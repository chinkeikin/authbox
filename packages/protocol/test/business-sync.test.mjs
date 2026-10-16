import assert from 'node:assert/strict';
import { before, test } from 'node:test';
import { validateBusinessEntity, validateVaultEntities, username, loginPassword } from '../src/business.mjs';
import { authorize, challengePayload, publicDeviceKey, signChallenge, verifyChallenge } from '../src/permissions.mjs';
import { MAX_ONLINE_BACKUP_BYTES, validateCommit, validateTransition } from '../src/sync.mjs';
import { base64url, canonical, randomBytes, sha256, utf8 } from '../src/encoding.mjs';
import { makeBackupFixture } from '../../../tools/protocol/fixtures.mjs';
import { accountRecoveryHash, createAccountRecoveryCodes, validateRecoveryHashes } from '../src/account-recovery.mjs';

const id = '10000000-0000-4000-8000-000000000001';
let fixture;
let snapshot;
before(async () => { fixture = await makeBackupFixture(); snapshot = JSON.parse(fixture.backup); });

function business() {
  return fixture.payloads.map(({ id, type, payload }) => ({ metadata: { id, type, revision: 1 }, payload: structuredClone(payload) }));
}

test('versioned entities preserve groups, tags, favorites, order and soft deletion', () => {
  const entities = business();
  validateVaultEntities(entities);
  entities[0].payload.deletedAt = '2026-10-16T00:00:00.000Z';
  validateVaultEntities(entities);
  entities[0].payload.groupId = null;
  entities[0].payload.tagIds = [];
  validateVaultEntities(entities.filter((entity) => !['group', 'tag'].includes(entity.metadata.type)));
});

test('reject unknown fields, missing references, duplicate identities, malformed TOTP and future schemas', () => {
  for (const mutation of [
    (v) => { v[0].payload.schemaVersion = 2; }, (v) => { v[0].payload.extra = 'unknown'; },
    (v) => { v[0].payload.algorithm = 'MD5'; }, (v) => { v[0].payload.secret = v[0].payload.secret.toLowerCase(); },
    (v) => { v[0].payload.favorite = 'true'; }, (v) => { v[0].payload.order = -1; },
    (v) => { v[0].payload.groupId = id; }, (v) => { v[0].payload.tagIds.push(v[0].payload.tagIds[0]); },
    (v) => { v[0].payload.deletedAt = '2026-02-30T00:00:00.000Z'; }, (v) => { v[0].payload.notes = 'x'.repeat(4097); },
    (v) => { v.push(structuredClone(v[0])); }, (v) => { v.splice(1, 1); }, (v) => { v.splice(2, 1); },
    (v) => { v[3].payload.defaultView = 'unknown'; }, (v) => { v[1].payload.name = '\u0000'; },
  ]) {
    const entities = business(); mutation(entities);
    assert.throws(() => validateVaultEntities(entities));
  }
  assert.throws(() => validateBusinessEntity({ id, type: 'manifest', revision: 1 }, { schemaVersion: 1 }));
});

test('account identifiers and passwords are bounded without normalization', () => {
  username('alice_01'); loginPassword('公开测试密码🔐');
  for (const value of ['Alice', 'aa', 'a b', 'a'.repeat(33), '用户']) assert.throws(() => username(value));
  for (const value of ['short', 'a'.repeat(257), '\ud800password']) assert.throws(() => loginPassword(value));
});

test('account recovery uses independent 128-bit single-use code material and domain-separated hashes', async () => {
  const first = await createAccountRecoveryCodes();
  const second = await createAccountRecoveryCodes();
  validateRecoveryHashes(first.hashes);
  assert.equal(new Set([...first.codes, ...second.codes]).size, 16);
  assert.equal(first.hashes[0], await accountRecoveryHash(first.codes[0]));
  assert.throws(() => validateRecoveryHashes(Array(8).fill(first.hashes[0])));
  await assert.rejects(accountRecoveryHash('ABR1-not-a-vault-recovery-key'));
  await assert.rejects(accountRecoveryHash('ABAC1-a'));
});

