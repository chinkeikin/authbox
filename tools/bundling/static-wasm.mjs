import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const sourceSha256 = '2d6333a619d7f64adc313a38732425fc0c6f0baaa36c1419cf05672bcd89340d';
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');

export async function buildStaticWasm(entryPoint = 'tools/feasibility/cloud-worker.mjs', options = {}) {
  const distRoot = dirname(fileURLToPath(import.meta.resolve('hash-wasm')));
  const upstreamRoot = resolve(distRoot, '..');
  const metadata = JSON.parse(await readFile(resolve(upstreamRoot, 'package.json'), 'utf8'));
  assert.equal(metadata.version, '4.12.0', 'Static loader requires reviewed hash-wasm version');
  const raw = await readFile(resolve(distRoot, 'index.esm.js'));
  assert.equal(digest(raw), sourceSha256, 'Upstream source changed; review loader adaptation');
  let source = raw.toString('utf8').replaceAll('\r\n', '\n');
  const directory = resolve(options.directory ?? '.cache/feasibility/static-wasm');
  await mkdir(directory, { recursive: true });
  const modules = [];
  const declarations = /var name(\$\w+)? = "(argon2|blake2b)";\s*var data\1 = "([A-Za-z0-9+/=]+)";\s*var hash\1 = "([0-9a-f]+)";/g;
  for (const match of source.matchAll(declarations)) {
    const name = `${match[2]}.wasm`;
    const contents = Buffer.from(match[3], 'base64');
    assert.equal(WebAssembly.validate(contents), true, `Invalid upstream module ${name}`);
    assert.ok(!modules.some((item) => item.name === name), 'Duplicate upstream module');
    const path = resolve(directory, name);
    await writeFile(path, contents);
    modules.push({ name, path, contents, sha256: digest(contents) });
  }
  assert.deepEqual(modules.map((item) => item.name).sort(), ['argon2.wasm', 'blake2b.wasm']);
  function replaceOnce(before, after) {
    assert.equal(source.split(before).length, 2, 'Reviewed upstream loader fragment changed');
    source = source.replace(before, after);
  }
  replaceOnce('const wasmModuleCache = new Map();', 'const wasmModuleCache = new Map([["argon2", Promise.resolve(authboxArgon2Module)], ["blake2b", Promise.resolve(authboxBlake2bModule)]]);');
  replaceOnce(`            if (!wasmModuleCache.has(binary.name)) {
                const asm = decodeBase64(binary.data);
                const promise = WebAssembly.compile(asm);
                wasmModuleCache.set(binary.name, promise);
            }`, `            if (!wasmModuleCache.has(binary.name)) {
                throw new Error("Unregistered static WASM module");
            }`);
  assert.ok(!source.includes('WebAssembly.compile('), 'Dynamic WASM compilation remains');
  source = `import authboxArgon2Module from './argon2.wasm';\nimport authboxBlake2bModule from './blake2b.wasm';\n${source}`;
  const adaptedPath = resolve(directory, 'hash-wasm-static.mjs');
  await writeFile(adaptedPath, source);
  const license = await readFile(resolve(upstreamRoot, 'LICENSE'), 'utf8');
  const headers = await Promise.all(['argon2.c', 'blake2b.c'].map(async (name) => {
    const text = await readFile(resolve(upstreamRoot, 'src', name), 'utf8');
    assert.ok(text.startsWith('/*'));
    return `${name}:\n${text.slice(2, text.indexOf('*/')).trim()}`;
  }));
  const notice = `${license}\n\n${headers.join('\n\n')}\n\nBLAKE2 reference implementation license choice: CC0 1.0 Universal.\nhttps://creativecommons.org/publicdomain/zero/1.0/\n`;
  await writeFile(resolve(directory, 'THIRD-PARTY-NOTICES.txt'), notice);
  const bundled = await build({
    entryPoints: [entryPoint], bundle: true, write: false, format: 'esm', platform: 'browser', target: 'es2022', minify: true,
    external: ['./argon2.wasm', './blake2b.wasm'], banner: { js: `/*!\n${options.notices ?? notice}\n*/` },
    plugins: [{ name: 'authbox-static-wasm-loader', setup(builder) {
      builder.onResolve({ filter: /^hash-wasm$/ }, () => ({ path: adaptedPath }));
    } }],
  });
  const script = bundled.outputFiles[0].text;
  assert.ok(!script.includes('WebAssembly.compile('));
  const scriptPath = resolve(directory, 'probe.mjs');
  await writeFile(scriptPath, script);
  return {
    script, modules,
    localOptions: { modulesRoot: directory, modules: [
      { type: 'ESModule', path: scriptPath, contents: script },
      ...modules.map(({ path, contents }) => ({ type: 'CompiledWasm', path, contents })),
    ] },
    manifest: { library: 'hash-wasm', version: metadata.version, sourceSha256, adaptation: 'static module loader only',
      modules: modules.map(({ name, sha256, contents }) => ({ name, sha256, bytes: contents.byteLength })) },
  };
}
