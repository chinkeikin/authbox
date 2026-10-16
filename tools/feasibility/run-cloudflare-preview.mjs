import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { argon2id } from 'hash-wasm';
import { rfcExpected, serverProfile, wasmOptions } from './fixtures.mjs';

const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? '';
const apiToken = process.env.CLOUDFLARE_API_TOKEN ?? '';
assert.ok(/^[a-f0-9]{32}$/i.test(accountId), 'Configure CLOUDFLARE_ACCOUNT_ID');
assert.ok(apiToken.length > 20 && !apiToken.includes('replace-with'));
const outputDirectory = path.resolve('.cache/feasibility/cloudflare/preview');
await mkdir(outputDirectory, { recursive: true });
const configPath = path.join(outputDirectory, 'wrangler.json');
await writeFile(configPath, JSON.stringify({ name: 'authbox-feasibility-preview', compatibility_date: '2026-10-16' }));
process.env.WRANGLER_SEND_METRICS = 'false';
process.env.WRANGLER_LOG = 'none';
process.env.WRANGLER_LOG_PATH = path.join(outputDirectory, 'wrangler-logs');
const { unstable_dev } = await import('wrangler');
const probeToken = randomBytes(32).toString('hex');
const expected = await argon2id(wasmOptions(serverProfile));
const report = {
  generatedAt: new Date().toISOString(),
  environment: 'Cloudflare remote development preview; not the production workers.dev entrypoint',
  profile: serverProfile, results: [], cleanupVerified: false,
  cloudCpuTime: 'Not measured; remote preview cannot establish production Free-tier eligibility',
};
let worker;
let failure;
try {
  worker = await unstable_dev(path.resolve('tools/feasibility/cloud-worker.mjs'), {
    config: configPath, accountId, local: false, ip: '127.0.0.1', port: 0,
    inspectorPort: 0, inspect: false, logLevel: 'none', persist: false,
    vars: { PROBE_TOKEN: probeToken, PROBE_EXPIRES_AT: String(Date.now() + 30 * 60 * 1000) },
    experimental: { disableExperimentalWarning: true, disableDevRegistry: true, showInteractiveDevSession: false, watch: false },
  });
  async function request(route, label, { authorized = true, method = 'POST' } = {}) {
    const start = performance.now();
    const response = await worker.fetch(route, {
      method, headers: authorized ? { Authorization: `Bearer ${probeToken}` } : {},
      signal: AbortSignal.timeout(30000),
    });
    const body = await response.text();
    let data;
    try { data = JSON.parse(body); } catch { data = {}; }
    const sample = { label, status: response.status, requestWallMs: Math.round((performance.now() - start) * 100) / 100 };
    if (data.hash) {
      assert.equal(data.hash, route === '/rfc' ? rfcExpected : expected);
      sample.hashMatches = true;
    }
    if (response.status === 200) assert.equal(sample.hashMatches, true);
    if (data.error) sample.error = String(data.error).replaceAll(probeToken, '[redacted]').replaceAll(apiToken, '[redacted]');
    if (response.status >= 500) sample.resourceError1102 = /1102/.test(body);
    report.results.push(sample);
    console.log(JSON.stringify(sample));
    return sample;
  }
  assert.equal((await request('/noble', 'unauthorized', { authorized: false })).status, 401);
  assert.equal((await request('/noble', 'invalid-method', { method: 'GET' })).status, 405);
  assert.equal((await request('/unknown', 'unknown-route')).status, 404);
  assert.equal((await request('/rfc', 'standard-vector')).status, 200);
  for (let index = 0; index < 3; index += 1) await request('/noble', `sequential-${index + 1}`);
  const concurrent = await Promise.allSettled([request('/noble', 'concurrent-1'), request('/noble', 'concurrent-2')]);
  for (const result of concurrent) if (result.status === 'rejected') throw result.reason;
  await request('/wasm', 'wasm-compatibility');
  const kdfResults = report.results.filter((sample) => /^sequential-|^concurrent-/.test(sample.label));
  report.kdfAttemptCount = kdfResults.length;
  report.kdfSuccessCount = kdfResults.filter((sample) => sample.status === 200 && sample.hashMatches).length;
  report.kdfResourceLimitErrorCount = kdfResults.filter((sample) => sample.resourceError1102).length;
  report.candidateReliableInPreview = report.kdfAttemptCount === 5 && report.kdfSuccessCount === report.kdfAttemptCount;
} catch (error) {
  failure = error;
  report.failure = String(error.message).replaceAll(probeToken, '[redacted]').replaceAll(apiToken, '[redacted]').replaceAll(accountId, '[account]');
} finally {
  if (worker) {
    try {
      await worker.stop();
      const stillListening = await new Promise((resolve) => {
        const connection = createConnection({ host: '127.0.0.1', port: worker.port });
        connection.once('connect', () => { connection.destroy(); resolve(true); });
        connection.once('error', () => resolve(false));
        connection.setTimeout(2000, () => { connection.destroy(); resolve(true); });
      });
      assert.equal(stillListening, false, 'Preview loopback listener was not stopped');
      report.cleanupVerified = true;
    } catch (error) {
      failure ??= error;
      report.cleanupError = String(error.message).replaceAll(probeToken, '[redacted]').replaceAll(apiToken, '[redacted]');
    }
  }
  await writeFile(path.join(outputDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
}
console.log(JSON.stringify(report, null, 2));
if (failure) process.exitCode = 1;
