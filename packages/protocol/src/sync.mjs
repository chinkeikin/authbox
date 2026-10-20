import { inspectBackup } from './backup.mjs';
import { canonical, fields, utf8, uuid } from './encoding.mjs';

export const MAX_ONLINE_BACKUP_BYTES = 1024 * 1024;

export async function validateCommit(value) {
  fields(value, ['operationId', 'baseRevision', 'restoredIds', 'backup']);
  uuid(value.operationId);
  if (!Number.isSafeInteger(value.baseRevision) || value.baseRevision < 0 || value.baseRevision >= Number.MAX_SAFE_INTEGER) throw new Error('Invalid base revision');
  if (!Array.isArray(value.restoredIds) || value.restoredIds.length > 10000 || new Set(value.restoredIds).size !== value.restoredIds.length) throw new Error('Invalid restore list');
  for (const id of value.restoredIds) uuid(id);
  if (typeof value.backup !== 'string' || value.backup.length > MAX_ONLINE_BACKUP_BYTES || utf8(value.backup).length > MAX_ONLINE_BACKUP_BYTES) throw new Error('Online snapshot too large');
  const backup = await inspectBackup(value.backup);
  if (backup.revision !== value.baseRevision + 1) throw new Error('Invalid target revision');
  return backup;
}

export function validateTransition(previous, next, retired = new Map(), restoredIds = [], rotation = false) {
  if (!previous) {
    if (next.revision !== 1 || next.keyGeneration !== 1 || restoredIds.length) throw new Error('Invalid initial snapshot');
  } else if (next.vaultId !== previous.vaultId || next.revision !== previous.revision + 1
    || next.keyGeneration !== previous.keyGeneration + (rotation ? 1 : 0)) {
    throw new Error('Invalid snapshot transition');
  }
  const old = new Map((previous?.entities ?? []).map((record) => [record.id, record]));
  const incoming = new Map(next.entities.map((record) => [record.id, record]));
  if (rotation) {
    if (!previous || restoredIds.length || old.size !== incoming.size || [...old.keys()].some((id) => !incoming.has(id))) throw new Error('Rotation must preserve all entity IDs');
    for (const purpose of ['password', 'recovery']) {
      if (next.wraps[purpose].nonce === previous.wraps[purpose].nonce || next.wraps[purpose].ciphertext === previous.wraps[purpose].ciphertext
        || next.wraps[purpose].kdf.salt === previous.wraps[purpose].kdf.salt) throw new Error('Rotation requires fresh wraps');
    }
    const nonces = new Set([...previous.entities, previous.manifest].map((record) => record.nonce));
    if ([...next.entities, next.manifest].some((record) => nonces.has(record.nonce))) throw new Error('Rotation requires fresh nonces');
  }
  const restores = new Set(restoredIds);
  for (const id of restores) if (!retired.has(id) || !incoming.has(id) || old.has(id)) throw new Error('Invalid explicit restore');
  for (const record of next.entities) {
    const before = old.get(record.id);
    if (retired.has(record.id) && !restores.has(record.id)) throw new Error('Retired entity requires explicit restore');
    if ((before && before.type !== record.type) || (retired.has(record.id) && retired.get(record.id) !== record.type)) throw new Error('Entity type cannot change');
    if (!before || canonical(before) !== canonical(record)) {
      if (record.revision !== next.revision) throw new Error('Changed entity must use target revision');
    }
  }
  return { removed: [...old.keys()].filter((id) => !incoming.has(id)), restored: [...restores] };
}
