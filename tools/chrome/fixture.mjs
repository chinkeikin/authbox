import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:https';
import { resolve } from 'node:path';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { buildApi } from '../bundling/api.mjs';
import { makeBackupFixture, fixturePassword } from '../protocol/fixtures.mjs';
import { createBackup } from '../../packages/protocol/src/backup.mjs';
import { canonical, sha256, utf8 } from '../../packages/protocol/src/encoding.mjs';
import { createAccountRecoveryCodes } from '../../packages/protocol/src/account-recovery.mjs';
import { sealRecord } from '../../packages/protocol/src/vault.mjs';
import { signChallenge } from '../../packages/protocol/src/permissions.mjs';
import { device } from '../../services/api/test/suite.mjs';

export async function makeChromeFixture() {
  const fixture = await makeBackupFixture();
  const first = fixture.payloads[0]; first.payload.name = 'GitHub work'; first.payload.notes = 'renew-access-index';
  fixture.payloads.push({ id: randomUUID(), type: 'account', payload: { ...first.payload, name: '<img src=x onerror=alert(1)>', account: 'secondary@example.invalid',
    favorite: false, groupId: null, tagIds: [], notes: 'SECONDARY keyword', algorithm: 'SHA256', order: 1 } },
    { id: randomUUID(), type: 'account', payload: { ...first.payload, name: 'Personal mail', account: 'third@example.invalid',
      favorite: false, notes: 'third searchable note', algorithm: 'SHA512', digits: 6, period: 45, order: 2 } },
    { id: randomUUID(), type: 'account', payload: { ...first.payload, name: 'Deleted account', deletedAt: '2026-10-16T00:00:00.000Z', order: 3 } });
  const source = JSON.parse(fixture.backup); delete source.manifest; delete source.digest;
  source.entities = await Promise.all(fixture.payloads.map((item) => sealRecord(fixture.vault.vmk,
    { vaultId: source.vaultId, id: item.id, type: item.type, revision: 1, keyGeneration: 1 }, item.payload)));
  source.entities.sort((a, b) => a.id.localeCompare(b.id));
  fixture.backup = await createBackup(fixture.vault.vmk, source);
  return fixture;
}

export async function seedReaderAccount(call, invite, fixture, user = 'chrome_browser') {
  const manager = await device(); const recovery = await createAccountRecoveryCodes();
  const context = { username: user, device: manager.value, recoveryHashes: recovery.hashes, backup: fixture.backup };
  const hash = await sha256(utf8(canonical(context)));
  const challenge = await call('/v1/auth/challenge', { operation: 'auth.register', username: user, deviceId: manager.value.id, requestHash: hash, invite });
  const registered = await call('/v1/auth/register', { ...context, password: 'Synthetic Chrome platform password 🔐', invite,
    proof: { id: challenge.id, signature: await signChallenge(manager.keys.privateKey, challenge.payload) } });
  return { ...registered, manager, username: user, password: 'Synthetic Chrome platform password 🔐', vaultPassword: fixturePassword };
}

export async function startLocalFixture() {
  const directory = resolve('.cache/chrome/local-' + randomUUID()); await mkdir(directory, { recursive: true });
  await writeFile(resolve(directory, 'openssl.cnf'), '[req]\ndistinguished_name=dn\n[dn]\n');
  await promisify(execFile)(process.env.AUTHBOX_OPENSSL ?? 'openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-config', resolve(directory, 'openssl.cnf'), '-keyout', resolve(directory, 'fixture.key'), '-out', resolve(directory, 'fixture.crt'),
    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'], { windowsHide: true });
  const token = randomBytes(32).toString('hex'); const invite = randomBytes(32).toString('base64url');
  let mf; const calls = []; let snapshotGate;
  const server = createServer({ key: await readFile(resolve(directory, 'fixture.key')), cert: await readFile(resolve(directory, 'fixture.crt')) }, async (req, res) => {
    try {
      if (req.url === '/probe') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); res.end('<!doctype html><title>AuthBox synthetic untrusted context</title>'); return; }
      const body = []; for await (const chunk of req) body.push(chunk);
      calls.push({ path: req.url.split('?')[0], method: req.method });
      if (req.url.startsWith('/v1/vault/snapshot') && snapshotGate) await snapshotGate();
      const response = await mf.dispatchFetch(origin + req.url, { method: req.method,
        headers: { ...req.headers, 'X-AuthBox-Fixture': token }, body: body.length ? Buffer.concat(body) : undefined });
      res.writeHead(response.status, Object.fromEntries(response.headers)); res.end(Buffer.from(await response.arrayBuffer()));
    } catch { res.writeHead(503).end(); }
  });
  const listen = (port) => new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  await listen(0); const port = server.address().port; const origin = `https://127.0.0.1:${port}`;
  const manifest = JSON.parse(await readFile('apps/chrome/manifest.json', 'utf8'));
  const id = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32).replace(/[0-9a-f]/g, (x) => String.fromCharCode(97 + parseInt(x, 16)));
  const bundle = await buildApi({ fixture: true });
  mf = new Miniflare(convertV4MiniflareOptions({ name: 'authbox-chrome-local', ...bundle.localOptions, compatibilityDate: '2026-10-16',
    durableObjects: { INSTALLATION: { className: 'FixtureStore', useSQLite: true } }, durableObjectsPersist: directory,
    bindings: { API_ORIGIN: origin, FIXTURE_TOKEN: token, FIXTURE_EXPIRES_AT: String(Date.now() + 1800000),
      REGISTRATION_INVITE_HASHES: JSON.stringify([await sha256(utf8(invite))]), CLIENT_ORIGINS: JSON.stringify([`chrome-extension://${id}`]) } }));
  async function call(path, body, access) {
    const response = await mf.dispatchFetch(origin + path, { method: body ? 'POST' : 'GET', headers: { 'X-AuthBox-Fixture': token,
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...(access ? { Authorization: `Bearer ${access}` } : {}) }, body: body ? canonical(body) : undefined });
    const text = await response.text(); assert.ok(response.ok, `Synthetic fixture ${path}: HTTP ${response.status}`);
    return JSON.parse(text);
  }
  const fixture = await makeChromeFixture(); const account = await seedReaderAccount(call, invite, fixture);
  return { directory, origin, id, fixture, account, calls, call,
    gateSnapshots(callback) { snapshotGate = callback; },
    async commit(backup) {
      const payload = { operationId: randomUUID(), baseRevision: JSON.parse(backup).revision - 1, restoredIds: [], backup };
      const challenge = await call('/v1/challenges', { operation: 'sync.commit', requestHash: await sha256(utf8(canonical(payload))) }, account.session.accessToken);
      return call('/v1/vault/commit', { payload, proof: { id: challenge.id, signature: await signChallenge(account.manager.keys.privateKey, challenge.payload) } }, account.session.accessToken);
    },
    async pause() { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); }, async resume() { await listen(port); },
    async close() { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); await mf.dispose(); fixture.vault.vmk.fill(0); } };
}
