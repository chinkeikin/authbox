import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';
import { buildApi } from '../bundling/api.mjs';
import { domainBindingRequest } from '../feasibility/domain-plan.mjs';
import { runApiSuite, apiFixturePassword } from '../../services/api/test/suite.mjs';
import { serverProfile } from '../feasibility/fixtures.mjs';
import { sha256, utf8 } from '../../packages/protocol/src/encoding.mjs';
const durableMarker = 'api-v1';
const rotationMode = process.argv.includes('--android-rotation');
const androidMode = process.argv.includes('--android') || rotationMode;
const invites = Array.from({ length: androidMode ? 3 : 2 }, () => randomBytes(32).toString('base64url'));
const inviteHashes = await Promise.all(invites.map((invite) => sha256(utf8(invite))));

const account = process.env.CLOUDFLARE_ACCOUNT_ID ?? '';
const token = process.env.CLOUDFLARE_API_TOKEN ?? '';
const zoneName = process.env.AUTHBOX_CF_TEST_ZONE ?? '';
const hostname = process.env.AUTHBOX_CF_TEST_HOST ?? '';
assert.match(account, /^[a-f0-9]{32}$/i);
assert.ok(token.length > 20 && !token.includes('replace-with'));
assert.ok(/^[a-z0-9.-]+$/.test(zoneName) && zoneName.includes('.'));
assert.ok(/^[a-z0-9.-]+$/.test(hostname) && hostname.endsWith(`.${zoneName}`));
const output = rotationMode ? '.cache/api/cloud/android-rotation-report.json' : androidMode ? '.cache/api/cloud/android-service-report.json' : '.cache/api/cloud/service-report.json';
const cleanupOnly = process.argv.includes('--cleanup');
const previous = cleanupOnly ? JSON.parse(await readFile(output, 'utf8')) : undefined;
const name = previous?.name ?? `authbox-api-check-${randomUUID().slice(0, 8)}`;
const ownershipTag = previous?.ownershipTag ?? `authbox-probe-${randomUUID()}`;
assert.match(name, /^authbox-api-check-[a-f0-9]{8}$/);
assert.match(ownershipTag, /^authbox-probe-[a-f0-9-]{36}$/);
const probeToken = randomBytes(32).toString('hex');
const path = `/accounts/${account}/workers/scripts/${name}`;
const namespacePath = `/accounts/${account}/workers/durable_objects/namespaces`;
const domainPath = `/accounts/${account}/workers/domains`;
await mkdir('.cache/api/cloud', { recursive: true });
const report = previous ?? { generatedAt: new Date().toISOString(), environment: 'Cloudflare deployed Worker and SQLite Durable Object',
  name, ownershipTag, profile: serverProfile, results: [], tail: [], cleanupVerified: false, runtimeChecksPassed: false };
const redact = (value) => [token, probeToken, account, hostname, apiFixturePassword, ...invites]
  .reduce((text, secret) => secret ? text.replaceAll(secret, '[redacted]') : text, String(value));
const save = () => writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
let uploaded = cleanupOnly;
let domainAttempted = cleanupOnly && Boolean((previous.domainAttempted || previous.domain?.bound) && !previous.domain?.removed);
let zoneId;
let namespaceId;
let socket;
let tailId;
let failure;
let stoppingTail = false;

async function api(endpoint, { method = 'GET', body, allowedStatuses = [] } = {}) {
  let response;
  for (let attempt = 0; attempt < 5; attempt += 1) {
    response = await fetch(`https://api.cloudflare.com/client/v4${endpoint}`, { method, body, redirect: 'error',
      headers: { Authorization: `Bearer ${token}`, 'Cache-Control': 'no-store', ...(typeof body === 'string' ? { 'Content-Type': 'application/json' } : {}) },
      signal: AbortSignal.timeout(30000),
    });
    if (method !== 'GET' || (response.status !== 429 && response.status < 500) || attempt === 4) break;
    await response.body?.cancel();
    await delay(Math.min(5000, 1000 * 2 ** attempt));
  }
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
  let absent = 0;
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const current = await api(`${path}/settings`, { allowedStatuses: [404] });
    if (current.status !== 404) {
      assert.ok(current.data.result.tags?.includes(ownershipTag), 'Refusing to modify an unowned Worker');
      return current.data.result;
    }
    if (allowAbsent) {
      const scripts = (await api(`/accounts/${account}/workers/scripts`)).data.result;
      absent = scripts.some((item) => item.id === name) ? 0 : absent + 1;
      if (absent >= 3) return undefined;
    }
    await delay(2000);
  }
  throw new Error('Worker control plane did not become ready');
}

