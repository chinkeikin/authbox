import { canonical, fields, positive, sha256, unbase64url, utf8, uuid } from './encoding.mjs';
import { openRecord, sealRecord, unwrapVmk, validateRecord, validateWrap } from './vault.mjs';

export const MAX_BACKUP_BYTES = 16 * 1024 * 1024;
export const MAX_BACKUP_ENTITIES = 10000;
const payloadFields = ['magic', 'version', 'vaultId', 'revision', 'keyGeneration', 'createdAt', 'appVersion', 'wraps', 'entities'];
const manifestContext = (value) => ({ vaultId: value.vaultId, id: value.vaultId, type: 'manifest', revision: value.revision, keyGeneration: value.keyGeneration });
const digest = (value) => sha256(utf8(canonical(value)));

function validatePayload(value) {
  fields(value, payloadFields);
  if (value.magic !== 'AUTHBOX-BACKUP' || value.version !== 1) throw new Error('Unsupported backup format');
  uuid(value.vaultId);
  positive(value.revision);
  positive(value.keyGeneration);
  if (typeof value.createdAt !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value.createdAt)
    || !Number.isFinite(Date.parse(value.createdAt)) || new Date(value.createdAt).toISOString() !== value.createdAt) throw new Error('Invalid snapshot time');
  if (typeof value.appVersion !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?$/.test(value.appVersion)
    || value.appVersion.length > 64) throw new Error('Invalid app version');
  fields(value.wraps, ['password', 'recovery']);
  for (const purpose of ['password', 'recovery']) {
    const wrap = value.wraps[purpose];
    validateWrap(wrap, purpose);
    if (wrap.vaultId !== value.vaultId || wrap.keyGeneration !== value.keyGeneration) throw new Error('Mismatched vault wrap');
  }
  if (!Array.isArray(value.entities) || value.entities.length > MAX_BACKUP_ENTITIES) throw new Error('Backup entity limit exceeded');
  let previous = '';
  const nonces = new Set();
  for (const record of value.entities) {
    validateRecord(record);
    if (record.type === 'manifest' || record.vaultId !== value.vaultId || record.keyGeneration !== value.keyGeneration
      || record.revision > value.revision || record.id <= previous || nonces.has(record.nonce)) throw new Error('Invalid backup entity manifest');
    previous = record.id;
    nonces.add(record.nonce);
  }
}

function split(value) {
  const { manifest, digest: checksum, ...payload } = value;
  return { manifest, checksum, payload };
}

function validateBackup(value) {
  fields(value, [...payloadFields, 'manifest', 'digest']);
  const { manifest, payload } = split(value);
  validatePayload(payload);
  validateRecord(manifest);
  for (const [key, expected] of Object.entries(manifestContext(payload))) {
    if (manifest[key] !== expected) throw new Error('Invalid authenticated manifest');
  }
  if (payload.entities.some((record) => record.nonce === manifest.nonce)) throw new Error('Duplicate backup nonce');
  unbase64url(value.digest, 32);
}

export async function createBackup(vmk, input) {
  const payload = structuredClone(input);
  validatePayload(payload);
  const manifest = await sealRecord(vmk, manifestContext(payload), { digest: await digest(payload), entityCount: payload.entities.length });
  const body = { ...payload, manifest };
  const result = { ...body, digest: await digest(body) };
  validateBackup(result);
  const encoded = canonical(result);
  if (utf8(encoded).length > MAX_BACKUP_BYTES) throw new Error('Backup byte limit exceeded');
  return encoded;
}

export async function inspectBackup(encoded) {
  if (typeof encoded !== 'string' || encoded.length > MAX_BACKUP_BYTES || utf8(encoded).length > MAX_BACKUP_BYTES) throw new Error('Backup byte limit exceeded');
  const value = JSON.parse(encoded);
  validateBackup(value);
  if (canonical(value) !== encoded) throw new Error('Backup must use canonical JSON');
  const { digest: expected, ...body } = value;
  if (await digest(body) !== expected) throw new Error('Backup checksum mismatch');
  return value;
}

export async function restoreBackup(encoded, { purpose, secret }) {
  if (!['password', 'recovery'].includes(purpose)) throw new Error('Invalid restore method');
  const backup = await inspectBackup(encoded);
  const vmk = await unwrapVmk(backup.wraps[purpose], secret);
  try { return await authenticateBackup(encoded, vmk); }
  finally { vmk.fill(0); }
}

export async function authenticateBackup(encoded, rawKey) {
  const backup = await inspectBackup(encoded);
  if (!(rawKey instanceof Uint8Array) || rawKey.length !== 32) throw new Error('Invalid VMK');
  const vmk = Uint8Array.from(rawKey);
  try {
    const { payload } = split(backup);
    const manifest = await openRecord(vmk, backup.manifest);
    fields(manifest, ['digest', 'entityCount']);
    if (manifest.digest !== await digest(payload) || manifest.entityCount !== backup.entities.length) throw new Error('Backup manifest authentication failed');
    const entities = [];
    for (const record of backup.entities) entities.push({ metadata: { id: record.id, type: record.type, revision: record.revision }, payload: await openRecord(vmk, record) });
    return { vaultId: backup.vaultId, revision: backup.revision, keyGeneration: backup.keyGeneration, vmk, wraps: backup.wraps, entities };
  } catch (error) {
    vmk.fill(0);
    throw error;
  }
}
