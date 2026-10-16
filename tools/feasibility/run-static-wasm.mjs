import assert from 'node:assert/strict';
import { argon2Sync, randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { argon2id as nobleArgon2id } from '@noble/hashes/argon2.js';
import { argon2id } from 'hash-wasm';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { buildStaticWasm } from './build-static-wasm.mjs';
import { staticWasmCases } from './static-wasm-cases.mjs';
import { hex, rfcExpected } from './fixtures.mjs';

const bundle = await buildStaticWasm();
const token = randomBytes(32).toString('hex');
const mf = new Miniflare(convertV4MiniflareOptions({
  name: 'authbox-static-wasm', ...bundle.localOptions, compatibilityDate: '2026-10-16',
  bindings: { PROBE_TOKEN: token, PROBE_EXPIRES_AT: String(Date.now() + 1800000) },
}));
const samples = [];
async function request(path, authorized = true, method = 'POST') {
  return mf.dispatchFetch(`https://probe.invalid${path}`, { method, headers: authorized ? { Authorization: `Bearer ${token}` } : {} });
}
try {
  assert.equal((await request('/wasm', false)).status, 401);
  assert.equal((await request('/wasm', true, 'GET')).status, 405);
  assert.equal((await request('/unknown')).status, 404);
  const rfc = await request('/rfc');
  assert.equal(rfc.status, 200);
  assert.equal((await rfc.json()).hash, rfcExpected);
  for (const { path, options, profile } of staticWasmCases) {
    const expected = argon2Sync('argon2id', {
      message: options.password, nonce: options.salt, memory: options.memorySize,
      passes: options.iterations, parallelism: options.parallelism, tagLength: options.hashLength, secret: options.secret,
    }).toString('hex');
    assert.equal(await argon2id(options), expected);
    assert.equal(hex(nobleArgon2id(options.password, options.salt, { ...profile, key: options.secret })), expected);
    const start = performance.now();
    const response = await request(path);
    const result = await response.json();
    assert.equal(response.status, 200, JSON.stringify(result));
    assert.equal(result.hash, expected, path);
    samples.push({ path, profile, hashMatchesThreeReferences: true, localRequestWallMs: Math.round((performance.now() - start) * 100) / 100 });
  }
  const concurrent = await Promise.allSettled([request('/wasm'), request('/wasm-salt')]);
  for (const [index, result] of concurrent.entries()) {
    assert.equal(result.status, 'fulfilled');
    assert.equal(result.value.status, 200);
    const options = staticWasmCases[index === 0 ? 0 : 2].options;
    assert.equal((await result.value.json()).hash, await argon2id(options));
  }
  const report = { generatedAt: new Date().toISOString(), environment: 'local workerd; not production CPU validation',
    manifest: bundle.manifest, references: ['Node native argon2Sync', 'unmodified hash-wasm', '@noble/hashes'],
    accessChecksPassed: true, samples, concurrentDifferentSaltsPassed: true,
    rfcScope: 'Full RFC 9106 5.3 verified through noble; hash-wasm does not expose associated data',
  };
  await writeFile('.cache/feasibility/static-wasm/report.json', `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
} finally {
  await mf.dispose();
}
