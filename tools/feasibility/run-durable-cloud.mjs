import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';
import { buildStaticWasm } from './build-static-wasm.mjs';
import { domainBindingRequest } from './domain-plan.mjs';
import { runDurableSuite, durableFixtures } from './durable-suite.mjs';
import { durableMarker, serverProfile } from './fixtures.mjs';

const account = process.env.CLOUDFLARE_ACCOUNT_ID ?? '';
const token = process.env.CLOUDFLARE_API_TOKEN ?? '';
const zoneName = process.env.AUTHBOX_CF_TEST_ZONE ?? '';
const hostname = process.env.AUTHBOX_CF_TEST_HOST ?? '';
assert.match(account, /^[a-f0-9]{32}$/i);
assert.ok(token.length > 20 && !token.includes('replace-with'));
assert.ok(/^[a-z0-9.-]+$/.test(zoneName) && zoneName.includes('.'));
assert.ok(/^[a-z0-9.-]+$/.test(hostname) && hostname.endsWith(`.${zoneName}`));
const name = `authbox-feasibility-do-${randomUUID().slice(0, 8)}`;
const ownershipTag = `authbox-probe-${randomUUID()}`;
const probeToken = randomBytes(32).toString('hex');
const path = `/accounts/${account}/workers/scripts/${name}`;
const namespacePath = `/accounts/${account}/workers/durable_objects/namespaces`;
const domainPath = `/accounts/${account}/workers/domains`;
const output = '.cache/feasibility/cloudflare/durable-object-report.json';
await mkdir('.cache/feasibility/cloudflare', { recursive: true });
const report = { generatedAt: new Date().toISOString(), environment: 'Cloudflare deployed Worker and SQLite Durable Object',
  name, ownershipTag, profile: serverProfile, results: [], tail: [], cleanupVerified: false, runtimeChecksPassed: false };
const redact = (value) => [token, probeToken, account, hostname, ...durableFixtures.map((item) => item.password)]
  .reduce((text, secret) => secret ? text.replaceAll(secret, '[redacted]') : text, String(value));
const save = () => writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
let uploaded = false;
let domainAttempted = false;
let zoneId;
let namespaceId;
let socket;
let tailId;
let failure;

async function api(endpoint, { method = 'GET', body, allowedStatuses = [] } = {}) {
  const response = await fetch(`https://api.cloudflare.com/client/v4${endpoint}`, { method, body, redirect: 'error',
    headers: { Authorization: `Bearer ${token}`, 'Cache-Control': 'no-store', ...(typeof body === 'string' ? { 'Content-Type': 'application/json' } : {}) },
    signal: AbortSignal.timeout(30000),
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : { success: response.ok }; }
  catch { throw new Error(`Cloudflare API returned non-JSON HTTP ${response.status}`); }
  if ((!response.ok || data.success === false || data.errors?.length) && !allowedStatuses.includes(response.status)) {
    throw new Error(redact(`Cloudflare ${method} ${endpoint}: HTTP ${response.status} ${JSON.stringify(data.errors?.map(({ code, message }) => ({ code, message })))}`));
  }
  return { status: response.status, data };
}

async function freeAccount() {
  const subscriptions = (await api(`/accounts/${account}/subscriptions`)).data.result;
  const workers = subscriptions.filter((item) => /worker/i.test(JSON.stringify(item.rate_plan)));
  assert.equal(workers.length, 0, 'A paid Workers subscription is present; free-tier probe must stop');
  return { workersSubscriptionCount: workers.length };
}

async function ownedSettings(allowAbsent = false) {
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const current = await api(`${path}/settings`, { allowedStatuses: [404] });
    if (current.status !== 404) {
      assert.ok(current.data.result.tags?.includes(ownershipTag), 'Refusing to modify an unowned Worker');
      return current.data.result;
    }
    if (allowAbsent) return undefined;
    await delay(2000);
  }
  throw new Error('Worker control plane did not become ready');
}

