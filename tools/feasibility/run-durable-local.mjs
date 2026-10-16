import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { buildStaticWasm } from './build-static-wasm.mjs';
import { runDurableSuite } from './durable-suite.mjs';

const bundle = await buildStaticWasm('tools/feasibility/durable-worker.mjs');
const token = randomBytes(32).toString('hex');
const mf = new Miniflare(convertV4MiniflareOptions({
  name: 'authbox-durable-local', ...bundle.localOptions, compatibilityDate: '2026-10-16',
  durableObjects: { AUTH_PROBE: { className: 'AuthProbe', useSQLite: true } },
  durableObjectsPersist: resolve(`.cache/feasibility/durable-local/${randomUUID()}`),
  bindings: { PROBE_TOKEN: token, PROBE_EXPIRES_AT: String(Date.now() + 1800000) },
}));
try {
  const report = await runDurableSuite((url, options) => mf.dispatchFetch(url, options), token);
  report.environment = 'Local workerd with SQLite Durable Object; not production CPU qualification';
  report.generatedAt = new Date().toISOString();
  report.manifest = bundle.manifest;
  const expired = new Miniflare(convertV4MiniflareOptions({ name: 'authbox-durable-expired', ...bundle.localOptions,
    compatibilityDate: '2026-10-16', durableObjects: { AUTH_PROBE: { className: 'AuthProbe', useSQLite: true } },
    bindings: { PROBE_TOKEN: token, PROBE_EXPIRES_AT: '1' },
  }));
  try {
    assert.equal((await expired.dispatchFetch('https://probe.invalid/health')).status, 503);
    assert.equal((await expired.dispatchFetch('https://probe.invalid/do/expired/login', { method: 'POST', headers: { Authorization: `Bearer ${token}` } })).status, 503);
  } finally { await expired.dispose(); }
  report.expiryGatePassed = true;
  await writeFile('.cache/feasibility/static-wasm/durable-local-report.json', `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
} finally { await mf.dispose(); }
