import { serverProfile, vaultProfile, wasmOptions } from './fixtures.mjs';

export const staticWasmCases = [
  { path: '/wasm', profile: serverProfile, options: wasmOptions(serverProfile) },
  { path: '/wasm-unicode', profile: serverProfile, options: { ...wasmOptions(serverProfile), password: '公开密码🔐e\u0301' } },
  { path: '/wasm-salt', profile: serverProfile, options: { ...wasmOptions(serverProfile), salt: new Uint8Array(16).fill(42) } },
  { path: '/wasm-vault', profile: vaultProfile, options: wasmOptions(vaultProfile) },
  { path: '/wasm-secret', profile: { ...serverProfile, p: 2 }, options: { ...wasmOptions(serverProfile), parallelism: 2, secret: new Uint8Array(8).fill(3) } },
];