function uploadForm(bundle, { cleanup = false, migrate = false } = {}) {
  const metadata = { main_module: 'probe.mjs', compatibility_date: '2026-10-16', tags: [ownershipTag],
    bindings: cleanup ? [] : [
      { name: 'PROBE_TOKEN', type: 'secret_text', text: probeToken },
      { name: 'PROBE_EXPIRES_AT', type: 'plain_text', text: String(Date.now() + 1800000) },
      { name: 'AUTH_PROBE', type: 'durable_object_namespace', class_name: 'AuthProbe' },
    ],
    ...(migrate ? { migrations: { new_tag: 'authbox-probe-v1', steps: [{ new_sqlite_classes: ['AuthProbe'] }] } } : {}),
    ...(cleanup ? { migrations: { old_tag: 'authbox-probe-v1', new_tag: 'authbox-probe-removed', steps: [{ deleted_classes: ['AuthProbe'] }] } } : {}),
  };
  const form = new FormData();
  form.set('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
  form.set('probe.mjs', new Blob([cleanup ? "export default {fetch(){return new Response('Probe removed',{status:410})}};" : bundle.script], { type: 'application/javascript+module' }), 'probe.mjs');
  for (const item of cleanup ? [] : bundle.modules) form.set(item.name, new Blob([item.contents], { type: 'application/wasm' }), item.name);
  return form;
}

async function attachDomain() {
  const origins = [{ hostname, zone_id: zoneId, enabled: true, previews_enabled: false }];
  const plan = (await api(`${path}/domains/changeset?replace_state=true`, { method: 'POST', body: JSON.stringify(origins) })).data.result;
  const request = domainBindingRequest(plan, hostname, zoneId);
  domainAttempted = true;
  await save();
  await api(`${path}/domains/records`, { method: 'PUT', body: JSON.stringify(request) });
  const binding = (await api(domainPath)).data.result.find((item) => item.hostname === hostname);
  assert.ok(binding?.service === name && binding.zone_id === zoneId);
  report.domain = { bound: true, certificateId: binding.cert_id, removed: false };
  await save();
}

async function startTail() {
  const tail = (await api(`${path}/tails`, { method: 'POST' })).data.result;
  tailId = tail.id;
  const url = new URL(tail.url);
  assert.equal(url.protocol, 'wss:');
  assert.ok(url.hostname.endsWith('.cloudflare.com') || url.hostname === 'tail.developers.workers.dev');
  socket = new WebSocket(tail.url, ['trace-v1']);
  socket.binaryType = 'arraybuffer';
  socket.addEventListener('message', (event) => {
    try {
      const data = JSON.parse(typeof event.data === 'string' ? event.data : Buffer.from(event.data).toString('utf8'));
      const markers = [];
      for (const log of data.logs ?? []) for (const value of log.message ?? []) {
        let candidate;
        try { candidate = typeof value === 'string' ? JSON.parse(value) : value; } catch { continue; }
        if (candidate?.authboxProbe === durableMarker && candidate.kind === 'object' && /^[a-z][a-z0-9-]{0,47}$/.test(candidate.label)
          && ['register', 'login', 'inspect', 'vector', 'reset', 'clear'].includes(candidate.action) && typeof candidate.hashed === 'boolean') {
          markers.push({ label: candidate.label, action: candidate.action, hashed: candidate.hashed });
        }
      }
      if (markers.length === 0) return;
      const row = { markers, cpuTimeMs: data.cpuTime, wallTimeMs: data.wallTime, outcome: data.outcome,
        exceptionNames: data.exceptions?.map((item) => item.name) };
      report.tail.push(row);
      console.log(JSON.stringify({ liveObjectCpu: markers.map((item) => item.label), cpuTimeMs: row.cpuTimeMs, outcome: row.outcome }));
    } catch { report.tailParseErrors = (report.tailParseErrors ?? 0) + 1; }
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Tail connection timed out')), 10000);
    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({ debug: false }), { binary: false, compress: false, mask: false, fin: true });
      clearTimeout(timeout); resolve();
    }, { once: true });
    socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('Tail connection failed')); }, { once: true });
  });
  report.tailConnected = true;
}

