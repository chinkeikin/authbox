import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { build } from 'esbuild';

export async function buildChrome({ directory = 'dist/chrome', origin = 'https://api.example.invalid', workerEntry = 'apps/chrome/src/background.mjs' } = {}) {
  const url = new URL(origin);
  assert.ok(url.protocol === 'https:' && url.origin === origin);
  const manifest = JSON.parse(await readFile('apps/chrome/manifest.json', 'utf8'));
  manifest.host_permissions = [`https://${url.hostname}/*`];
  manifest.content_security_policy.extension_pages = manifest.content_security_policy.extension_pages.replaceAll('https://api.example.invalid', origin);
  const output = resolve(directory); await mkdir(output, { recursive: true });
  await build({ entryPoints: { background: workerEntry, ui: 'apps/chrome/src/ui.mjs' }, outdir: output,
    bundle: true, format: 'esm', platform: 'browser', target: 'chrome116', legalComments: 'inline',
    define: { AUTHBOX_API_ORIGIN: JSON.stringify(origin) } });
  await writeFile(resolve(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  for (const name of ['index.html', 'style.css']) await copyFile(`apps/chrome/${name}`, resolve(output, name));
  for (const name of ['LICENSE', 'THIRD-PARTY-NOTICES.txt']) await copyFile(name, resolve(output, name));
  const id = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32)
    .replace(/[0-9a-f]/g, (value) => String.fromCharCode(97 + parseInt(value, 16)));
  return { directory: output, id, origin, manifest };
}
