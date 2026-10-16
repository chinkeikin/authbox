import { argon2Sync, createCipheriv, createHash, hkdfSync } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { Secret } from 'otpauth/slim';

const canonical = (value) => {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
};
const digest = (value) => createHash('sha256').update(canonical(value), 'utf8').digest('base64url');
const sequence = (length, start) => Buffer.from(Array.from({ length }, (_, index) => start + index));
const vmk = sequence(32, 1);
const rawRecovery = sequence(32, 65);
const recoveryKey = `ABR1-${new Secret({ buffer: Uint8Array.from(rawRecovery).buffer }).base32}-${createHash('sha256').update(rawRecovery).digest('base64url').slice(0, 8)}`;
const password = 'AuthBox 协议公开测试🔐e\u0301';
const vaultId = '50000000-0000-4000-8000-000000000001';
const profile = { name: 'argon2id', version: 19, memoryKiB: 65536, iterations: 3, parallelism: 1, hashBytes: 32 };
let nonceIndex = 0;
const seal = (key, header, aad, plaintext) => {
  const nonce = sequence(12, ++nonceIndex * 16);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(canonical(aad), 'utf8'));
  const ciphertext = Buffer.concat([cipher.update(Buffer.from(plaintext, 'utf8')), cipher.final(), cipher.getAuthTag()]);
  return { ...header, nonce: nonce.toString('base64url'), ciphertext: ciphertext.toString('base64url') };
};
const wrap = (purpose) => {
  const salt = sequence(purpose === 'password' ? 16 : 32, purpose === 'password' ? 17 : 33);
  const kdf = purpose === 'password' ? { ...profile, salt: salt.toString('base64url') }
    : { name: 'HKDF-SHA256', salt: salt.toString('base64url'), info: 'AuthBox/v1/vmk-recovery' };
  const key = purpose === 'password'
    ? argon2Sync('argon2id', { message: Buffer.from(password, 'utf8'), nonce: salt, memory: 65536, passes: 3, parallelism: 1, tagLength: 32 })
    : hkdfSync('sha256', rawRecovery, salt, Buffer.from('AuthBox/v1/vmk-recovery'), 32);
  const header = { version: 1, vaultId, keyGeneration: 1, purpose, kdf };
  const aad = ['AuthBox', 1, 'vmk-wrap', vaultId, 1, purpose, kdf];
  const nonce = sequence(12, ++nonceIndex * 16);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(canonical(aad), 'utf8'));
  const ciphertext = Buffer.concat([cipher.update(vmk), cipher.final(), cipher.getAuthTag()]);
  return { value: { ...header, nonce: nonce.toString('base64url'), ciphertext: ciphertext.toString('base64url') }, aadUtf8: canonical(aad) };
};
const passwordWrap = wrap('password');
const recoveryWrap = wrap('recovery');
const payloads = [
  { id: '10000000-0000-4000-8000-000000000001', type: 'account', payload: { issuer: '测试', account: 'fixture@example.invalid',
    secret: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', algorithm: 'SHA1', digits: 8, period: 30, favorite: true } },
  { id: '20000000-0000-4000-8000-000000000001', type: 'group', payload: { name: '工作', order: 0 } },
  { id: '30000000-0000-4000-8000-000000000001', type: 'tag', payload: { name: '常用' } },
  { id: '40000000-0000-4000-8000-000000000001', type: 'settings', payload: { defaultView: 'favorites' } },
];
const entityAads = payloads.map(({ id, type }) => ['AuthBox', 1, 'entity', vaultId, id, type, 1, 1]);
const entities = payloads.map(({ id, type, payload }, index) => seal(vmk,
  { version: 1, vaultId, id, type, revision: 1, keyGeneration: 1 }, entityAads[index], canonical(payload)));
const body = { magic: 'AUTHBOX-BACKUP', version: 1, vaultId, revision: 1, keyGeneration: 1,
  createdAt: '2026-10-16T00:00:00.000Z', appVersion: '0.0.0', wraps: { password: passwordWrap.value, recovery: recoveryWrap.value }, entities };
const manifest = seal(vmk, { version: 1, vaultId, id: vaultId, type: 'manifest', revision: 1, keyGeneration: 1 },
  ['AuthBox', 1, 'entity', vaultId, vaultId, 'manifest', 1, 1], canonical({ digest: digest(body), entityCount: entities.length }));
const backupBody = { ...body, manifest };
const result = { warning: 'PUBLIC SYNTHETIC VECTOR ONLY. Fixed keys, salts and nonces must never encrypt real data.',
  producer: 'Node native Argon2id, HKDF and AES-GCM; no protocol implementation imports',
  password, recoveryKey, vmkHex: vmk.toString('hex'), payloads,
  entityAadUtf8: canonical(entityAads[0]), passwordWrapAadUtf8: passwordWrap.aadUtf8, recoveryWrapAadUtf8: recoveryWrap.aadUtf8,
  backup: canonical({ ...backupBody, digest: digest(backupBody) }) };
await mkdir('packages/protocol/test/fixtures', { recursive: true });
await writeFile('packages/protocol/test/fixtures/v1.json', JSON.stringify(result, null, 2) + '\n');
console.log('Wrote public deterministic v1 interoperability vector');
