import assert from 'node:assert/strict';
import { argon2Sync, createCipheriv, hkdfSync } from 'node:crypto';
import { before, test } from 'node:test';
import { base64url, canonical, fromUtf8, unbase64url, utf8 } from '../src/encoding.mjs';
import { createBackup, inspectBackup, MAX_BACKUP_BYTES, restoreBackup } from '../src/backup.mjs';
import { createVault, decodeRecoveryKey, derivePasswordKey, deriveRecoveryKey, encodeRecoveryKey, LOGIN_KDF, openRecord,
  recordAad, sealRecord, unwrapVmk, VAULT_KDF, wrapAad, wrapVmk } from '../src/vault.mjs';
import { serverProfile, vaultProfile } from '../../../tools/feasibility/fixtures.mjs';

const password = 'AuthBox 公开密码🔐e\u0301';
const id = '10000000-0000-4000-8000-000000000001';
const otherVault = '20000000-0000-4000-8000-000000000001';
let vault;
let record;
let backup;
let source;
const clone = structuredClone;
const changeByte = (encoded) => { const bytes = unbase64url(encoded, 1, 100000); bytes[0] ^= 1; return base64url(bytes); };

before(async () => {
  vault = await createVault(password);
  record = await sealRecord(vault.vmk, { vaultId: vault.vaultId, id, type: 'account', revision: 1, keyGeneration: 1 },
    { issuer: '测试', account: 'alice@example.com', secret: 'JBSWY3DPEHPK3PXP', tags: ['工作'], favorite: true });
  source = { magic: 'AUTHBOX-BACKUP', version: 1, vaultId: vault.vaultId, revision: 1, keyGeneration: 1,
    createdAt: '2026-10-16T00:00:00.000Z', appVersion: '0.0.0', wraps: vault.wraps, entities: [record] };
  backup = await createBackup(vault.vmk, source);
});

test('fixed KDF strengths and native Node Argon2id agree on exact UTF8 bytes', async () => {
  assert.deepEqual(LOGIN_KDF, { memoryKiB: 32768, iterations: 3, parallelism: 1, hashBytes: 32 });
  assert.equal(VAULT_KDF.memoryKiB, 65536);
  assert.deepEqual(serverProfile, { m: LOGIN_KDF.memoryKiB, t: LOGIN_KDF.iterations, p: LOGIN_KDF.parallelism, dkLen: LOGIN_KDF.hashBytes });
  assert.deepEqual(vaultProfile, { m: VAULT_KDF.memoryKiB, t: VAULT_KDF.iterations, p: VAULT_KDF.parallelism, dkLen: VAULT_KDF.hashBytes });
  const salt = new Uint8Array(16).fill(42);
  const expected = argon2Sync('argon2id', { message: utf8(password), nonce: salt, memory: 65536, passes: 3, parallelism: 1, tagLength: 32 });
  assert.deepEqual(Buffer.from(await derivePasswordKey(password, salt)), expected);
  assert.notDeepEqual(await derivePasswordKey(password.normalize('NFC'), salt), expected);
});

test('independent HKDF implementation and recovery checksum', async () => {
  const raw = new Uint8Array(32).fill(17);
  const salt = new Uint8Array(32).fill(42);
  const expected = hkdfSync('sha256', raw, salt, utf8('AuthBox/v1/vmk-recovery'), 32);
  assert.deepEqual(await deriveRecoveryKey(raw, salt), new Uint8Array(expected));
  const encoded = await encodeRecoveryKey(raw);
  assert.deepEqual(await decodeRecoveryKey(encoded), raw);
  await assert.rejects(decodeRecoveryKey(encoded.slice(0, -1) + (encoded.endsWith('A') ? 'B' : 'A')));
  await assert.rejects(decodeRecoveryKey(encoded.replace('ABR1-', 'ABR2-')));
});

test('password and recovery independently unwrap identical random VMK', async () => {
  assert.deepEqual(await unwrapVmk(vault.wraps.password, password), vault.vmk);
  assert.deepEqual(await unwrapVmk(vault.wraps.recovery, vault.recoveryKey), vault.vmk);
  await assert.rejects(unwrapVmk(vault.wraps.password, 'incorrect password'), /Authentication failed/);
  const other = await encodeRecoveryKey(new Uint8Array(32).fill(123));
  await assert.rejects(unwrapVmk(vault.wraps.recovery, other), /Authentication failed/);
});

