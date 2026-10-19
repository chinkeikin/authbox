import assert from 'node:assert/strict';
import { argon2Sync } from 'node:crypto';
import { canonical, base64url, randomBytes, sha256, utf8 } from '../../../packages/protocol/src/encoding.mjs';
import { createAccountRecoveryCodes } from '../../../packages/protocol/src/account-recovery.mjs';
import { createBackup, restoreBackup } from '../../../packages/protocol/src/backup.mjs';
import { publicDeviceKey, signChallenge } from '../../../packages/protocol/src/permissions.mjs';
import { sealRecord } from '../../../packages/protocol/src/vault.mjs';
import { validateVaultEntities } from '../../../packages/protocol/src/business.mjs';
import { fixturePassword, makeBackupFixture } from '../../../tools/protocol/fixtures.mjs';

const hash = (value) => sha256(utf8(canonical(value)));
const DAY = 86400000;
export const apiFixturePassword = 'AuthBox login 公开密码🔐e\u0301';

export async function device() {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const exported = await crypto.subtle.exportKey('jwk', keys.publicKey);
  return { keys, value: { id: crypto.randomUUID(), name: 'Synthetic Android', publicKey: publicDeviceKey({ kty: exported.kty, crv: exported.crv, x: exported.x, y: exported.y }) } };
}

