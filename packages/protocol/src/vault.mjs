import { argon2id } from 'hash-wasm';
import { Secret } from 'otpauth/slim';
import { base64url, bytes, canonical, fields, fromUtf8, positive, randomBytes, sha256, unbase64url, utf8, uuid } from './encoding.mjs';

export const LOGIN_KDF = Object.freeze({ memoryKiB: 32768, iterations: 3, parallelism: 1, hashBytes: 32 });
export const VAULT_KDF = Object.freeze({ name: 'argon2id', version: 19, memoryKiB: 65536, iterations: 3, parallelism: 1, hashBytes: 32 });
export const RECOVERY_INFO = 'AuthBox/v1/vmk-recovery';
export const MAX_PLAINTEXT_BYTES = 65536;
const types = new Set(['account', 'group', 'tag', 'settings', 'manifest']);
const recordFields = ['version', 'vaultId', 'id', 'type', 'revision', 'keyGeneration', 'nonce', 'ciphertext'];

function identity(value) {
  if (value.version !== 1) throw new Error('Unsupported encryption version');
  uuid(value.vaultId);
  positive(value.keyGeneration);
}

export function validateRecord(value) {
  fields(value, recordFields);
  identity(value);
  uuid(value.id);
  positive(value.revision);
  if (!types.has(value.type)) throw new Error('Unsupported record type');
  unbase64url(value.nonce, 12);
  unbase64url(value.ciphertext, 16, MAX_PLAINTEXT_BYTES + 16);
}

export function recordAad(value) {
  return utf8(canonical(['AuthBox', 1, 'entity', value.vaultId, value.id, value.type, value.revision, value.keyGeneration]));
}

export function wrapAad(value) {
  return utf8(canonical(['AuthBox', 1, 'vmk-wrap', value.vaultId, value.keyGeneration, value.purpose, value.kdf]));
}

async function encrypt(rawKey, plaintext, aad) {
  const key = await crypto.subtle.importKey('raw', bytes(rawKey, 32), 'AES-GCM', false, ['encrypt']);
  const nonce = randomBytes(12);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, key, plaintext));
  return { nonce: base64url(nonce), ciphertext: base64url(ciphertext) };
}

async function decrypt(rawKey, value, aad) {
  const key = await crypto.subtle.importKey('raw', bytes(rawKey, 32), 'AES-GCM', false, ['decrypt']);
  try {
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unbase64url(value.nonce, 12),
      additionalData: aad, tagLength: 128 }, key, unbase64url(value.ciphertext, 16, MAX_PLAINTEXT_BYTES + 16)));
  } catch {
    throw new Error('Authentication failed');
  }
}

export async function sealRecord(vmk, metadata, payload) {
  fields(metadata, ['vaultId', 'id', 'type', 'revision', 'keyGeneration']);
  const header = { version: 1, ...metadata };
  const plaintext = utf8(canonical(payload));
  if (!payload || Array.isArray(payload) || typeof payload !== 'object' || plaintext.length > MAX_PLAINTEXT_BYTES) {
    throw new Error('Invalid record payload');
  }
  validateRecord({ ...header, nonce: base64url(new Uint8Array(12)), ciphertext: base64url(new Uint8Array(16)) });
  return { ...header, ...await encrypt(vmk, plaintext, recordAad(header)) };
}

export async function openRecord(vmk, record) {
  const value = structuredClone(record);
  validateRecord(value);
  const decoded = fromUtf8(await decrypt(vmk, value, recordAad(value)));
  const payload = JSON.parse(decoded);
  if (!payload || Array.isArray(payload) || typeof payload !== 'object' || canonical(payload) !== decoded) throw new Error('Invalid record payload');
  return payload;
}

export function validateWrap(value, purpose) {
  fields(value, ['version', 'vaultId', 'keyGeneration', 'purpose', 'kdf', 'nonce', 'ciphertext']);
  identity(value);
  if (value.purpose !== purpose || !['password', 'recovery'].includes(purpose)) throw new Error('Invalid wrap purpose');
  unbase64url(value.nonce, 12);
  unbase64url(value.ciphertext, 48);
  if (purpose === 'password') {
    fields(value.kdf, [...Object.keys(VAULT_KDF), 'salt']);
    for (const [key, expected] of Object.entries(VAULT_KDF)) {
      if (value.kdf[key] !== expected) throw new Error('Unsupported vault KDF profile');
    }
    unbase64url(value.kdf.salt, 16);
  } else {
    fields(value.kdf, ['name', 'salt', 'info']);
    if (value.kdf.name !== 'HKDF-SHA256' || value.kdf.info !== RECOVERY_INFO) throw new Error('Unsupported recovery KDF');
    unbase64url(value.kdf.salt, 32);
  }
}

