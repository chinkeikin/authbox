import { canonical, fields, positive, utf8, uuid } from './encoding.mjs';
import { normalizeSecret, validateTotp } from './totp.mjs';

function text(value, maximum, required = false) {
  if (typeof value !== 'string' || !value.isWellFormed() || utf8(value).length > maximum
    || /[\u0000-\u001f\u007f]/.test(value) || (required && !value.trim())) throw new Error('Invalid business text');
}

export function timestamp(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value)
    || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString() !== value) throw new Error('Invalid timestamp');
}

export function username(value) {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_]{2,31}$/.test(value)) throw new Error('Invalid username');
}

export function loginPassword(value) {
  if (typeof value !== 'string' || !value.isWellFormed() || utf8(value).length < 8 || utf8(value).length > 256) throw new Error('Invalid login password');
}

export function validateBusinessEntity(metadata, payload) {
  fields(metadata, ['id', 'type', 'revision']);
  uuid(metadata.id);
  positive(metadata.revision);
  if (!payload || payload.schemaVersion !== 1) throw new Error('Unsupported business schema');
  if (utf8(canonical(payload)).length > 65536) throw new Error('Business entity too large');
  if (metadata.type === 'account') {
    fields(payload, ['schemaVersion', 'issuer', 'name', 'account', 'secret', 'algorithm', 'digits', 'period', 'groupId', 'tagIds', 'favorite', 'order', 'notes', 'deletedAt']);
    for (const key of ['issuer', 'name', 'account']) text(payload[key], 256);
    if (!payload.name.trim() && !payload.issuer.trim() && !payload.account.trim()) throw new Error('Empty account identity');
    text(payload.notes, 4096);
    validateTotp(payload);
    if (normalizeSecret(payload.secret) !== payload.secret) throw new Error('Noncanonical TOTP secret');
    if (payload.groupId !== null) uuid(payload.groupId);
    if (!Array.isArray(payload.tagIds) || payload.tagIds.length > 64) throw new Error('Too many tags');
    for (const id of payload.tagIds) uuid(id);
    if (new Set(payload.tagIds).size !== payload.tagIds.length) throw new Error('Duplicate tags');
    if (typeof payload.favorite !== 'boolean') throw new Error('Invalid favorite');
    if (payload.deletedAt !== null) timestamp(payload.deletedAt);
  } else if (metadata.type === 'group') {
    fields(payload, ['schemaVersion', 'name', 'order']);
    text(payload.name, 256, true);
  } else if (metadata.type === 'tag') {
    fields(payload, ['schemaVersion', 'name']);
    text(payload.name, 256, true);
  } else if (metadata.type === 'settings') {
    fields(payload, ['schemaVersion', 'defaultView']);
    if (!['all', 'favorites'].includes(payload.defaultView)) throw new Error('Invalid default view');
  } else throw new Error('Unsupported business entity type');
  if (['account', 'group'].includes(metadata.type) && (!Number.isSafeInteger(payload.order) || payload.order < 0)) throw new Error('Invalid entity order');
}

export function validateVaultEntities(entities) {
  if (!Array.isArray(entities) || entities.length > 10000) throw new Error('Invalid entity list');
  const indexed = new Map();
  let settingsCount = 0;
  for (const entity of entities) {
    fields(entity, ['metadata', 'payload']);
    validateBusinessEntity(entity.metadata, entity.payload);
    if (indexed.has(entity.metadata.id)) throw new Error('Duplicate entity identity');
    indexed.set(entity.metadata.id, entity);
    if (entity.metadata.type === 'settings') settingsCount += 1;
  }
  if (settingsCount > 1) throw new Error('Multiple settings entities');
  for (const { metadata, payload } of entities) {
    if (metadata.type !== 'account') continue;
    if (payload.groupId !== null && indexed.get(payload.groupId)?.metadata.type !== 'group') throw new Error('Missing group');
    for (const id of payload.tagIds) if (indexed.get(id)?.metadata.type !== 'tag') throw new Error('Missing tag');
  }
}
