import { argon2id, argon2Verify } from 'hash-wasm';
import { LOGIN_KDF } from '../../../packages/protocol/src/vault.mjs';
import { randomBytes } from '../../../packages/protocol/src/encoding.mjs';

export async function hashPassword(password) {
  return argon2id({ password, salt: randomBytes(16), memorySize: LOGIN_KDF.memoryKiB, iterations: LOGIN_KDF.iterations,
    parallelism: LOGIN_KDF.parallelism, hashLength: LOGIN_KDF.hashBytes, outputType: 'encoded' });
}

export async function checkPassword(password, hash) {
  if (!hash) { await hashPassword(password); return false; }
  if (!/^\$argon2id\$v=19\$m=32768,t=3,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$/.test(hash)) throw new Error('Unsupported stored password profile');
  return argon2Verify({ password, hash });
}