export async function runApiSuite(dispatch, { origin, fixtureToken, invites, onCase = () => {} }) {
  const results = [];
  let offset = 0;
  let serial = 0;
  async function call(label, path, { payload, token, expected = 200, method = payload === undefined ? 'GET' : 'POST', headers = {}, body } = {}) {
    const response = await dispatch(`${origin}${path}`, { method, headers: { 'X-AuthBox-Fixture': fixtureToken,
      'X-AuthBox-Case': label, 'CF-Connecting-IP': `192.0.2.${++serial % 200 + 1}`, ...(payload !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }, body: body ?? (payload === undefined ? undefined : canonical(payload)) });
    const text = await response.text();
    assert.equal(response.status, expected, `${label}: HTTP ${response.status}, expected ${expected}; ${text.slice(0, 120)}`);
    assert.equal(response.headers.get('Cache-Control'), 'no-store', label);
    const row = { label, status: response.status };
    results.push(row); onCase(row);
    let json;
    try { json = text ? JSON.parse(text) : undefined; } catch {}
    return { value: json, text, headers: response.headers };
  }
  async function clock(delta = 61000) {
    offset += delta;
    await call(`clock-${offset}`, `/__test/clock?offset=${offset}`);
  }
  async function proofFor(label, operation, payload, manager, token) {
    const challenge = (await call(`${label}-challenge`, '/v1/challenges', { payload: { operation, requestHash: await hash(payload) }, token })).value;
    return { id: challenge.id, signature: await signChallenge(manager.keys.privateKey, challenge.payload) };
  }
  async function managed(label, path, operation, payload, manager, token, expected = 200) {
    const proof = await proofFor(label, operation, payload, manager, token);
    return call(label, path, { payload: { payload, proof }, token, expected });
  }
  async function authProof(label, operation, context, manager, invite = null) {
    const challenge = (await call(`${label}-challenge`, '/v1/auth/challenge', { payload: { operation, username: context.username,
      deviceId: context.device?.id ?? context.deviceId, requestHash: await hash(context), invite } })).value;
    return { id: challenge.id, signature: await signChallenge(manager.keys.privateKey, challenge.payload) };
  }

  await call('health', '/health');
  await call('unauthorized', '/v1/vault/head', { expected: 401 });
  await call('wrong-origin', '/health', { headers: { Origin: 'https://evil.invalid' }, expected: 403 });
  assert.equal((await call('allowed-origin', '/health', { headers: { Origin: 'https://client.example.invalid' } })).headers.get('Access-Control-Allow-Origin'), 'https://client.example.invalid');
  await call('preflight-allowed', '/v1/vault/head', { method: 'OPTIONS', headers: { Origin: 'https://client.example.invalid', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'Authorization, Content-Type' }, expected: 204 });
  await call('preflight-denied', '/v1/vault/head', { method: 'OPTIONS', headers: { Origin: 'https://evil.invalid', 'Access-Control-Request-Method': 'POST' }, expected: 403 });
  await call('bad-json', '/v1/auth/login', { body: '{', method: 'POST', headers: { 'Content-Type': 'application/json' }, expected: 400 });
  await call('duplicate-json-key', '/v1/auth/login', { body: '{"username":"fixture_alice","username":"fixture_bob"}', method: 'POST', headers: { 'Content-Type': 'application/json' }, expected: 400 });
  await call('oversized-body', '/v1/auth/login', { body: 'a'.repeat(2 * 1024 * 1024 + 20000), method: 'POST', headers: { 'Content-Type': 'application/json' }, expected: 413 });
  const first = await makeBackupFixture();
  const manager = await device();
  const recovery = await createAccountRecoveryCodes();
  const context = { username: 'fixture_alice', device: manager.value, recoveryHashes: recovery.hashes, backup: first.backup };
  const proof = await authProof('register', 'auth.register', context, manager, invites[0]);
  const registration = { ...context, password: apiFixturePassword, invite: invites[0], proof };
  await call('register-fault-set', `/__test/fault?kind=session&id=${manager.value.id}`);
  await call('register-rollback', '/v1/auth/register', { payload: registration, expected: 500 });
  const before = (await call('register-rollback-audit', '/__test/audit')).value;
  assert.equal(before.users.length, 0); assert.equal(before.devices.length, 0); assert.equal(before.snapshotCount, 0);
  assert.ok(before.invitations.every((row) => row.user_id === null)); assert.equal(before.nonces.length, 0);
  await call('register-fault-clear', '/__test/fault');
  const registered = (await call('register-success', '/v1/auth/register', { payload: registration, expected: 201 })).value;
  let managerToken = registered.session.accessToken;
  assert.equal(registered.device.role, 'manager');
  await call('invite-single-use', '/v1/auth/register', { payload: registration, expected: 403 });
  const pulled = await call('initial-snapshot', '/v1/vault/snapshot', { token: managerToken });
  assert.equal(pulled.text, first.backup);
  const restored = await restoreBackup(pulled.text, { purpose: 'password', secret: fixturePassword });
  validateVaultEntities(restored.entities);
  await clock();

  const reader = (await call('reader-login', '/v1/auth/login', { payload: { username: context.username, password: apiFixturePassword, deviceName: 'Synthetic Chrome', publicKey: null } })).value;
  const readerToken = reader.session.accessToken;
  assert.equal(reader.device.role, 'reader');
  await call('reader-pull', '/v1/vault/snapshot', { token: readerToken });
  for (const path of ['/v1/vault/commit', '/v1/devices/approve', '/v1/devices/revoke', '/v1/account/password/prepare', '/v1/account/password/confirm', '/v1/account/recovery/rotate', '/v1/challenges']) {
    await call(`reader-deny-${path.split('/').slice(-2).join('-')}`, path, { token: readerToken, payload: { role: 'manager', client_type: 'mobile' }, expected: 403 });
  }
  await call('reader-devices-denied', '/v1/devices', { token: readerToken, expected: 403 });
  await call('client-role-rejected', '/v1/auth/login', { payload: { username: context.username, password: apiFixturePassword, deviceName: 'Forged mobile', publicKey: null, role: 'manager' }, expected: 400 });
  await call('wrong-password', '/v1/auth/login', { payload: { username: context.username, password: 'incorrect password', deviceName: 'Wrong', publicKey: null }, expected: 401 });
  await call('unknown-user', '/v1/auth/login', { payload: { username: 'fixture_missing', password: apiFixturePassword, deviceName: 'Wrong', publicKey: null }, expected: 401 });
  await call('reader-devices-cannot-promote', '/v1/devices/approve', { token: managerToken,
    payload: { payload: { deviceId: reader.device.id }, proof: await proofFor('chrome-promote', 'device.approve', { deviceId: reader.device.id }, manager, managerToken) }, expected: 409 });

  const secondManager = await device();
  const candidate = (await call('candidate-login', '/v1/auth/login', { payload: { username: context.username, password: apiFixturePassword,
    deviceName: 'Second Android', publicKey: secondManager.value.publicKey } })).value;
  secondManager.value.id = candidate.device.id;
  await managed('approve-mobile', '/v1/devices/approve', 'device.approve', { deviceId: candidate.device.id }, manager, managerToken);
  await call('approval-revokes-reader-session', '/v1/vault/head', { token: candidate.session.accessToken, expected: 401 });
  const deviceContext = { username: context.username, deviceId: candidate.device.id };
  const deviceProof = await authProof('device-login', 'auth.device-login', deviceContext, secondManager);
  const secondSession = (await call('device-login-manager', '/v1/auth/device-login', { payload: { ...deviceContext, password: apiFixturePassword, proof: deviceProof } })).value;
  assert.equal(secondSession.device.role, 'manager');
  const secondToken = secondSession.session.accessToken;
  await call('device-proof-single-use', '/v1/auth/device-login', { payload: { ...deviceContext, password: apiFixturePassword, proof: deviceProof }, expected: 403 });
  await clock();

  async function nextBackup(previous, revision, alter = undefined, omit = []) {
    const entities = [];
    for (const record of previous.entities) {
      if (omit.includes(record.id)) continue;
      if (alter && record.id === alter.id) entities.push(await sealRecord(first.vault.vmk,
        { vaultId: previous.vaultId, id: record.id, type: record.type, revision, keyGeneration: previous.keyGeneration }, alter.payload));
      else entities.push(record);
    }
    return createBackup(first.vault.vmk, { magic: previous.magic, version: 1, vaultId: previous.vaultId, revision,
      keyGeneration: previous.keyGeneration, createdAt: new Date(Date.now()).toISOString(), appVersion: '0.0.0', wraps: previous.wraps, entities });
  }
  const previous = JSON.parse(first.backup);
  const changedPayload = { ...first.payloads[0].payload, name: 'Updated synthetic account' };
  const next = await nextBackup(previous, 2, { id: previous.entities[0].id, payload: changedPayload });
  const commit = { operationId: crypto.randomUUID(), baseRevision: 1, restoredIds: [], backup: next };
  const commitProof = await proofFor('commit', 'sync.commit', commit, manager, managerToken);
  await call('commit-fault-set', `/__test/fault?kind=operation&id=${commit.operationId}`);
  await call('commit-rollback', '/v1/vault/commit', { token: managerToken, payload: { payload: commit, proof: commitProof }, expected: 500 });
  assert.equal((await call('rollback-head', '/v1/vault/head', { token: managerToken })).value.revision, 1);
  assert.equal((await call('rollback-snapshot', '/v1/vault/snapshot', { token: managerToken })).text, first.backup);
  await call('commit-fault-clear', '/__test/fault');
  const receipt = (await call('commit-success', '/v1/vault/commit', { token: managerToken, payload: { payload: commit, proof: commitProof } })).value;
  assert.equal(receipt.revision, 2);
  assert.deepEqual((await call('commit-retry', '/v1/vault/commit', { token: managerToken, payload: { payload: commit, proof: commitProof } })).value, receipt);
  await call('operation-id-mismatch', '/v1/vault/commit', { token: managerToken, payload: { payload: { ...commit, restoredIds: [crypto.randomUUID()] }, proof: commitProof }, expected: 409 });
  const tampered = { ...commit, operationId: crypto.randomUUID(), baseRevision: 2, backup: await nextBackup(JSON.parse(next), 3) };
  await call('proof-request-bound', '/v1/vault/commit', { token: managerToken, payload: { payload: tampered, proof: commitProof }, expected: 403 });
  await call('proof-session-bound', '/v1/vault/commit', { token: secondToken, payload: { payload: tampered, proof: await proofFor('session-bind', 'sync.commit', tampered, manager, managerToken) }, expected: 403 });
  await call('history-read', '/v1/vault/snapshot?revision=1', { token: readerToken });
  assert.equal((await call('changes-full', '/v1/vault/changes?after=1', { token: readerToken })).value.mode, 'full');
  assert.equal((await call('changes-current', '/v1/vault/changes?after=2', { token: readerToken })).value.mode, 'unchanged');
  await call('future-cursor', '/v1/vault/changes?after=3', { token: readerToken, expected: 400 });
  const competing = { ...tampered, operationId: crypto.randomUUID() };
  const aProof = await proofFor('concurrent-a', 'sync.commit', tampered, manager, managerToken);
  const bProof = await proofFor('concurrent-b', 'sync.commit', competing, secondManager, secondToken);
  const requests = await Promise.all([dispatch(`${origin}/v1/vault/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${managerToken}`, 'X-AuthBox-Fixture': fixtureToken }, body: canonical({ payload: tampered, proof: aProof }) }),
    dispatch(`${origin}/v1/vault/commit`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${secondToken}`, 'X-AuthBox-Fixture': fixtureToken }, body: canonical({ payload: competing, proof: bProof }) })]);
  assert.deepEqual(requests.map((response) => response.status).sort(), [200, 409]);
  for (const response of requests) await response.body?.cancel();
  results.push({ label: 'concurrent-cas-one-winner', statuses: [200, 409] }); onCase(results.at(-1));
  const current = JSON.parse((await call('after-concurrent-snapshot', '/v1/vault/snapshot', { token: readerToken })).text);
  assert.equal(current.revision, 3);

  const removedId = current.entities[0].id;
  const removedBackup = await nextBackup(current, 4, undefined, [removedId]);
  await managed('remove-entity', '/v1/vault/commit', 'sync.commit', { operationId: crypto.randomUUID(), baseRevision: 3, restoredIds: [], backup: removedBackup }, manager, managerToken);
  const retired = (await call('retired-ids-manager', '/v1/vault/retired', { token: managerToken })).value;
  assert.equal(retired.revision, 4); assert.deepEqual(retired.retiredIds, [removedId]);
  await call('retired-reader-denied', '/v1/vault/retired', { token: readerToken, expected: 403 });
  await call('retired-query-denied', '/v1/vault/retired?userId=other', { token: managerToken, expected: 400 });
  const history = (await call('history-first-page', '/v1/vault/history?limit=2', { token: readerToken })).value;
  assert.deepEqual(history.entries.map((row) => row.revision), [4, 3]); assert.equal(history.nextBefore, 3);
  const older = (await call('history-next-page', '/v1/vault/history?limit=2&before=3', { token: managerToken })).value;
  assert.deepEqual(older.entries.map((row) => row.revision), [2, 1]); assert.equal(older.nextBefore, null);
  assert.equal(older.entries[0].digest, JSON.parse(next).digest);
  for (const query of ['before=5', 'before=0', 'before=01', 'before=2&before=3', 'limit=21', 'limit=0', 'userId=other']) {
    await call('history-invalid-query', '/v1/vault/history?' + query, { token: managerToken, expected: 400 });
  }
  const restoredRecord = await sealRecord(first.vault.vmk, { vaultId: current.vaultId, id: removedId, type: 'account', revision: 5, keyGeneration: 1 }, changedPayload);
  const restoredEntities = [restoredRecord, ...JSON.parse(removedBackup).entities].sort((a, b) => a.id.localeCompare(b.id));
  const restoreSource = JSON.parse(removedBackup); delete restoreSource.digest; delete restoreSource.manifest;
  const restoredBackup = await createBackup(first.vault.vmk, { ...restoreSource, revision: 5, entities: restoredEntities });
  const restoring = { operationId: crypto.randomUUID(), baseRevision: 4, restoredIds: [], backup: restoredBackup };
  await managed('silent-resurrection-denied', '/v1/vault/commit', 'sync.commit', restoring, manager, managerToken, 400);
  await managed('explicit-history-restore', '/v1/vault/commit', 'sync.commit', { ...restoring, restoredIds: [removedId] }, manager, managerToken);
  assert.deepEqual((await call('retired-cleared-after-restore', '/v1/vault/retired', { token: managerToken })).value.retiredIds, []);
  await clock();

  const otherFixture = await makeBackupFixture();
  const otherManager = await device();
  const otherRecovery = await createAccountRecoveryCodes();
  const otherContext = { username: 'fixture_bob', device: otherManager.value, recoveryHashes: otherRecovery.hashes, backup: otherFixture.backup };
  const otherProof = await authProof('other-register', 'auth.register', otherContext, otherManager, invites[1]);
  const other = (await call('other-register', '/v1/auth/register', { payload: { ...otherContext, password: apiFixturePassword, invite: invites[1], proof: otherProof }, expected: 201 })).value;
  assert.equal((await call('other-user-snapshot', '/v1/vault/snapshot', { token: other.session.accessToken })).text, otherFixture.backup);
  const isolatedHistory = (await call('history-other-user-isolated', '/v1/vault/history', { token: other.session.accessToken })).value;
  assert.equal(isolatedHistory.vaultId, otherFixture.vault.vaultId); assert.deepEqual(isolatedHistory.entries.map((row) => row.revision), [1]);
  await call('cross-user-query-rejected', `/v1/vault/snapshot?userId=${registered.user.id}`, { token: other.session.accessToken, expected: 400 });
  await managed('cross-user-device-denied', '/v1/devices/revoke', 'device.revoke', { deviceId: manager.value.id }, otherManager, other.session.accessToken, 404);

  const refreshed = (await call('refresh-rotate', '/v1/auth/refresh', { payload: { refreshToken: reader.session.refreshToken } })).value;
  assert.equal(refreshed.device.role, 'reader');
  await call('old-access-replaced', '/v1/vault/head', { token: readerToken, expected: 401 });
  await call('rotated-access-valid', '/v1/vault/head', { token: refreshed.session.accessToken });
  await call('refresh-reuse-revokes-family', '/v1/auth/refresh', { payload: { refreshToken: reader.session.refreshToken }, expected: 401 });
  await call('reuse-new-access-revoked', '/v1/vault/head', { token: refreshed.session.accessToken, expected: 401 });
  await call('reuse-new-refresh-revoked', '/v1/auth/refresh', { payload: { refreshToken: refreshed.session.refreshToken }, expected: 401 });
  await managed('revoke-secondary', '/v1/devices/revoke', 'device.revoke', { deviceId: secondManager.value.id }, manager, managerToken);
  await call('revocation-immediate', '/v1/vault/head', { token: secondToken, expected: 401 });
  await call('revoked-retry-denied', '/v1/vault/commit', { token: secondToken, payload: { payload: competing, proof: bProof }, expected: 401 });
  await clock();

  const prepare = (await call('password-prepare', '/v1/account/password/prepare', { token: managerToken,
    payload: { currentPassword: apiFixturePassword, newPassword: 'new platform password 🔐' } })).value;
  const passwordProof = { id: prepare.id, signature: await signChallenge(manager.keys.privateKey, prepare.payload) };
  const changedPassword = (await call('password-confirm', '/v1/account/password/confirm', { token: managerToken, payload: { proof: passwordProof } })).value;
  await call('password-change-revokes-old-session', '/v1/vault/head', { token: managerToken, expected: 401 });
  managerToken = changedPassword.session.accessToken;
  await call('old-password-denied', '/v1/auth/login', { payload: { username: context.username, password: apiFixturePassword, deviceName: 'Old password', publicKey: null }, expected: 401 });
  await call('password-proof-replay', '/v1/account/password/confirm', { token: managerToken, payload: { proof: passwordProof }, expected: 403 });
  await call('generic-password-challenge-denied', '/v1/challenges', { token: managerToken, payload: { operation: 'password.change', requestHash: await hash({}) }, expected: 400 });
  await call('password-prepare-wrong-current', '/v1/account/password/prepare', { token: managerToken,
    payload: { currentPassword: apiFixturePassword, newPassword: 'another new platform password' }, expected: 401 });

  const recoveredManager = await device();
  const recoverContext = { username: context.username, device: recoveredManager.value };
  const recoveryProof = await authProof('recover', 'auth.recover', recoverContext, recoveredManager);
  const recoverBody = { ...recoverContext, password: 'recovered platform password 🔐', code: recovery.codes[0], proof: recoveryProof };
  await call('recover-fault-set', `/__test/fault?kind=session&id=${recoveredManager.value.id}`);
  await call('recover-rollback', '/v1/auth/recover', { payload: recoverBody, expected: 500 });
  await call('recover-rollback-old-session-valid', '/v1/vault/head', { token: managerToken });
  await call('recover-fault-clear', '/__test/fault');
  const recoveryResponses = await Promise.all([dispatch(`${origin}/v1/auth/recover`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-AuthBox-Fixture': fixtureToken }, body: canonical(recoverBody) }),
    dispatch(`${origin}/v1/auth/recover`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-AuthBox-Fixture': fixtureToken }, body: canonical(recoverBody) })]);
  assert.deepEqual(recoveryResponses.map((response) => response.status).sort(), [200, 401]);
  let recovered;
  for (const response of recoveryResponses) { const value = await response.json(); if (response.status === 200) recovered = value; }
  results.push({ label: 'concurrent-recovery-one-winner', statuses: [200, 401] }); onCase(results.at(-1));
  await call('recovery-revokes-old-manager', '/v1/vault/head', { token: managerToken, expected: 401 });
  const finalBackup = (await call('recovery-keeps-vault', '/v1/vault/snapshot', { token: recovered.session.accessToken })).text;
  const finalVault = await restoreBackup(finalBackup, { purpose: 'recovery', secret: first.vault.recoveryKey });
  validateVaultEntities(finalVault.entities);
  assert.equal(finalVault.revision, 5); assert.equal(finalVault.entities[0].payload.name, changedPayload.name);
  await call('recovery-code-single-use', '/v1/auth/recover', { payload: recoverBody, expected: 401 });
  await clock();

  const rotation = await createAccountRecoveryCodes();
  await managed('rotate-recovery', '/v1/account/recovery/rotate', 'recovery.rotate', { hashes: rotation.hashes }, recoveredManager, recovered.session.accessToken);
  await managed('recovery-old-material-cannot-reactivate', '/v1/account/recovery/rotate', 'recovery.rotate', { hashes: recovery.hashes }, recoveredManager, recovered.session.accessToken, 409);
  await call('rotated-old-code-denied', '/v1/auth/recover', { payload: { ...recoverBody, code: recovery.codes[1] }, expected: 401 });
  const nonceSource = JSON.parse(finalBackup);
  delete nonceSource.manifest; delete nonceSource.digest;
  nonceSource.revision = 6;
  nonceSource.entities[0] = { ...nonceSource.entities[0], revision: 6 };
  const noncePayload = { operationId: crypto.randomUUID(), baseRevision: 5, restoredIds: [], backup: await createBackup(first.vault.vmk, nonceSource) };
  await managed('historical-nonce-reuse-denied', '/v1/vault/commit', 'sync.commit', noncePayload, recoveredManager, recovered.session.accessToken, 409);
  assert.equal((await call('nonce-failure-keeps-head', '/v1/vault/head', { token: recovered.session.accessToken })).value.revision, 5);

  const audit = (await call('audit', '/__test/audit')).value;
  assert.equal(audit.users.length, 2);
  for (const user of audit.users) {
    assert.match(user.password_hash, /^\$argon2id\$v=19\$m=32768,t=3,p=1\$/);
    const parts = user.password_hash.split('$');
    const known = user.username === context.username ? recoverBody.password : apiFixturePassword;
    const native = argon2Sync('argon2id', { message: utf8(known), nonce: Buffer.from(parts[4], 'base64'), memory: 32768, passes: 3, parallelism: 1, tagLength: 32 });
    assert.equal(native.toString('base64').replace(/=+$/, ''), parts[5]);
  }
  const serialized = JSON.stringify(audit);
  for (const forbidden of [apiFixturePassword, recoverBody.password, fixturePassword, first.vault.recoveryKey, ...recovery.codes,
    recovered.session.accessToken, recovered.session.refreshToken, 'fixture@example.invalid', 'Updated synthetic account']) assert.equal(serialized.includes(forbidden), false, 'Stored plaintext secret');
  const devicesBefore = audit.devices.length;
  await call('runtime-reset', '/__test/reset', { expected: 503 });
  assert.equal((await call('snapshot-after-reset', '/v1/vault/snapshot', { token: recovered.session.accessToken })).text, finalBackup);
  const auditAfter = (await call('audit-after-reset', '/__test/audit')).value;
  assert.equal(auditAfter.devices.length, devicesBefore);
  assert.equal(auditAfter.snapshotCount, audit.snapshotCount);

  const rateBody = { operation: 'auth.device-login', username: 'fixture_rate', deviceId: crypto.randomUUID(), requestHash: await hash({}), invite: null };
  for (let index = 0; index < 20; index++) await call(`rate-${index + 1}`, '/v1/auth/challenge', { payload: rateBody });
  await call('rate-reset', '/__test/reset', { expected: 503 });
  await call('rate-persists-after-reset', '/v1/auth/challenge', { payload: rateBody, expected: 429 });

  const expiringPayload = { deviceId: other.device.id };
  const expiringProof = await proofFor('expiring-proof', 'device.revoke', expiringPayload, recoveredManager, recovered.session.accessToken);
  await clock(91000);
  await call('expired-proof-denied', '/v1/devices/revoke', { token: recovered.session.accessToken, payload: { payload: expiringPayload, proof: expiringProof }, expected: 403 });
  await clock(601000);
  await call('access-expired', '/v1/vault/head', { token: recovered.session.accessToken, expected: 401 });
  const afterExpiry = (await call('refresh-after-access-expiry', '/v1/auth/refresh', { payload: { refreshToken: recovered.session.refreshToken } })).value;
  await clock(31 * DAY);
  await call('idle-refresh-expired', '/v1/auth/refresh', { payload: { refreshToken: afterExpiry.session.refreshToken }, expected: 401 });
  const reconnectContext = { username: context.username, deviceId: recoveredManager.value.id };
  const reconnectProof = await authProof('reconnect-after-idle', 'auth.device-login', reconnectContext, recoveredManager);
  const reconnected = (await call('reconnect-after-idle', '/v1/auth/device-login', { payload: { ...reconnectContext, password: recoverBody.password, proof: reconnectProof } })).value;
  await call('old-cursor-reset-required', '/v1/vault/changes?after=1', { token: reconnected.session.accessToken, expected: 410 });
  await call('old-history-expired', '/v1/vault/snapshot?revision=1', { token: reconnected.session.accessToken, expected: 404 });
  assert.deepEqual((await call('history-retains-expired-current-only', '/v1/vault/history', { token: reconnected.session.accessToken })).value.entries.map((row) => row.revision), [5]);
  await call('current-snapshot-retained', '/v1/vault/snapshot', { token: reconnected.session.accessToken });
  let absoluteToken = reconnected.session.refreshToken;
  for (let index = 0; index < 3; index++) {
    await clock(25 * DAY);
    absoluteToken = (await call(`absolute-renew-${index + 1}`, '/v1/auth/refresh', { payload: { refreshToken: absoluteToken } })).value.session.refreshToken;
  }
  await clock(16 * DAY);
  await call('absolute-expiry-no-extension', '/v1/auth/refresh', { payload: { refreshToken: absoluteToken }, expected: 401 });

  return { results, checks: ['Registration and recovery fault rollback', 'Single-use invitation and account recovery', 'No plaintext credentials or vault data in SQLite',
    'Reader cannot mutate or escalate', 'Bound one-use device proofs', 'Same-user approvals and immediate revocation', 'Complete snapshot CAS and idempotency',
    'Concurrent commits have one winner', 'Explicit history restore', 'Cross-user isolation', 'Refresh rotation and reuse revocation', 'Password reauthentication and change',
    'Runtime reset persistence', 'Challenge, access and refresh expiry', 'Native Node verifies stored Argon2id profile'], generatedAt: new Date().toISOString() };
}