let pendingKdfs = 0;
let kdfTail = Promise.resolve();

export async function derivePasswordKey(password, salt) {
  if (typeof password !== 'string' || !password.isWellFormed() || utf8(password).length < 8 || utf8(password).length > 1024) {
    throw new Error('Invalid vault password length');
  }
  bytes(salt, 16);
  if (pendingKdfs >= 4) throw new Error('Vault KDF queue full');
  const input = utf8(password);
  const saltCopy = Uint8Array.from(salt);
  pendingKdfs += 1;
  const previous = kdfTail;
  let release;
  kdfTail = new Promise((resolve) => { release = resolve; });
  await previous;
  try {
    return await argon2id({ password: input, salt: saltCopy, memorySize: VAULT_KDF.memoryKiB,
      iterations: VAULT_KDF.iterations, parallelism: VAULT_KDF.parallelism, hashLength: VAULT_KDF.hashBytes, outputType: 'binary' });
  } finally {
    input.fill(0);
    pendingKdfs -= 1;
    release();
  }
}

export async function encodeRecoveryKey(raw) {
  bytes(raw, 32);
  const checksum = (await sha256(raw)).slice(0, 8);
  return `ABR1-${new Secret({ buffer: Uint8Array.from(raw).buffer }).base32}-${checksum}`;
}

export async function decodeRecoveryKey(value) {
  if (typeof value !== 'string' || !/^ABR1-[A-Z2-7]{52}-[A-Za-z0-9_-]{8}$/.test(value)) throw new Error('Invalid recovery key');
  const encoded = value.slice(5, 57);
  const raw = Secret.fromBase32(encoded).bytes;
  bytes(raw, 32);
  if (await encodeRecoveryKey(raw) !== value) throw new Error('Invalid recovery key checksum');
  return raw;
}

export async function deriveRecoveryKey(raw, salt) {
  const key = await crypto.subtle.importKey('raw', bytes(raw, 32), 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: bytes(salt, 32), info: utf8(RECOVERY_INFO) }, key, 256));
}

export async function wrapVmk(vmk, context, purpose, secret) {
  bytes(vmk, 32);
  fields(context, ['vaultId', 'keyGeneration']);
  identity({ version: 1, ...context });
  let kdf;
  let key;
  if (purpose === 'password') {
    kdf = { ...VAULT_KDF, salt: base64url(randomBytes(16)) };
    key = await derivePasswordKey(secret, unbase64url(kdf.salt, 16));
  } else if (purpose === 'recovery') {
    kdf = { name: 'HKDF-SHA256', salt: base64url(randomBytes(32)), info: RECOVERY_INFO };
    const raw = await decodeRecoveryKey(secret);
    try { key = await deriveRecoveryKey(raw, unbase64url(kdf.salt, 32)); }
    finally { raw.fill(0); }
  } else throw new Error('Invalid wrap purpose');
  const header = { version: 1, ...context, purpose, kdf };
  try { return { ...header, ...await encrypt(key, vmk, wrapAad(header)) }; }
  finally { key.fill(0); }
}

export async function unwrapVmk(wrap, secret) {
  const value = structuredClone(wrap);
  validateWrap(value, value.purpose);
  let key;
  if (value.purpose === 'password') key = await derivePasswordKey(secret, unbase64url(value.kdf.salt, 16));
  else {
    const raw = await decodeRecoveryKey(secret);
    try { key = await deriveRecoveryKey(raw, unbase64url(value.kdf.salt, 32)); }
    finally { raw.fill(0); }
  }
  try { return bytes(await decrypt(key, value, wrapAad(value)), 32); }
  finally { key.fill(0); }
}

export async function createVault(password) {
  const context = { vaultId: crypto.randomUUID(), keyGeneration: 1 };
  const vmk = randomBytes(32);
  const rawRecovery = randomBytes(32);
  try {
    const recoveryKey = await encodeRecoveryKey(rawRecovery);
    const passwordWrap = await wrapVmk(vmk, context, 'password', password);
    const recoveryWrap = await wrapVmk(vmk, context, 'recovery', recoveryKey);
    return { ...context, vmk, recoveryKey, wraps: { password: passwordWrap, recovery: recoveryWrap } };
  } catch (error) {
    vmk.fill(0);
    throw error;
  } finally { rawRecovery.fill(0); }
}
