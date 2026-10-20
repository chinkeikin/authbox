import assert from 'node:assert/strict';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { buildChrome } from '../bundling/chrome.mjs';

const built = await buildChrome(); const manifest = built.manifest;
assert.equal(manifest.manifest_version, 3); assert.equal(manifest.background.type, 'module');
assert.deepEqual(manifest.permissions, ['storage', 'alarms', 'clipboardWrite']);
assert.deepEqual(manifest.host_permissions, ['https://api.example.invalid/*']);
for (const key of ['content_scripts', 'web_accessible_resources', 'externally_connectable', 'oauth2', 'optional_host_permissions']) assert.equal(key in manifest, false);
assert.equal(manifest.content_security_policy.extension_pages.includes("'unsafe-eval'"), false);
assert.equal(manifest.content_security_policy.extension_pages.includes("'unsafe-inline'"), false);
const hashes = {};
for (const file of await readdir(built.directory)) {
  const bytes = await readFile(resolve(built.directory, file)); hashes[file] = createHash('sha256').update(bytes).digest('hex');
  if (!file.endsWith('.js')) continue;
  const source = bytes.toString();
  for (const forbidden of ['X-AuthBox-Fixture', '__test/', 'fixtureToken', 'chrome.storage.sync', 'localStorage', 'innerHTML', 'eval(', 'new Function(']) assert.equal(source.includes(forbidden), false, file + ': ' + forbidden);
}
for (const name of ['LICENSE', 'THIRD-PARTY-NOTICES.txt', 'index.html', 'style.css', 'background.js', 'ui.js', 'manifest.json']) assert.ok(hashes[name]);
const report = { generatedAt: new Date().toISOString(), extensionId: built.id, origin: built.origin, productionBundleChecksPassed: true,
  permissions: manifest.permissions, hostPermissions: manifest.host_permissions, fixtureCodeAbsent: true, hashes };
await writeFile('.cache/chrome/production-report.json', JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
