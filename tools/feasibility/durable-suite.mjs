import assert from 'node:assert/strict';
import { argon2Sync } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { durableMarker, fixturePassword, rfcExpected, serverProfile } from './fixtures.mjs';

export const durableFixtures = [
  { username: 'fixture-owner-a', password: fixturePassword },
  { username: 'fixture-owner-b', password: fixturePassword },
  { username: 'fixture-unicode', password: 'AuthBox自定义密码🔐e\u0301' },
];

export async function runDurableSuite(dispatch, token, { onSample = () => {}, afterInspect } = {}) {
  const samples = [];
  async function request(action, label, { input, status = 200, authorized = true, method = 'POST', raw } = {}) {
    const start = performance.now();
    const result = await dispatch(`https://probe.invalid/do/${label}/${action}`, {
      method, headers: { 'Content-Type': 'application/json', ...(authorized ? { Authorization: `Bearer ${token}` } : {}) },
      ...(method === 'POST' && (input || raw) ? { body: raw ?? JSON.stringify(input) } : {}),
    });
    const text = await result.text();
    let data;
    try { data = JSON.parse(text); } catch { data = {}; }
    const sample = { label, action, status: result.status, reachedProbe: result.headers.get('X-AuthBox-Probe') === durableMarker,
      requestWallMs: Math.round((performance.now() - start) * 100) / 100 };
    samples.push(sample);
    onSample(sample);
    assert.equal(result.status, status, `Unexpected status for ${label}; no response body logged`);
    assert.equal(sample.reachedProbe, true, `Probe marker absent for ${label}`);
    return data;
  }
  await request('login', 'unauthorized', { authorized: false, status: 401 });
  await request('login', 'invalid-method', { method: 'GET', status: 405 });
  await request('unknown', 'unknown-route', { status: 404 });
  await request('register', 'invalid-json', { raw: '{', status: 400 });
  await request('register', 'oversized-input', { raw: 'x'.repeat(2049), status: 400 });
  const vector = await request('vector', 'standard-vector');
  assert.equal(vector.hash, rfcExpected);
  assert.equal(vector.matches, true);
  for (const [index, input] of durableFixtures.entries()) {
    const registered = await request('register', `register-${index + 1}`, { input, status: 201 });
    assert.equal(registered.registered, true);
    assert.deepEqual(registered.profile, serverProfile);
  }
  await request('register', 'duplicate-register', { input: durableFixtures[0], status: 409 });
  for (let index = 0; index < 3; index += 1) {
    const loggedIn = await request('login', `sequential-${index + 1}`, { input: durableFixtures[0] });
    assert.equal(loggedIn.verified, true);
    assert.deepEqual(loggedIn.profile, serverProfile);
  }
  await request('login', 'wrong-password', { input: { ...durableFixtures[0], password: 'Definitely the wrong fixture password' }, status: 401 });
  await request('login', 'unknown-user', { input: { username: 'fixture-missing', password: fixturePassword }, status: 401 });
  const unicode = await request('login', 'unicode-password', { input: durableFixtures[2] });
  assert.equal(unicode.verified, true);
  const concurrent = await Promise.allSettled([
    request('login', 'concurrent-1', { input: durableFixtures[0] }),
    request('login', 'concurrent-2', { input: durableFixtures[1] }),
    request('login', 'concurrent-3', { input: { ...durableFixtures[0], password: 'Another incorrect fixture password' }, status: 401 }),
    request('login', 'concurrent-4', { input: durableFixtures[2] }),
  ]);
  for (const result of concurrent) {
    if (result.status === 'rejected') throw result.reason;
  }
  const snapshot = await request('inspect', 'inspect-storage');
  assert.equal(snapshot.engine, 'SQLite');
  assert.equal(snapshot.rows.length, durableFixtures.length);
  assert.equal(snapshot.maxActiveHashes, 1);
  const salts = [];
  for (const row of snapshot.rows) {
    assert.deepEqual(Object.keys(row).sort(), ['username', 'password_hash', 'memory_kib', 'iterations', 'parallelism', 'hash_bytes'].sort());
    assert.equal(row.memory_kib, serverProfile.m);
    assert.equal(row.iterations, serverProfile.t);
    assert.equal(row.parallelism, serverProfile.p);
    assert.equal(row.hash_bytes, serverProfile.dkLen);
    const match = row.password_hash.match(/^\$argon2id\$v=19\$m=32768,t=3,p=1\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)$/);
    assert.ok(match, 'Stored hash must retain the reviewed Argon2id profile');
    const salt = Buffer.from(match[1], 'base64');
    assert.equal(salt.length, 16);
    const fixture = durableFixtures.find((item) => item.username === row.username);
    const expected = argon2Sync('argon2id', { message: fixture.password, nonce: salt, memory: serverProfile.m,
      passes: serverProfile.t, parallelism: serverProfile.p, tagLength: serverProfile.dkLen });
    assert.deepEqual(Buffer.from(match[2], 'base64'), expected);
    for (const secret of durableFixtures.map((item) => item.password)) assert.ok(!JSON.stringify(row).includes(secret));
    salts.push(match[1]);
  }
  assert.equal(new Set(salts).size, durableFixtures.length);
  await afterInspect?.();
  await request('reset', 'reset-runtime', { status: 502 });
  const restored = await request('inspect', 'inspect-after-reset');
  assert.notEqual(restored.instance, snapshot.instance, 'Runtime did not restart');
  assert.deepEqual(restored.rows, snapshot.rows, 'SQLite data changed after runtime reset');
  assert.equal((await request('login', 'login-after-reset', { input: durableFixtures[0] })).verified, true);
  await request('clear', 'clear-fixtures');
  assert.equal((await request('inspect', 'inspect-after-clear')).rows.length, 0);
  return { samples, checks: { standardVector: true, customCredentials: true, unicodePassword: true, randomSaltPerAccount: true,
    nodeNativeHashCrossCheck: true, wrongPasswordRejected: true, unknownUserRejected: true, duplicateRejected: true,
    fourConcurrentRequests: true, maxActiveHashes: snapshot.maxActiveHashes, sqlitePersistenceAfterRuntimeReset: true, fixtureDataCleared: true },
    instanceResetVerified: true, profile: serverProfile };
}
