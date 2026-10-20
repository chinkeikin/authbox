import assert from 'node:assert/strict';
import { before, test } from 'node:test';
import { ReaderController, readerAuth } from '../src/reader.mjs';
import { fixturePassword, makeBackupFixture } from '../../../tools/protocol/fixtures.mjs';
import { createBackup, restoreBackup } from '../../../packages/protocol/src/backup.mjs';
import { base64url, canonical, randomBytes } from '../../../packages/protocol/src/encoding.mjs';
import { sealRecord } from '../../../packages/protocol/src/vault.mjs';
import { rotationFixture } from '../../../services/api/test/rotation.mjs';

let fixture;
before(async () => { fixture = await makeBackupFixture(); });
export function memoryStorage() {
  const area = () => {
    const values = {};
    return { values, async get(name) { return structuredClone(name ? { [name]: values[name] } : values); },
      async set(value) { Object.assign(values, structuredClone(value)); }, async remove(name) { delete values[name]; } };
  };
  return { local: area(), session: area() };
}
function setup() {
  const storage = memoryStorage();
  let time = Date.now();
  const sessionId = crypto.randomUUID(); const userId = crypto.randomUUID(); const deviceId = crypto.randomUUID();
  const auth = () => ({ user: { id: userId, username: 'chrome_reader' }, device: { id: deviceId, role: 'reader' }, session: {
    accessToken: `ab1a.${sessionId}.${base64url(randomBytes(32))}`, refreshToken: `ab1r.${sessionId}.${base64url(randomBytes(32))}`,
    accessExpiresAt: time + 600000, refreshExpiresAt: time + 30 * 86400000, absoluteExpiresAt: time + 90 * 86400000 } });
  const api = { backup: fixture.backup, offline: false, refreshLost: false, calls: [], status: 200, beforeSnapshot: null };
  const request = async (url, options) => {
    const path = new URL(url).pathname; api.calls.push({ path, body: options.body ? JSON.parse(options.body) : null });
    if (api.offline) throw new TypeError('Synthetic network failure');
    if (api.status !== 200) return new Response('{"error":"unauthorized"}', { status: api.status });
    if (path === '/v1/auth/login') return Response.json(auth());
    if (path === '/v1/auth/refresh') { if (api.refreshLost) throw new TypeError('Lost response'); return Response.json(auth()); }
    if (path === '/v1/auth/logout') return Response.json({ loggedOut: true });
    const backup = JSON.parse(api.backup);
    if (path === '/v1/vault/head') return Response.json({ vaultId: backup.vaultId, revision: backup.revision, keyGeneration: backup.keyGeneration });
    if (path === '/v1/vault/snapshot') { await api.beforeSnapshot?.(); return new Response(api.backup); }
    assert.fail('Reader attempted forbidden endpoint');
  };
  const controller = () => new ReaderController({ storage, request, now: () => time });
  const reader = controller();
  return { reader, controller, storage, api, auth, now: () => time, advance: (ms) => { time += ms; },
    async login() { await reader.dispatch('login', { origin: 'https://api.example.invalid', user: 'chrome_reader', password: 'Synthetic platform password' }); },
    async ready() { await this.login(); await reader.dispatch('sync', { manual: true }); await reader.dispatch('unlock', { password: fixturePassword }); } };
}
async function newer(name = 'Latest group') {
  const previous = JSON.parse(fixture.backup); const entities = [];
  for (const record of previous.entities) entities.push(record.type === 'group' ? await sealRecord(fixture.vault.vmk,
    { vaultId: previous.vaultId, id: record.id, type: record.type, revision: 2, keyGeneration: 1 }, { schemaVersion: 1, name, order: 0 }) : record);
  const { manifest, digest, ...body } = previous;
  return createBackup(fixture.vault.vmk, { ...body, revision: 2, entities });
}