try {
  report.accountBefore = await freeAccount();
  assert.ok(!(await api(`/accounts/${account}/workers/scripts`)).data.result.some((item) => item.id === name));
  await api(namespacePath);
  const zone = (await api(`/zones?name=${encodeURIComponent(zoneName)}`)).data.result.find((item) => item.name === zoneName && item.status === 'active' && item.account?.id === account);
  assert.ok(zone, 'Authorized active zone not found');
  zoneId = zone.id;
  assert.ok(!(await api(domainPath)).data.result.some((item) => item.hostname === hostname), 'Refusing to overwrite an existing domain binding');
  console.log('Free account and isolated namespace preflight passed; building the reviewed static WASM probe');
  const bundle = await buildStaticWasm('tools/feasibility/durable-worker.mjs');
  report.manifest = bundle.manifest;
  await save();
  uploaded = true;
  const deployment = (await api(path, { method: 'PUT', body: uploadForm(bundle, { migrate: true }) })).data.result;
  report.deployment = { startupTimeMs: deployment.startup_time_ms, bundleBytes: Buffer.byteLength(bundle.script) };
  const settings = await ownedSettings();
  namespaceId = settings.bindings.find((item) => item.name === 'AUTH_PROBE' && item.type === 'durable_object_namespace')?.namespace_id;
  assert.ok(namespaceId, 'Durable Object namespace binding missing');
  report.namespace = { id: namespaceId, migrationTag: settings.migration_tag };
  const namespace = (await api(namespacePath)).data.result.find((item) => item.id === namespaceId);
  assert.ok(namespace, 'Namespace was not found after SQLite migration');
  report.namespace.registryFields = Object.keys(namespace);
  report.namespace.sqlite = namespace.use_sqlite;
  await save();
  await attachDomain();
  await startTail();
  let consecutive = 0;
  for (let attempt = 0; attempt < 61; attempt += 1) {
    let reached = false;
    try {
      const ready = await fetch(`https://${hostname}/health`, { redirect: 'error', signal: AbortSignal.timeout(10000) });
      reached = ready.status === 200 && ready.headers.get('X-AuthBox-Probe') === durableMarker;
      await ready.body?.cancel();
    } catch {}
    consecutive = reached ? consecutive + 1 : 0;
    if (consecutive >= 6) break;
    assert.ok(attempt < 60, 'Custom domain failed to become consistently ready');
    await delay(2000);
  }
  const suite = await runDurableSuite((url, options) => {
    const target = new URL(url); target.hostname = hostname;
    return fetch(target, { ...options, redirect: 'error', signal: AbortSignal.timeout(30000) });
  }, probeToken, { onSample(sample) { report.results.push(sample); console.log(JSON.stringify(sample)); } });
  report.checks = suite.checks;
  report.runtimeChecksPassed = true;
  const hashLabels = ['register-1', 'register-2', 'register-3', 'sequential-1', 'sequential-2', 'sequential-3', 'wrong-password', 'unknown-user', 'unicode-password',
    'concurrent-1', 'concurrent-2', 'concurrent-3', 'concurrent-4', 'login-after-reset'];
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const correlated = report.tail.filter((item) => item.markers.length === 1 && item.markers[0].hashed && Number.isFinite(item.cpuTimeMs));
    if (hashLabels.every((label) => correlated.some((item) => item.markers[0].label === label))) break;
    if (attempt === 0) console.log('Waiting for per-invocation Durable Object CPU records');
    await delay(2000);
  }
  report.hashCpu = report.tail.filter((item) => item.markers.length === 1 && hashLabels.includes(item.markers[0].label) && item.markers[0].hashed && Number.isFinite(item.cpuTimeMs))
    .map((item) => ({ label: item.markers[0].label, cpuTimeMs: item.cpuTimeMs, outcome: item.outcome }));
  assert.equal(new Set(report.hashCpu.map((item) => item.label)).size, hashLabels.length, 'Not all candidate invocations have uniquely correlated CPU records');
  assert.ok(report.hashCpu.every((item) => item.outcome === 'ok' && item.cpuTimeMs < 30000), 'Durable Object CPU condition failed');
  report.accountAfter = await freeAccount();
  report.freeTierRuntimeVerified = true;
} catch (error) {
  failure = error;
  report.failure = redact(error.message);
} finally {
  socket?.terminate();
  if (tailId) {
    try { await api(`${path}/tails/${tailId}`, { method: 'DELETE', allowedStatuses: [404] }); }
    catch (error) { report.tailCleanupError = redact(error.message); }
  }
  if (uploaded) {
    try {
      const settings = await ownedSettings(true);
      if (settings) {
        namespaceId ??= settings.bindings.find((item) => item.name === 'AUTH_PROBE' && item.type === 'durable_object_namespace')?.namespace_id;
        if (domainAttempted) {
          const binding = (await api(domainPath)).data.result.find((item) => item.hostname === hostname);
          if (binding) {
            assert.ok(binding.service === name && binding.zone_id === zoneId, 'Refusing to remove a domain owned by another service');
            await api(`${domainPath}/${binding.id}`, { method: 'DELETE' });
          }
          assert.ok(!(await api(domainPath)).data.result.some((item) => item.hostname === hostname));
          report.domain ??= {};
          report.domain.removed = true;
          report.domain.certificateCleanup = 'Managed certificate lifecycle is separate; only the binding deletion is verified';
        }
        if (namespaceId) {
          await api(path, { method: 'PUT', body: uploadForm(undefined, { cleanup: true }) });
          report.namespace ??= { id: namespaceId };
          for (let attempt = 0; attempt < 16; attempt += 1) {
            const remaining = (await api(namespacePath)).data.result;
            if (!remaining.some((item) => item.id === namespaceId)) { report.namespace.removed = true; break; }
            await delay(2000);
          }
          assert.equal(report.namespace.removed, true, 'Temporary Durable Object namespace deletion was not confirmed');
        }
        await ownedSettings();
        await api(path, { method: 'DELETE' });
      }
      let absent = 0;
      for (let attempt = 0; attempt < 16; attempt += 1) {
        await delay(2000);
        const current = await api(`${path}/settings`, { allowedStatuses: [404] });
        const scripts = (await api(`/accounts/${account}/workers/scripts`)).data.result;
        absent = current.status === 404 && !scripts.some((item) => item.id === name) ? absent + 1 : 0;
        if (attempt >= 4 && absent >= 3) break;
      }
      assert.ok(absent >= 3, 'Worker deletion not consistently confirmed');
      report.cleanupVerified = true;
    } catch (error) { report.cleanupError = redact(error.message); failure ??= error; }
  }
  await save();
}
console.log(JSON.stringify(report, null, 2));
if (failure) process.exitCode = 1;
