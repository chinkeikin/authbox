import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { build } from 'esbuild';
import { argon2id as nobleArgon2id } from '@noble/hashes/argon2.js';
import { argon2id as wasmArgon2id } from 'hash-wasm';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import {
  fixturePassword,
  fixtureSalt,
  hex,
  rfcExpected,
  rfcProfile,
  serverProfile,
  wasmOptions,
} from './fixtures.mjs';

const outputDirectory = '.cache/feasibility/worker';
await mkdir(outputDirectory, { recursive: true });

const nodeRfc = nobleArgon2id(new Uint8Array(32).fill(1), new Uint8Array(16).fill(2), rfcProfile);
assert.equal(hex(nodeRfc), rfcExpected);
const nodeWasmStart = performance.now();
const expected = await wasmArgon2id(wasmOptions(serverProfile));
const nodeWasmWallMs = performance.now() - nodeWasmStart;
assert.equal(hex(nobleArgon2id(fixturePassword, fixtureSalt, serverProfile)), expected);

const bundle = await build({
  entryPoints: ['tools/feasibility/worker.mjs'],
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'browser',
  target: 'es2022',
});
const token = randomBytes(32).toString('hex');
const mf = new Miniflare(convertV4MiniflareOptions({
  name: 'authbox-feasibility',
  modules: true,
  script: bundle.outputFiles[0].text,
  compatibilityDate: '2026-10-16',
  bindings: { PROBE_TOKEN: token },
}));

async function request(path, authorized = true, method = 'POST') {
  const start = performance.now();
  const response = await mf.dispatchFetch(`http://localhost${path}`, {
    method,
    headers: authorized ? { Authorization: `Bearer ${token}` } : {},
  });
  const text = await response.text();
  return { status: response.status, text, wallMs: performance.now() - start };
}

try {
  assert.equal((await request('/noble', false)).status, 401);
  assert.equal((await request('/noble', true, 'GET')).status, 405);
  assert.equal((await request('/unknown')).status, 404);
  const rfc = await request('/rfc');
  assert.equal(rfc.status, 200, rfc.text);
  assert.equal(JSON.parse(rfc.text).hash, rfcExpected);

  const samples = [];
  for (let index = 0; index < 3; index += 1) {
    const sample = await request('/noble');
    assert.equal(sample.status, 200, sample.text);
    assert.equal(JSON.parse(sample.text).hash, expected);
    samples.push(Math.round(sample.wallMs * 100) / 100);
  }

  const wasm = await request('/wasm');
  let wasmCompatibility;
  if (wasm.status === 200) {
    assert.equal(JSON.parse(wasm.text).hash, expected);
    wasmCompatibility = { supported: true, hashMatches: true };
  } else {
    assert.equal(wasm.status, 422, wasm.text);
    const error = JSON.parse(wasm.text).error;
    assert.match(error, /WebAssembly|Wasm|wasm|code generation|disallowed/i);
    wasmCompatibility = { supported: false, error };
  }

  const report = {
    environment: 'local workerd through Miniflare; not Cloudflare production',
    node: process.version,
    platform: process.platform,
    compatibilityDate: '2026-10-16',
    generatedAt: new Date().toISOString(),
    profile: serverProfile,
    standardVector: 'RFC 9106 section 5.3 passed in Node and workerd',
    crossImplementation: 'Node hash-wasm agrees with Node and workerd noble',
    accessChecks: 'unauthorized, invalid method and unknown route rejected',
    nobleLocalRequestWallMs: samples,
    nodeHashWasmWallMs: Math.round(nodeWasmWallMs * 100) / 100,
    hashWasmWorkerd: wasmCompatibility,
    cloudCpuTime: 'not measured; local wall time cannot establish free-tier eligibility',
  };
  await writeFile(`${outputDirectory}/report.json`, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
} finally {
  await mf.dispose();
}