function uploadForm(bundle, { cleanup = false, migrate = false } = {}) {
  const metadata = { main_module: 'probe.mjs', compatibility_date: '2026-10-16', tags: [ownershipTag],
    bindings: cleanup ? [] : [
      { name: 'FIXTURE_TOKEN', type: 'secret_text', text: probeToken },
      { name: 'API_ORIGIN', type: 'plain_text', text: `https://${hostname}` },
      { name: 'REGISTRATION_INVITE_HASHES', type: 'secret_text', text: JSON.stringify(inviteHashes) },
      { name: 'CLIENT_ORIGINS', type: 'plain_text', text: '["https://client.example.invalid"]' },
      { name: 'FIXTURE_EXPIRES_AT', type: 'plain_text', text: String(Date.now() + 1800000) },
      { name: 'INSTALLATION', type: 'durable_object_namespace', class_name: 'FixtureStore' },
    ],
    ...(migrate ? { migrations: { new_tag: 'authbox-probe-v1', steps: [{ new_sqlite_classes: ['FixtureStore'] }] } } : {}),
    ...(cleanup ? { migrations: { old_tag: 'authbox-probe-v1', new_tag: 'authbox-probe-removed', steps: [{ deleted_classes: ['FixtureStore'] }] } } : {}),
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
  report.domainAttempted = true;
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
  socket.addEventListener('close', (event) => {
    if (!stoppingTail) report.tailDisconnected = { code: event.code };
  });
  socket.addEventListener('message', (event) => {
    try {
      const data = JSON.parse(typeof event.data === 'string' ? event.data : Buffer.from(event.data).toString('utf8'));
      const markers = [];
      for (const log of data.logs ?? []) for (const value of log.message ?? []) {
        let candidate;
        try { candidate = typeof value === 'string' ? JSON.parse(value) : value; } catch { continue; }
        if (candidate?.authboxServiceFixture === 1 && /^[a-z0-9-]{1,64}$/.test(candidate.label)) {
          markers.push({ label: candidate.label });
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

async function verify() {
  report.accountBefore = await freeAccount();
  assert.ok(!(await api(`/accounts/${account}/workers/scripts`)).data.result.some((item) => item.id === name));
  await api(namespacePath);
  const zone = (await api(`/zones?name=${encodeURIComponent(zoneName)}`)).data.result.find((item) => item.name === zoneName && item.status === 'active' && item.account?.id === account);
  assert.ok(zone, 'Authorized active zone not found');
  zoneId = zone.id;
  assert.ok(!(await api(domainPath)).data.result.some((item) => item.hostname === hostname), 'Refusing to overwrite an existing domain binding');
  console.log('Free account and isolated namespace preflight passed; building the reviewed static WASM probe');
  const bundle = await buildApi({ fixture: true });
  report.manifest = bundle.manifest;
  await save();
  uploaded = true;
  const deployment = (await api(path, { method: 'PUT', body: uploadForm(bundle, { migrate: true }) })).data.result;
  report.deployment = { startupTimeMs: deployment.startup_time_ms, bundleBytes: Buffer.byteLength(bundle.script) };
  const settings = await ownedSettings();
  namespaceId = settings.bindings.find((item) => item.name === 'INSTALLATION' && item.type === 'durable_object_namespace')?.namespace_id;
  assert.ok(namespaceId, 'Durable Object namespace binding missing');
  report.namespace = { id: namespaceId, migrationTag: settings.migration_tag };
  const namespace = (await api(namespacePath)).data.result.find((item) => item.id === namespaceId);
  assert.ok(namespace, 'Namespace was not found after SQLite migration');
  report.namespace.registryFields = Object.keys(namespace);
  report.namespace.sqlite = namespace.use_sqlite;
  assert.equal(report.namespace.sqlite, true, 'Namespace must use the free SQLite backend');
  await save();
  await attachDomain();
  await startTail();
  let consecutive = 0;
  const readinessDeadline = Date.now() + 120000;
  for (let attempt = 0; attempt < 61; attempt += 1) {
    assert.ok(Date.now() < readinessDeadline, 'Custom domain readiness exceeded 120 seconds');
    let reached = false;
    let status;
    try {
      const ready = await fetch(`https://${hostname}/health`, { headers: { 'X-AuthBox-Fixture': probeToken }, redirect: 'error', signal: AbortSignal.timeout(Math.min(10000, readinessDeadline - Date.now())) });
      status = ready.status;
      reached = ready.status === 200 && ready.headers.get('X-AuthBox-Service-Fixture') === durableMarker;
      await ready.body?.cancel();
    } catch (error) { report.lastReadinessNetworkError = error.cause?.code ?? error.name; }
    consecutive = reached ? consecutive + 1 : 0;
    if (consecutive >= 6) break;
    console.log(JSON.stringify({ readinessAttempt: attempt + 1, status, reachedProbe: reached }));
    assert.ok(attempt < 60, 'Custom domain failed to become consistently ready');
    await delay(2000);
  }
  if (androidMode) {
    const { runAndroidCloud } = await import('../android/run-cloud.mjs');
    const result = await runAndroidCloud({ origin: `https://${hostname}`, fixtureToken: probeToken, invite: invites[0], managementInvite: invites[1], registrationInvite: invites[2], rotationOnly: rotationMode });
    report.android = result;
    report.checks = result.checks;
    report.runtimeChecksPassed = true;
    report.accountAfter = await freeAccount();
    report.freeTierRuntimeVerified = true;
    return;
  }
  const hashLabels = ['register-success', 'reader-login', 'candidate-login', 'device-login-manager', 'wrong-password', 'unknown-user', 'other-register', 'password-prepare', 'recover-rollback', 'reconnect-after-idle'];
  const suite = await runApiSuite((url, options) => {
    const headers = new Headers(options.headers);
    headers.delete('CF-Connecting-IP');
    return fetch(url, { ...options, headers, redirect: 'error', signal: AbortSignal.timeout(30000) });
  }, {
    origin: 'https://' + hostname, fixtureToken: probeToken, invites,
    onCase(sample) { report.results.push(sample); console.log(JSON.stringify(sample)); },
  });
  report.checks = suite.checks;
  report.runtimeChecksPassed = true;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const seen = new Set(report.tail.flatMap((item) => item.markers.map((marker) => marker.label)));
    if (hashLabels.every((label) => seen.has(label)) || socket.readyState === WebSocket.CLOSED) break;
    await delay(2000);
  }
  report.hashCpu = report.tail.filter((item) => item.markers.length === 1 && hashLabels.includes(item.markers[0].label) && Number.isFinite(item.cpuTimeMs))
    .map((item) => ({ label: item.markers[0].label, cpuTimeMs: item.cpuTimeMs, outcome: item.outcome }));
  const observedLabels = new Set(report.hashCpu.map((item) => item.label));
  report.cpuCoverage = { expectedCount: hashLabels.length, observedCount: observedLabels.size,
    complete: observedLabels.size === hashLabels.length, missingLabels: hashLabels.filter((label) => !observedLabels.has(label)) };
  assert.ok(observedLabels.size >= 3, 'Insufficient real service KDF CPU samples');
  assert.ok(report.hashCpu.some((item) => ['reader-login', 'candidate-login', 'device-login-manager', 'reconnect-after-idle'].includes(item.label)), 'Missing successful password verification CPU sample');
  assert.ok(report.hashCpu.every((item) => item.outcome === 'ok' && item.cpuTimeMs >= 0 && item.cpuTimeMs < 30000), 'Service KDF CPU condition failed');
  report.accountAfter = await freeAccount();
  report.freeTierRuntimeVerified = true;
}

try {
  if (cleanupOnly) {
    const zone = (await api(`/zones?name=${encodeURIComponent(zoneName)}`)).data.result.find((item) => item.name === zoneName && item.account?.id === account);
    assert.ok(zone, 'Cleanup zone ownership not confirmed');
    zoneId = zone.id;
    report.cleanupRetriedAt = new Date().toISOString();
  } else await verify();
} catch (error) {
  failure = error;
  report.failure = redact(error.message);
} finally {
  stoppingTail = true;
  socket?.terminate();
  if (tailId) {
    try { await api(`${path}/tails/${tailId}`, { method: 'DELETE', allowedStatuses: [404] }); }
    catch (error) { report.tailCleanupError = redact(error.message); }
  }
  if (uploaded) {
    try {
      const settings = await ownedSettings(true);
      if (settings) {
        const boundNamespace = settings.bindings.find((item) => item.name === 'INSTALLATION' && item.type === 'durable_object_namespace')?.namespace_id;
        if (namespaceId && boundNamespace) assert.equal(namespaceId, boundNamespace, 'Namespace binding changed before cleanup');
        namespaceId ??= boundNamespace;
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
      delete report.cleanupError;
    } catch (error) { report.cleanupError = redact(error.message); failure ??= error; }
  }
  await save();
}
if (cleanupOnly && !failure && report.cleanupVerified && report.runtimeChecksPassed) {
  try {
    report.accountAfter = await freeAccount();
    report.freeTierRuntimeVerified = true;
    if (report.failure) { report.recoveredFailure = report.failure; delete report.failure; }
    report.recoveredAt = new Date().toISOString();
  } catch (error) {
    failure = error;
    report.failure = redact(error.message);
  }
  await save();
}
console.log(JSON.stringify(report, null, 2));
if (failure) process.exitCode = 1;
