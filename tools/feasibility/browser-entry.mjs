import { argon2id as nobleArgon2id } from '@noble/hashes/argon2.js';
import { argon2id as wasmArgon2id } from 'hash-wasm';
import { hex, makeInteropFixture, rfcProfile, vaultProfile, wasmOptions } from './fixtures.mjs';

async function run(inputCiphertext) {
  if (!window.isSecureContext) throw new Error('Secure context required');
  const { key, nonce, aad, plaintext } = makeInteropFixture();
  const cryptoKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt', 'decrypt']);
  const settings = { name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 };
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(settings, cryptoKey, plaintext));
  const input = Uint8Array.from(inputCiphertext);
  const decoded = new Uint8Array(await crypto.subtle.decrypt(settings, cryptoKey, input));
  const tamperChecks = [];
  for (const item of ['ciphertext', 'tag', 'nonce', 'aad', 'key']) {
    const changedCiphertext = new Uint8Array(input);
    const changedKey = new Uint8Array(key);
    const changedNonce = new Uint8Array(nonce);
    const changedAad = new Uint8Array(aad);
    if (item === 'ciphertext') changedCiphertext[0] ^= 1;
    else if (item === 'tag') changedCiphertext[changedCiphertext.length - 1] ^= 1;
    else if (item === 'nonce') changedNonce[0] ^= 1;
    else if (item === 'aad') changedAad[0] ^= 1;
    else changedKey[0] ^= 1;
    const alternativeKey = await crypto.subtle.importKey('raw', changedKey, 'AES-GCM', false, ['decrypt']);
    let rejected = false;
    try {
      await crypto.subtle.decrypt({ name: 'AES-GCM', iv: changedNonce, additionalData: changedAad }, alternativeKey, changedCiphertext);
    } catch (error) {
      if (error.name !== 'OperationError') throw error;
      rejected = true;
    }
    if (!rejected) throw new Error(`Accepted modified ${item}`);
    tamperChecks.push(item);
  }
  const rfc = nobleArgon2id(new Uint8Array(32).fill(1), new Uint8Array(16).fill(2), rfcProfile);
  const hashes = [];
  const samples = [];
  for (let index = 0; index < 3; index += 1) {
    const start = performance.now();
    hashes.push(await wasmArgon2id(wasmOptions(vaultProfile)));
    samples.push(Math.round((performance.now() - start) * 100) / 100);
  }
  return {
    secureContext: window.isSecureContext,
    ciphertext: hex(ciphertext),
    plaintext: hex(decoded),
    rfc: hex(rfc),
    hashes,
    samples,
    tamperChecks,
    profile: vaultProfile,
  };
}

window.authboxProbe = { run };