test('random salt and nonce, password rewrap preserves data and old offline backups', async () => {
  const context = { vaultId: vault.vaultId, keyGeneration: 1 };
  const samePassword = await wrapVmk(vault.vmk, context, 'password', password);
  assert.notEqual(samePassword.kdf.salt, vault.wraps.password.kdf.salt);
  assert.notEqual(samePassword.nonce, vault.wraps.password.nonce);
  const changed = await wrapVmk(vault.vmk, context, 'password', 'new independent password');
  const key = await unwrapVmk(changed, 'new independent password');
  assert.equal((await openRecord(key, record)).issuer, '测试');
  await assert.rejects(unwrapVmk(changed, password));
  assert.equal((await restoreBackup(backup, { purpose: 'password', secret: password })).entities.length, 1);
});

test('independent native AES-GCM encrypt matches the record ciphertext and AAD', async () => {
  const plaintext = await openRecord(vault.vmk, record);
  const aad = utf8(`["AuthBox",1,"entity","${vault.vaultId}","${id}","account",1,1]`);
  assert.deepEqual(recordAad(record), aad);
  const cipher = createCipheriv('aes-256-gcm', vault.vmk, unbase64url(record.nonce, 12));
  cipher.setAAD(aad);
  const ciphertext = Buffer.concat([cipher.update(utf8(canonical(plaintext))), cipher.final(), cipher.getAuthTag()]);
  assert.equal(base64url(ciphertext), record.ciphertext);
  const second = await sealRecord(vault.vmk, { vaultId: record.vaultId, id, type: 'account', revision: 1, keyGeneration: 1 }, plaintext);
  assert.notEqual(second.nonce, record.nonce);
  assert.notEqual(second.ciphertext, record.ciphertext);
});

test('all identity fields, ciphertext, tag, nonce and keys are authenticated', async () => {
  for (const mutation of [
    (v) => { v.vaultId = otherVault; }, (v) => { v.id = otherVault; }, (v) => { v.type = 'group'; },
    (v) => { v.revision += 1; }, (v) => { v.keyGeneration += 1; },
    (v) => { v.nonce = changeByte(v.nonce); }, (v) => { v.ciphertext = changeByte(v.ciphertext); },
    (v) => { const bytes = unbase64url(v.ciphertext, 16, 100000); bytes[bytes.length - 1] ^= 1; v.ciphertext = base64url(bytes); },
    (v) => { v.version = 2; }, (v) => { v.unknown = true; },
  ]) {
    const changed = clone(record);
    mutation(changed);
    await assert.rejects(openRecord(vault.vmk, changed));
  }
  await assert.rejects(openRecord(new Uint8Array(32), record), /Authentication failed/);
  for (const field of ['vaultId', 'keyGeneration', 'purpose', 'kdf', 'version']) {
    const changed = clone(vault.wraps.recovery);
    if (field === 'vaultId') changed[field] = otherVault;
    else if (field === 'keyGeneration' || field === 'version') changed[field] += 1;
    else if (field === 'purpose') changed[field] = 'password';
    else changed.kdf.salt = changeByte(changed.kdf.salt);
    await assert.rejects(unwrapVmk(changed, vault.recoveryKey));
  }
  assert.equal(fromUtf8(wrapAad(vault.wraps.password)).includes('argon2id'), true);
});

test('untrusted KDF parameters and allocation limits are rejected before hashing', async () => {
  for (const [key, value] of [['memoryKiB', 1], ['memoryKiB', 1073741824], ['iterations', 1], ['iterations', 999999],
    ['parallelism', 2], ['hashBytes', 4096], ['version', 16], ['name', 'PBKDF2'], ['salt', 'a']]) {
    const changed = clone(vault.wraps.password);
    changed.kdf[key] = value;
    await assert.rejects(unwrapVmk(changed, password), /Unsupported|Invalid/);
  }
  await assert.rejects(derivePasswordKey('short', new Uint8Array(16)));
  await assert.rejects(derivePasswordKey('\ud800password', new Uint8Array(16)));
  await assert.rejects(derivePasswordKey('a'.repeat(1025), new Uint8Array(16)));
  const requests = Array.from({ length: 5 }, () => derivePasswordKey(password, new Uint8Array(16)));
  const results = await Promise.allSettled(requests);
  assert.equal(results.filter((v) => v.status === 'fulfilled').length, 4);
  assert.equal(results[4].reason.message, 'Vault KDF queue full');
});

test('complete backup restores offline via either secret and contains no unwrapped material', async () => {
  const inspected = await inspectBackup(backup);
  assert.deepEqual(inspected.entities, [record]);
  assert.equal(backup.includes(password), false);
  assert.equal(backup.includes(vault.recoveryKey), false);
  assert.equal(backup.includes(base64url(vault.vmk)), false);
  assert.equal(backup.includes('alice@example.com'), false);
  const byPassword = await restoreBackup(backup, { purpose: 'password', secret: password });
  const byRecovery = await restoreBackup(backup, { purpose: 'recovery', secret: vault.recoveryKey });
  assert.deepEqual(byPassword, byRecovery);
  assert.equal(byRecovery.entities[0].payload.secret, 'JBSWY3DPEHPK3PXP');
  await assert.rejects(restoreBackup(backup, { purpose: 'password', secret: 'bad password' }));
});

