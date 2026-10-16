import { base64url, randomBytes, sha256, unbase64url, utf8 } from './encoding.mjs';

export async function accountRecoveryHash(code) {
  if (typeof code !== 'string' || !code.startsWith('ABAC1-')) throw new Error('Invalid account recovery code');
  unbase64url(code.slice(6), 16);
  return sha256(utf8(`AuthBox/account-recovery/v1/${code}`));
}

export function validateRecoveryHashes(value) {
  if (!Array.isArray(value) || value.length < 5 || value.length > 16 || new Set(value).size !== value.length) throw new Error('Invalid recovery hash list');
  for (const hash of value) unbase64url(hash, 32);
}

export async function createAccountRecoveryCodes() {
  const codes = Array.from({ length: 8 }, () => `ABAC1-${base64url(randomBytes(16))}`);
  const hashes = await Promise.all(codes.map(accountRecoveryHash));
  return { codes, hashes };
}
