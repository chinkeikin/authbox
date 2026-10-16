export const fixturePassword = 'AuthBox public feasibility fixture';
export const durableMarker = 'durable-sqlite-v1';
export const fixtureSalt = Uint8Array.from({ length: 16 }, (_, index) => index + 1);
export const serverProfile = Object.freeze({ m: 32768, t: 3, p: 1, dkLen: 32 });
export const vaultProfile = Object.freeze({ m: 65536, t: 3, p: 1, dkLen: 32 });

export const rfcProfile = Object.freeze({
  m: 32,
  t: 3,
  p: 4,
  dkLen: 32,
  key: new Uint8Array(8).fill(3),
  personalization: new Uint8Array(12).fill(4),
});
export const rfcExpected = '0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659';

export function hex(bytes) {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function wasmOptions(profile) {
  return {
    password: fixturePassword,
    salt: fixtureSalt,
    parallelism: profile.p,
    iterations: profile.t,
    memorySize: profile.m,
    hashLength: profile.dkLen,
    outputType: 'hex',
  };
}

export function makeInteropFixture() {
  return {
    key: Uint8Array.from({ length: 32 }, (_, index) => index + 1),
    nonce: Uint8Array.from({ length: 12 }, (_, index) => index + 40),
    aad: new TextEncoder().encode('AuthBox feasibility aad v1'),
    plaintext: new TextEncoder().encode('公开测试数据 Android 与浏览器互通'),
  };
}