test('reader login, authenticated cache, filtering and live TOTP never expose raw secrets', async () => {
  const t = setup(); await t.ready();
  const status = await t.reader.dispatch('status'); assert.equal(status.unlocked, true); assert.equal(status.revision, 1);
  const view = await t.reader.dispatch('list', { query: '常用' }); assert.equal(view.rows.length, 1); assert.equal(view.rows[0].favorite, true);
  assert.equal((await t.reader.dispatch('list', { group: 'none' })).rows.length, 0);
  assert.equal((await t.reader.dispatch('list', { tag: view.tags[0].id, favorites: false })).rows.length, 1);
  const copy = await t.reader.dispatch('copy', { id: view.rows[0].id }); assert.equal(copy.code.length, 8);
  const persistent = JSON.stringify(t.storage.local.values);
  for (const forbidden of [fixturePassword, fixture.vault.recoveryKey, base64url(fixture.vault.vmk), 'fixture@example.invalid', 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ']) assert.equal(persistent.includes(forbidden), false);
  assert.equal(JSON.stringify(view).includes('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'), false);
  assert.equal(t.api.calls.find(({ path }) => path === '/v1/auth/login').body.publicKey, null);
  await assert.rejects(t.reader.dispatch('write'), { code: 'unsupported_action' });
});

test('worker recreation retains unlock only within session deadline and browser restart clears it', async () => {
  const t = setup(); await t.ready();
  const restarted = t.controller(); assert.equal((await restarted.dispatch('list')).rows.length, 1);
  t.advance(5 * 60000); await assert.rejects(restarted.dispatch('copy', { id: fixture.payloads[0].id }), { code: 'locked' });
  assert.equal(t.storage.session.values.unlock, undefined);
  await restarted.dispatch('unlock', { password: fixturePassword });
  delete t.storage.session.values.unlock;
  assert.equal((await t.controller().dispatch('status')).unlocked, false);
  t.api.offline = true; await restarted.dispatch('unlock', { password: fixturePassword });
  assert.equal((await restarted.dispatch('list')).rows.length, 1);
});

test('synchronization authenticates newer data without password and never extends idle deadline', async () => {
  const t = setup(); await t.ready(); const expires = t.storage.session.values.unlock.expiresAt;
  t.api.backup = await newer(); t.advance(10000);
  await t.reader.dispatch('sync', { manual: true });
  assert.equal((await t.reader.dispatch('status')).revision, 2); assert.equal(t.storage.session.values.unlock.expiresAt, expires);
  assert.equal((await t.reader.dispatch('list')).rows[0].group, 'Latest group');
  await t.reader.dispatch('touch'); assert.equal(t.storage.session.values.unlock.expiresAt, t.now() + 300000);
});

test('invalid ciphertext, rollback and network failure preserve verified cache', async () => {
  const t = setup(); await t.ready(); const original = t.storage.local.values.reader.backup;
  const altered = JSON.parse(await newer()); altered.entities[0].ciphertext = base64url(randomBytes(48));
  const { manifest, digest, ...body } = altered; t.api.backup = await createBackup(fixture.vault.vmk, body);
  await assert.rejects(t.reader.dispatch('sync', { manual: true }));
  assert.equal(t.storage.local.values.reader.backup, original);
  t.api.offline = true; await assert.rejects(t.reader.dispatch('sync', { manual: true }), { code: 'network' });
  assert.equal((await t.reader.dispatch('list')).rows.length, 1);
  assert.equal(t.storage.local.values.reader.sync.status, 'offline');
  const before = t.api.calls.length; await t.reader.dispatch('sync'); assert.equal(t.api.calls.length, before);
});

test('trusted activity within the storage timestamp throttle preserves a consistent idle deadline', async () => {
  const t = setup(); await t.ready();
  for (const elapsed of [1, 400, 999, 1001]) {
    t.advance(elapsed);
    const status = await t.reader.dispatch('touch');
    assert.equal(status.unlocked, true);
    assert.equal(status.expiresAt, t.now() + 300000);
    assert.equal(t.storage.session.values.unlock.lastSeenAt, t.now());
  }
});

test('new generation requires new password and never silently overwrites old cache', async () => {
  const t = setup(); await t.ready(); const rotation = await rotationFixture(fixture);
  try {
    t.api.backup = rotation.backup; await t.reader.dispatch('sync', { manual: true });
    const status = await t.reader.dispatch('status'); assert.equal(status.unlocked, false); assert.equal(status.hasCandidate, true); assert.equal(status.revision, 1);
    await assert.rejects(t.reader.dispatch('unlock', { password: fixturePassword }), { code: 'unlock_failed' });
    await t.reader.dispatch('unlock', { password: fixturePassword, cached: true }); assert.equal((await t.reader.dispatch('status')).hasCandidate, true);
    await t.reader.dispatch('lock'); await t.reader.dispatch('unlock', { password: rotation.password });
    assert.equal((await t.reader.dispatch('status')).keyGeneration, 2); assert.equal((await t.reader.dispatch('status')).hasCandidate, false);
    t.api.backup = fixture.backup; await assert.rejects(t.reader.dispatch('sync', { manual: true }), { code: 'snapshot_regression' });
  } finally { rotation.vmk.fill(0); }
});

test('background grace, idle expiry and clock rollback are checked before using keys', async () => {
  const t = setup(); await t.ready(); await t.reader.dispatch('inactive'); assert.equal((await t.reader.dispatch('status')).unlocked, false);
  await t.reader.dispatch('preferences', { idleMinutes: 1, backgroundSeconds: 30, theme: 'dark' });
  await t.reader.dispatch('unlock', { password: fixturePassword }); await t.reader.dispatch('inactive');
  t.advance(31000); await t.reader.dispatch('active'); assert.equal((await t.reader.dispatch('status')).unlocked, false);
  await t.reader.dispatch('unlock', { password: fixturePassword }); t.advance(-1000);
  await assert.rejects(t.reader.dispatch('list'), { code: 'locked' });
});

test('immediate lock cancels late decrypt and late sync cannot restore plaintext', async () => {
  const t = setup(); await t.ready();
  t.api.backup = await newer(); let release; let entered;
  const blocked = new Promise((resolve) => { entered = resolve; });
  t.api.beforeSnapshot = () => { entered(); return new Promise((resolve) => { release = resolve; }); };
  const syncing = t.reader.dispatch('sync', { manual: true }); await blocked;
  const locking = t.reader.dispatch('lock'); release(); await syncing; await locking;
  assert.equal((await t.reader.dispatch('status')).unlocked, false); assert.equal(t.reader.opened, null);
  assert.equal(t.storage.local.values.reader.backup, fixture.backup);
});

test('uncertain refresh fails closed and the old refresh token is never retried', async () => {
  const t = setup(); await t.ready(); t.advance(590000); t.api.refreshLost = true;
  await assert.rejects(t.reader.dispatch('sync', { manual: true }), { code: 'refresh_uncertain' });
  await assert.rejects(t.reader.dispatch('sync', { manual: true }), { code: 'login_required' });
  assert.equal(t.api.calls.filter(({ path }) => path === '/v1/auth/refresh').length, 1);
  assert.equal((await t.reader.dispatch('status')).unlocked, false);
  await t.reader.dispatch('unlock', { password: fixturePassword }); assert.equal((await t.reader.dispatch('list')).rows.length, 1);
});

test('manual lock during an in-flight Argon2 unlock cannot be undone by its result', async () => {
  const t = setup(); await t.login(); await t.reader.dispatch('sync', { manual: true });
  const opening = t.reader.dispatch('unlock', { password: fixturePassword });
  const rejected = assert.rejects(opening, { code: 'cancelled' });
  await new Promise((resolve) => setTimeout(resolve, 1));
  const locking = t.reader.dispatch('lock'); await rejected; await locking;
  assert.equal((await t.reader.dispatch('status')).unlocked, false); assert.equal(t.reader.opened, null);
  assert.equal(t.storage.session.values.unlock, undefined);
});

test('revocation locks plaintext, preserves offline data and role escalation is rejected', async () => {
  const t = setup(); await t.ready(); t.api.status = 401;
  await assert.rejects(t.reader.dispatch('sync', { manual: true }));
  assert.equal((await t.reader.dispatch('status')).needsLogin, true); assert.equal((await t.reader.dispatch('status')).unlocked, false);
  const auth = t.auth(); auth.device.role = 'manager'; assert.throws(() => readerAuth(auth, 'chrome_reader', t.now()), { code: 'reader_required' });
  assert.equal(t.storage.local.values.reader.backup, fixture.backup);
});

test('logout and confirmed local erasure cannot mutate vault data', async () => {
  const t = setup(); await t.ready(); t.api.offline = true;
  const logout = await t.reader.dispatch('logout'); assert.equal(logout.logoutConfirmed, false); assert.equal(logout.hasCache, true);
  await assert.rejects(t.reader.dispatch('forget', { confirmed: false }), { code: 'confirmation' });
  await t.reader.dispatch('forget', { confirmed: true }); assert.deepEqual(t.storage.local.values, {});
  assert.equal(t.api.backup, fixture.backup);
});

test('corrupt session deadlines and persisted lock settings fail closed', async () => {
  const t = setup(); await t.ready(); t.storage.session.values.unlock.expiresAt = 'invalid';
  await assert.rejects(t.reader.dispatch('list'), { code: 'locked' }); assert.equal(t.reader.opened, null);
  t.storage.local.values.reader.preferences.idleMinutes = 100000;
  await assert.rejects(t.controller().dispatch('status'), { code: 'invalid_state' });
});