async function recomputeChecksum(changed) {
  const { digest: ignored, ...body } = changed;
  const digest = base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', utf8(canonical(body)))));
  return canonical({ ...body, digest });
}

test('authenticated backup manifest rejects omissions and substitutions even after checksum is recomputed', async () => {
  for (const mutate of [
    (v) => { v.entities = []; }, (v) => { v.appVersion = '0.0.1'; },
    (v) => { v.createdAt = '2026-10-17T00:00:00.000Z'; },
    (v) => { v.entities[0].ciphertext = changeByte(v.entities[0].ciphertext); },
    (v) => { v.wraps.password.kdf.salt = changeByte(v.wraps.password.kdf.salt); },
  ]) {
    const changed = JSON.parse(backup);
    mutate(changed);
    const encoded = await recomputeChecksum(changed);
    await inspectBackup(encoded);
    await assert.rejects(restoreBackup(encoded, { purpose: 'recovery', secret: vault.recoveryKey }), /manifest authentication failed/);
  }
});

test('truncated, oversized, unknown versions, duplicated ids, mixed vaults and malformed encodings fail', async () => {
  for (const mutate of [
    (v) => { v.version = 2; }, (v) => { v.magic = 'OTHER'; }, (v) => { v.entities.push(clone(v.entities[0])); },
    (v) => { v.entities[0].vaultId = otherVault; }, (v) => { v.entities[0].revision = 2; },
    (v) => { v.wraps.password.vaultId = otherVault; }, (v) => { v.wraps.password.extra = 1; },
    (v) => { v.entities[0].ciphertext += '='; }, (v) => { v.createdAt = '2026-02-30T00:00:00.000Z'; },
    (v) => { v.manifest.type = 'account'; }, (v) => { v.extra = 'unknown'; },
  ]) {
    const changed = JSON.parse(backup);
    mutate(changed);
    await assert.rejects(inspectBackup(canonical(changed)));
  }
  await assert.rejects(inspectBackup(backup.slice(0, -1)));
  await assert.rejects(inspectBackup(' '.repeat(MAX_BACKUP_BYTES + 1)));
  await assert.rejects(inspectBackup(JSON.stringify(JSON.parse(backup), null, 2)), /canonical/);
  await assert.rejects(inspectBackup(backup.replace('"version":1', '"version":1,"version":1')), /canonical/);
  const changed = JSON.parse(backup);
  changed.entities[0].ciphertext = changeByte(changed.entities[0].ciphertext);
  await assert.rejects(inspectBackup(canonical(changed)), /checksum/);
});

test('empty vault remains a complete authenticated backup', async () => {
  const encoded = await createBackup(vault.vmk, { ...source, entities: [] });
  assert.deepEqual((await restoreBackup(encoded, { purpose: 'recovery', secret: vault.recoveryKey })).entities, []);
});

test('record and backup limits reject oversized plaintext, excess entities and nonce reuse', async () => {
  const metadata = { vaultId: record.vaultId, id, type: 'account', revision: 1, keyGeneration: 1 };
  await assert.rejects(sealRecord(vault.vmk, metadata, { text: 'a'.repeat(65536) }), /payload/);
  await assert.rejects(sealRecord(vault.vmk, { ...metadata, revision: 0 }, {}), /integer/);
  await assert.rejects(createBackup(vault.vmk, { ...source, entities: Array.from({ length: 10001 }, () => record) }), /entity limit/);
  const second = { ...record, id: otherVault };
  await assert.rejects(createBackup(vault.vmk, { ...source, entities: [record, second] }), /manifest/);
});

test('restore authenticates every entity before returning a result', async () => {
  const invalid = clone(record);
  invalid.ciphertext = changeByte(invalid.ciphertext);
  const encoded = await createBackup(vault.vmk, { ...source, entities: [invalid] });
  await inspectBackup(encoded);
  await assert.rejects(restoreBackup(encoded, { purpose: 'recovery', secret: vault.recoveryKey }), /Authentication failed/);
});

test('canonical JSON rejects unsupported data, invalid Unicode, sparse arrays and excessive nesting', () => {
  for (const invalid of [undefined, { a: undefined }, { a: NaN }, { a: 0.5 }, '\ud800', [, 1], new Date(), { a: 1n }]) {
    assert.throws(() => canonical(invalid));
  }
  let nested = 'x';
  for (let index = 0; index < 34; index += 1) nested = { nested };
  assert.throws(() => canonical(nested), /nesting/);
  assert.equal(canonical({ z: 1, a: ['测试', true, null] }), '{"a":["测试",true,null],"z":1}');
});
