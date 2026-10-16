import { base64url, bytes, canonical, fields, positive, unbase64url, utf8, uuid } from './encoding.mjs';

export const MANAGEMENT_OPERATIONS = Object.freeze(['sync.commit', 'device.approve', 'device.revoke', 'password.change', 'recovery.rotate']);

export function authorize(principal, operation) {
  if (!principal || principal.revoked || !['reader', 'manager', 'backup_reader'].includes(principal.role)) return false;
  if (operation === 'backup.read') return true;
  if (principal.role === 'backup_reader') return false;
  if (['vault.read', 'session.refresh', 'session.logout'].includes(operation)) return true;
  return principal.role === 'manager' && ['device.list', ...MANAGEMENT_OPERATIONS].includes(operation);
}

export function publicDeviceKey(value) {
  fields(value, ['kty', 'crv', 'x', 'y']);
  if (value.kty !== 'EC' || value.crv !== 'P-256') throw new Error('Unsupported device key');
  unbase64url(value.x, 32);
  unbase64url(value.y, 32);
  return structuredClone(value);
}

export function challengePayload(value) {
  fields(value, ['origin', 'id', 'subject', 'deviceId', 'sessionId', 'operation', 'requestHash', 'expiresAt', 'nonce']);
  const origin = new URL(value.origin);
  if (origin.protocol !== 'https:' || origin.origin !== value.origin) throw new Error('Invalid API origin');
  uuid(value.id);
  uuid(value.deviceId);
  if (value.sessionId !== '') uuid(value.sessionId);
  if (typeof value.subject !== 'string' || value.subject.length < 3 || value.subject.length > 64) throw new Error('Invalid challenge subject');
  if (![...MANAGEMENT_OPERATIONS, 'auth.register', 'auth.device-login', 'auth.recover'].includes(value.operation)) throw new Error('Invalid challenge operation');
  unbase64url(value.requestHash, 32);
  unbase64url(value.nonce, 32);
  positive(value.expiresAt);
  return canonical(['AuthBox', 1, 'device-proof', value.origin, value.id, value.subject, value.deviceId,
    value.sessionId, value.operation, value.requestHash, value.expiresAt, value.nonce]);
}

export async function signChallenge(privateKey, payload) {
  return base64url(new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, utf8(payload))));
}

export async function verifyChallenge(publicKey, payload, signature) {
  const key = await crypto.subtle.importKey('jwk', publicDeviceKey(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  return crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, bytes(unbase64url(signature, 64), 64), utf8(payload));
}