test('read-only and backup credentials cannot create management challenges or mutate state', () => {
  for (const operation of ['sync.commit', 'device.approve', 'device.revoke', 'password.change', 'recovery.rotate', 'device.list']) {
    assert.equal(authorize({ role: 'reader' }, operation), false);
    assert.equal(authorize({ role: 'backup_reader' }, operation), false);
    assert.equal(authorize({ role: 'manager' }, operation), true);
  }
  assert.equal(authorize({ role: 'reader' }, 'vault.read'), true);
  assert.equal(authorize({ role: 'backup_reader' }, 'backup.read'), true);
  assert.equal(authorize({ role: 'backup_reader' }, 'vault.read'), false);
  assert.equal(authorize({ role: 'manager', revoked: true }, 'vault.read'), false);
  assert.equal(authorize({ role: 'manager' }, 'unknown'), false);
});

test('P-256 device proofs bind API origin, session, operation, request and expiry', async () => {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const exported = await crypto.subtle.exportKey('jwk', keys.publicKey);
  const publicKey = publicDeviceKey({ kty: exported.kty, crv: exported.crv, x: exported.x, y: exported.y });
  const context = { origin: 'https://api.example.invalid', id: crypto.randomUUID(), subject: crypto.randomUUID(),
    deviceId: crypto.randomUUID(), sessionId: crypto.randomUUID(), operation: 'sync.commit',
    requestHash: await sha256(utf8('{}')), expiresAt: 1792110000000, nonce: base64url(randomBytes(32)) };
  const payload = challengePayload(context);
  const signature = await signChallenge(keys.privateKey, payload);
  assert.equal(await verifyChallenge(publicKey, payload, signature), true);
  for (const change of [{ origin: 'https://other.example.invalid' }, { sessionId: crypto.randomUUID() },
    { operation: 'device.revoke' }, { requestHash: await sha256(utf8('{"changed":true}')) }, { expiresAt: context.expiresAt + 1 }]) {
    assert.equal(await verifyChallenge(publicKey, challengePayload({ ...context, ...change }), signature), false);
  }
  assert.throws(() => publicDeviceKey({ ...publicKey, d: 'private-key' }));
});

test('complete snapshot commit is bounded and advances exactly one revision', async () => {
  const commit = { operationId: crypto.randomUUID(), baseRevision: 0, restoredIds: [], backup: fixture.backup };
  assert.deepEqual(await validateCommit(commit), snapshot);
  for (const changed of [{ ...commit, baseRevision: 1 }, { ...commit, role: 'manager' },
    { ...commit, backup: ' '.repeat(MAX_ONLINE_BACKUP_BYTES + 1) }, { ...commit, operationId: 'invalid' }]) {
    await assert.rejects(validateCommit(changed));
  }
});

test('snapshot transitions retain unchanged revisions and reject stale edits, vault substitution and silent resurrection', () => {
  validateTransition(undefined, snapshot);
  const next = { ...snapshot, revision: 2 };
  validateTransition(snapshot, next);
  const changed = structuredClone(next); changed.entities[0].nonce = base64url(randomBytes(12));
  assert.throws(() => validateTransition(snapshot, changed));
  changed.entities[0].revision = 2; validateTransition(snapshot, changed);
  assert.throws(() => validateTransition(snapshot, { ...next, vaultId: crypto.randomUUID() }));
  assert.throws(() => validateTransition(snapshot, { ...next, revision: 3 }));
  assert.throws(() => validateTransition(snapshot, { ...next, keyGeneration: 3 }));
  const missing = { ...next, entities: next.entities.filter((entity) => entity.id !== id) };
  assert.deepEqual(validateTransition(snapshot, missing).removed, [id]);
  const retired = new Map([[id, 'account']]);
  const restored = structuredClone(snapshot); restored.revision = 3; restored.entities[0].revision = 3;
  assert.throws(() => validateTransition(missing, restored, retired));
  validateTransition(missing, restored, retired, [id]);
  assert.throws(() => validateTransition(missing, restored, new Map([[id, 'tag']]), [id]));
  assert.throws(() => validateTransition(snapshot, next, new Map(), [id]));
  assert.equal(canonical(snapshot.entities[0]), canonical(next.entities[0]));
});
