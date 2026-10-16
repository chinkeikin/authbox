import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { argon2id } from 'hash-wasm';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import WebSocket from 'ws';
import { rfcExpected, serverProfile, wasmOptions } from './fixtures.mjs';
import cloudWorker from './cloud-worker.mjs';
import minimalWorker from './cloud-minimal-worker.mjs';
import { domainBindingRequest } from './domain-plan.mjs';
import { buildStaticWasm } from './build-static-wasm.mjs';

const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? '';
const apiToken = process.env.CLOUDFLARE_API_TOKEN ?? '';
assert.ok(/^[a-f0-9]{32}$/i.test(accountId), 'Configure CLOUDFLARE_ACCOUNT_ID');
assert.ok(apiToken.length > 20 && !apiToken.includes('replace-with'), 'Configure CLOUDFLARE_API_TOKEN');
const routeOnly = process.argv.includes('--route-only');
const customDomain = process.argv.includes('--custom-domain');
const staticWasm = process.argv.includes('--static-wasm');
assert.ok(!staticWasm || !routeOnly, 'Static WASM cannot be combined with route-only mode');
const testZone = process.env.AUTHBOX_CF_TEST_ZONE ?? '';
const testHostname = process.env.AUTHBOX_CF_TEST_HOST ?? '';
if (customDomain) {
  assert.ok(/^[a-z0-9.-]+$/.test(testZone) && testZone.includes('.'), 'Configure AUTHBOX_CF_TEST_ZONE');
  assert.ok(/^[a-z0-9.-]+$/.test(testHostname) && testHostname.endsWith(`.${testZone}`), 'Configure a test subdomain in AUTHBOX_CF_TEST_HOST');
}
const name = routeOnly ? `authbox-feasibility-route-${randomUUID().slice(0, 8)}` : 'authbox-feasibility';
const marker = routeOnly ? 'minimal-v1' : 'synthetic-v1';
const scriptPath = `/accounts/${accountId}/workers/scripts/${name}`;
const ownershipTag = `authbox-probe-${randomUUID()}`;
const probeToken = randomBytes(32).toString('hex');
const outputDirectory = '.cache/feasibility/cloudflare';
await mkdir(outputDirectory, { recursive: true });

function redact(value) {
  return String(value).replaceAll(apiToken, '[redacted]').replaceAll(probeToken, '[redacted]').replaceAll(accountId, '[account]');
}

async function api(endpoint, { method = 'GET', body, allowedStatuses = [] } = {}) {
  const response = await fetch(`https://api.cloudflare.com/client/v4${endpoint}`, {
    method, body,
    headers: { Authorization: `Bearer ${apiToken}`, 'Cache-Control': 'no-store', ...(typeof body === 'string' ? { 'Content-Type': 'application/json' } : {}) },
    redirect: 'error', signal: AbortSignal.timeout(30000),
  });
  const responseBody = await response.text();
  const data = responseBody ? JSON.parse(responseBody) : { success: response.ok };
  if ((!response.ok || data.success === false || data.errors?.length) && !allowedStatuses.includes(response.status)) {
    const errors = data.errors?.map(({ code, message }) => ({ code, message: redact(message) }));
    throw new Error(`Cloudflare ${method} ${redact(endpoint)}: HTTP ${response.status} ${JSON.stringify(errors)}`);
  }
  return { status: response.status, data };
}

const report = {
  generatedAt: new Date().toISOString(), environment: 'Cloudflare deployed Worker', name, ownershipTag,
  mode: routeOnly ? 'public-constant-route-only' : staticWasm ? 'static-wasm-argon2id-fixtures' : 'fixed-argon2id-fixtures',
  profile: routeOnly ? undefined : serverProfile, results: [], tail: [], cleanupVerified: false,
};
let attemptedUpload = false;
let tailId;
let socket;
let failure;
let zoneId;
let domainBindingAttempted = false;
const requestLabels = new Map();
const isCandidate = (label) => /^sequential-|^concurrent-/.test(label ?? '');

async function waitForOwnedWorker() {
  for (let attempt = 0; attempt < 16; attempt += 1) {
    if (attempt > 0) await delay(2000);
    const current = await api(`${scriptPath}/settings`, { allowedStatuses: [404] });
    if (current.status === 404) continue;
    assert.ok(current.data.result.tags?.includes(ownershipTag), 'Worker ownership tag missing');
    return current.data.result;
  }
  throw new Error('Uploaded Worker did not become visible within 30 seconds');
}

try {
  assert.equal((await cloudWorker.fetch(new Request('https://probe.invalid/rfc'), {})).status, 503);
  assert.equal((await cloudWorker.fetch(new Request('https://probe.invalid/rfc'), { PROBE_EXPIRES_AT: '1' })).status, 503);
  assert.equal(minimalWorker.fetch(new Request('https://probe.invalid/'), {}).status, 503);
  const settings = (await api(`/accounts/${accountId}/workers/account-settings`)).data.result;
  const subscriptions = (await api(`/accounts/${accountId}/subscriptions`)).data.result;
  const workerSubscriptions = subscriptions.filter((item) => /worker/i.test(JSON.stringify(item.rate_plan)));
  report.account = { defaultUsageModel: settings.default_usage_model, workerSubscriptionCount: workerSubscriptions.length };
  const subdomain = (await api(`/accounts/${accountId}/workers/subdomain`)).data.result.subdomain;
  assert.match(subdomain, /^[a-z0-9-]+$/i, 'A registered workers.dev subdomain is required');
  const scripts = (await api(`/accounts/${accountId}/workers/scripts`)).data.result;
  assert.ok(!scripts.some((script) => script.id === name), 'Existing authbox-feasibility Worker will not be overwritten');
  if (customDomain) {
    const zones = (await api(`/zones?name=${encodeURIComponent(testZone)}`)).data.result;
    const zone = zones.find((item) => item.name === testZone && item.account?.id === accountId && item.status === 'active');
    assert.ok(zone, 'Test zone must be active in the configured account');
    zoneId = zone.id;
    const domains = (await api(`/accounts/${accountId}/workers/domains`)).data.result;
    assert.ok(!domains.some((domain) => domain.hostname === testHostname), 'Existing custom domain will not be overwritten');
    report.customDomain = { zoneMatched: true, bindingRemoved: false };
  }
  console.log('Cloudflare preflight passed; uploading an isolated synthetic probe');
  const staticBundle = staticWasm ? await buildStaticWasm() : undefined;
  const bundle = staticBundle ? undefined : await build({
    entryPoints: [routeOnly ? 'tools/feasibility/cloud-minimal-worker.mjs' : 'tools/feasibility/cloud-worker.mjs'], bundle: true, write: false,
    format: 'esm', platform: 'browser', target: 'es2022', minify: true,
  });
  const script = staticBundle?.script ?? bundle.outputFiles[0].text;
  report.staticWasmManifest = staticBundle?.manifest;
  const local = new Miniflare(convertV4MiniflareOptions({
    name, ...(staticBundle?.localOptions ?? { modules: true, script }), compatibilityDate: '2026-10-16',
    bindings: { PROBE_TOKEN: probeToken, PROBE_EXPIRES_AT: String(Date.now() + 30 * 60 * 1000) },
  }));
  try {
    if (routeOnly) {
      const fixture = await local.dispatchFetch('https://probe.invalid/');
      assert.equal(fixture.status, 200);
      assert.equal(fixture.headers.get('X-AuthBox-Probe'), marker);
      assert.equal(await fixture.text(), 'AuthBox minimal public fixture');
      assert.equal((await local.dispatchFetch('https://probe.invalid/unknown')).status, 404);
      assert.equal((await local.dispatchFetch('https://probe.invalid/', { method: 'POST' })).status, 405);
    } else {
      const unauthorized = await local.dispatchFetch('https://probe.invalid/rfc');
      assert.equal(unauthorized.status, 401);
      assert.equal(unauthorized.headers.get('X-AuthBox-Probe'), marker);
      const vector = await local.dispatchFetch('https://probe.invalid/rfc', {
        method: 'POST', headers: { Authorization: `Bearer ${probeToken}` },
      });
      assert.equal(vector.status, 200);
      assert.equal((await vector.json()).hash, rfcExpected);
      if (staticWasm) {
        const candidate = await local.dispatchFetch('https://probe.invalid/wasm', {
          method: 'POST', headers: { Authorization: `Bearer ${probeToken}` },
        });
        assert.equal(candidate.status, 200);
        assert.equal((await candidate.json()).hash, await argon2id(wasmOptions(serverProfile)));
      }
    }
    report.localBundleChecksPassed = true;
  } finally {
    await local.dispose();
  }
  const form = new FormData();
  form.set('metadata', new Blob([JSON.stringify({
    main_module: 'probe.mjs', compatibility_date: '2026-10-16', tags: [ownershipTag],
    bindings: [
      { name: 'PROBE_TOKEN', type: 'secret_text', text: probeToken },
      { name: 'PROBE_EXPIRES_AT', type: 'plain_text', text: String(Date.now() + 30 * 60 * 1000) },
    ],
  })], { type: 'application/json' }));
  form.set('probe.mjs', new Blob([script], { type: 'application/javascript+module' }), 'probe.mjs');
  for (const module of staticBundle?.modules ?? []) {
    form.set(module.name, new Blob([module.contents], { type: 'application/wasm' }), module.name);
  }
  attemptedUpload = true;
  const uploaded = (await api(scriptPath, { method: 'PUT', body: form })).data.result;
  report.deployment = { startupTimeMs: uploaded.startup_time_ms, bundleBytes: Buffer.byteLength(script), wasmBytes: staticBundle?.modules.reduce((sum, item) => sum + item.contents.byteLength, 0) };
  const liveSettings = await waitForOwnedWorker();
  report.deployment.usageModel = liveSettings.usage_model;
  report.deployment.limits = liveSettings.limits;
  await api(`${scriptPath}/subdomain`, { method: 'POST', body: JSON.stringify({ enabled: true, previews_enabled: false }) });
  const workersDevUrl = `https://${name}.${subdomain}.workers.dev`;
  let baseUrl = workersDevUrl;
  if (customDomain) {
    const origins = [{ hostname: testHostname, zone_id: zoneId, enabled: true, previews_enabled: false }];
    const changeset = (await api(`${scriptPath}/domains/changeset?replace_state=true`, {
      method: 'POST', body: JSON.stringify(origins),
    })).data.result;
    const bindingRequest = domainBindingRequest(changeset, testHostname, zoneId);
    report.customDomain.conflictPlanValidated = true;
    domainBindingAttempted = true;
    await api(`${scriptPath}/domains/records`, { method: 'PUT', body: JSON.stringify(bindingRequest) });
    const domains = (await api(`/accounts/${accountId}/workers/domains`)).data.result;
    const binding = domains.find((domain) => domain.hostname === testHostname && domain.service === name && domain.zone_id === zoneId);
    assert.ok(binding, 'Custom domain binding was not confirmed');
    report.customDomain.certificateId = binding.cert_id;
    report.customDomain.bound = true;
    await api(`${scriptPath}/subdomain`, { method: 'POST', body: JSON.stringify({ enabled: true, previews_enabled: false }) });
    report.customDomain.workersDevEnabled = (await api(`${scriptPath}/subdomain`)).data.result.enabled;
    assert.equal(report.customDomain.workersDevEnabled, true, 'workers.dev comparison endpoint must be enabled');
    baseUrl = `https://${testHostname}`;
    console.log('Custom domain attached after conflict checks; waiting for HTTPS readiness');
  }

  try {
    const tail = (await api(`${scriptPath}/tails`, { method: 'POST' })).data.result;
    tailId = tail.id;
    const tailUrl = new URL(tail.url);
    assert.equal(tailUrl.protocol, 'wss:');
    assert.ok(tailUrl.hostname.endsWith('.cloudflare.com') || tailUrl.hostname === 'tail.developers.workers.dev', 'Unexpected tail hostname');
    socket = new WebSocket(tail.url, ['trace-v1']);
    socket.binaryType = 'arraybuffer';
    socket.addEventListener('close', (event) => { report.tailCloseCode = event.code; });
    socket.addEventListener('message', (event) => {
      try {
        const raw = typeof event.data === 'string' ? event.data : Buffer.from(event.data).toString('utf8');
        const data = JSON.parse(raw);
        const headers = data.event?.request?.headers ?? {};
        const id = Object.entries(headers).find(([key]) => key.toLowerCase() === 'x-authbox-probe-request-id')?.[1];
        const label = requestLabels.get(id);
        if (!label) return;
        const pathname = data.event?.request?.url ? new URL(data.event.request.url).pathname : undefined;
        report.tail.push({
          label,
          path: ['/', '/rfc', '/noble', '/wasm', '/unknown'].includes(pathname) ? pathname : undefined,
          outcome: data.outcome, cpuTimeMs: data.cpuTime, wallTimeMs: data.wallTime,
          exceptionNames: data.exceptions?.map((exception) => exception.name),
          exceptionMessages: data.exceptions?.map((exception) => redact(exception.message).slice(0, 240)),
        });
      } catch {
        report.tailParseErrors = (report.tailParseErrors ?? 0) + 1;
      }
    });
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Tail connection timed out')), 10000);
      socket.addEventListener('open', () => {
        socket.send(JSON.stringify({ debug: false }), { binary: false, compress: false, mask: false, fin: true });
        clearTimeout(timeout);
        resolve();
      }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('Tail connection failed')); }, { once: true });
    });
    report.tailConnected = true;
  } catch (error) {
    report.tailError = redact(error.message);
  }

  const expected = routeOnly ? undefined : await argon2id(wasmOptions(serverProfile));
  async function request(route, label, { authorized = true, method = 'POST' } = {}) {
    const start = performance.now();
    const requestId = randomUUID();
    requestLabels.set(requestId, label);
    const response = await fetch(`${baseUrl}${route}`, {
      method, headers: { Accept: 'application/json', 'X-AuthBox-Probe-Request-Id': requestId, ...(authorized ? { Authorization: `Bearer ${probeToken}` } : {}) },
      redirect: 'error', signal: AbortSignal.timeout(30000),
    });
    const body = await response.text();
    let result;
    try { result = JSON.parse(body); } catch { result = {}; }
    const sample = { label, path: route, status: response.status, requestWallMs: Math.round((performance.now() - start) * 100) / 100 };
    sample.reachedProbe = response.headers.get('X-AuthBox-Probe') === marker;
    if (result.hash) {
      assert.equal(result.hash, route === '/rfc' ? rfcExpected : expected);
      sample.hashMatches = true;
    }
    if (response.status === 200) {
      if (routeOnly) {
        assert.equal(body, 'AuthBox minimal public fixture');
        sample.publicFixtureMatches = true;
      } else assert.equal(sample.hashMatches, true, 'Successful response missing expected hash');
    }
    if (response.status >= 500) {
      sample.resourceError1102 = result.error_code === 1102 || /1102/.test(body);
      sample.errorCode = result.error_code ?? body.match(/error(?:\s+code)?\s*[:=]?\s*(1\d{3})/i)?.[1];
      sample.errorTitle = (result.title ?? body.match(/<title[^>]*>(.*?)<\/title>/is)?.[1])?.replace(/\s+/g, ' ').replaceAll(`${name}.${subdomain}.workers.dev`, '[probe-host]').slice(0, 160);
      sample.server = response.headers.get('server');
    }
    if (result.error) sample.error = redact(result.error);
    report.results.push(sample);
    console.log(JSON.stringify(sample));
    return sample;
  }

  const readinessAttempts = customDomain ? 61 : 16;
  for (let attempt = 0; attempt < readinessAttempts; attempt += 1) {
    let status;
    let reachedProbe = false;
    try {
      const ready = await fetch(`${baseUrl}${routeOnly ? '/' : '/rfc'}`, { redirect: 'error', signal: AbortSignal.timeout(10000) });
      reachedProbe = ready.headers.get('X-AuthBox-Probe') === marker;
      status = ready.status;
      await ready.body?.cancel();
    } catch (error) {
      if (!customDomain) throw error;
      report.lastReadinessNetworkError = error.cause?.code ?? error.name;
    }
    if (status === (routeOnly ? 200 : 401) && reachedProbe) break;
    if (attempt === readinessAttempts - 1) {
      await request(routeOnly ? '/' : '/rfc', 'readiness-failure', { authorized: false, method: routeOnly ? 'GET' : 'POST' });
      await delay(2000);
      throw new Error('Selected entrypoint did not serve the probe within the readiness window');
    }
    console.log(JSON.stringify({ readinessAttempt: attempt + 1, status, reachedProbe }));
    await delay(2000);
  }
  if (customDomain) {
    const response = await fetch(`${workersDevUrl}${routeOnly ? '/' : '/rfc'}`, {
      headers: { Accept: 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(15000),
    });
    const body = await response.text();
    let data;
    try { data = JSON.parse(body); } catch { data = {}; }
    report.workersDevComparison = { status: response.status, reachedProbe: response.headers.get('X-AuthBox-Probe') === marker, errorCode: data.error_code };
  }
  const measurementsStart = new Date().toISOString();
  if (routeOnly) {
    assert.equal((await request('/', 'public-constant', { authorized: false, method: 'GET' })).status, 200);
  } else {
    const candidateRoute = staticWasm ? '/wasm' : '/noble';
    assert.equal((await request(candidateRoute, 'unauthorized', { authorized: false })).status, 401);
    assert.equal((await request(candidateRoute, 'invalid-method', { method: 'GET' })).status, 405);
    assert.equal((await request('/unknown', 'unknown-route')).status, 404);
    assert.equal((await request('/rfc', 'standard-vector')).status, 200);
    for (let index = 0; index < 3; index += 1) await request(candidateRoute, `sequential-${index + 1}`);
    const concurrent = await Promise.allSettled([request(candidateRoute, 'concurrent-1'), request(candidateRoute, 'concurrent-2')]);
    for (const result of concurrent) if (result.status === 'rejected') throw result.reason;
    if (!staticWasm) await request('/wasm', 'wasm-compatibility');
  }
  const measurementsEnd = new Date().toISOString();
  report.measurementWindow = { start: measurementsStart, end: measurementsEnd };
  const analyticsStart = new Date(Math.floor(Date.parse(measurementsStart) / 1000) * 1000).toISOString();
  const analyticsEnd = new Date(Math.ceil(Date.parse(measurementsEnd) / 1000) * 1000).toISOString();
  report.analyticsWindow = { start: analyticsStart, end: analyticsEnd };

  const query = `query Metrics($account: string, $start: string, $end: string, $name: string) {
    viewer { accounts(filter: {accountTag: $account}) {
      workersInvocationsAdaptive(limit: 100, filter: {scriptName: $name, datetime_geq: $start, datetime_leq: $end}) {
        sum { requests errors } quantiles { cpuTimeP50 cpuTimeP99 } dimensions { status }
      }
    } }
    __type(name: "AccountWorkersInvocationsAdaptiveQuantiles") { fields { name description } }
  }`;
  for (let attempt = 0; attempt < 5; attempt += 1) {
    if (attempt > 0) {
      console.log('Waiting 20 seconds for Cloudflare analytics ingestion');
      await delay(20000);
    }
    try {
      const analytics = (await api('/graphql', { method: 'POST', body: JSON.stringify({
        query, variables: { account: accountId, start: analyticsStart, end: analyticsEnd, name },
      }) })).data;
      report.analytics = analytics.data?.viewer?.accounts?.[0]?.workersInvocationsAdaptive ?? [];
      report.analyticsCpuFieldDescriptions = analytics.data?.__type?.fields?.filter((field) => ['cpuTimeP50', 'cpuTimeP99'].includes(field.name));
      if (report.analytics.reduce((sum, row) => sum + row.sum.requests, 0) >= report.results.length) break;
      const candidates = report.results.filter((sample) => isCandidate(sample.label));
      if (candidates.length > 0 && candidates.every((sample) => report.tail.some((event) => event.label === sample.label && Number.isFinite(event.cpuTimeMs)))) {
        report.analyticsWaitSkipped = 'Every candidate already has correlated live CPU data';
        break;
      }
    } catch (error) {
      report.analyticsError = redact(error.message);
      break;
    }
  }
} catch (error) {
  failure = error;
  report.failure = redact(error.message);
} finally {
  socket?.terminate();
  if (tailId) {
    try { await api(`${scriptPath}/tails/${tailId}`, { method: 'DELETE', allowedStatuses: [404] }); }
    catch (error) { report.tailCleanupError = redact(error.message); }
  }
  if (attemptedUpload) {
    try {
      await waitForOwnedWorker();
      if (domainBindingAttempted) {
        const domains = (await api(`/accounts/${accountId}/workers/domains`)).data.result;
        const binding = domains.find((domain) => domain.hostname === testHostname);
        if (binding) {
          assert.ok(binding.service === name && binding.zone_id === zoneId, 'Refusing to remove a domain now owned by another service');
          await api(`/accounts/${accountId}/workers/domains/${binding.id}`, { method: 'DELETE' });
        }
        const remainingDomains = (await api(`/accounts/${accountId}/workers/domains`)).data.result;
        assert.ok(!remainingDomains.some((domain) => domain.hostname === testHostname), 'Custom domain binding still exists');
        report.customDomain.bindingRemoved = true;
        report.customDomain.managedCertificateCleanup = 'Custom domain deletion does not prove certificate deletion; certificate id retained for follow-up';
      }
      await api(scriptPath, { method: 'DELETE' });
      let absentChecks = 0;
      for (let attempt = 0; attempt < 16; attempt += 1) {
        await delay(2000);
        const current = await api(`${scriptPath}/settings`, { allowedStatuses: [404] });
        const remaining = (await api(`/accounts/${accountId}/workers/scripts`)).data.result;
        const absent = current.status === 404 && !remaining.some((script) => script.id === name);
        absentChecks = absent ? absentChecks + 1 : 0;
        if (attempt >= 4 && absentChecks >= 3) break;
      }
      assert.ok(absentChecks >= 3, 'Temporary Worker deletion was not consistently visible');
      report.cleanupVerified = true;
    } catch (error) {
      report.cleanupError = redact(error.message);
      failure ??= error;
    }
  }
  report.passwordHashRequestsExecuted = report.results.filter((sample) => isCandidate(sample.label)).length;
  report.correlatedCandidateCpuCount = new Set(report.tail.filter((event) => isCandidate(event.label) && Number.isFinite(event.cpuTimeMs)).map((event) => event.label)).size;
  report.cloudCpuDataAvailable = report.tail.some((event) => Number.isFinite(event.cpuTimeMs)) || Boolean(report.analytics?.length);
  await writeFile(`${outputDirectory}/${customDomain ? 'custom-domain-' : ''}${staticWasm ? 'static-wasm-' : ''}${routeOnly ? 'route-report' : 'report'}.json`, `${JSON.stringify(report, null, 2)}\n`);
}
console.log(JSON.stringify(report, null, 2));
if (failure) process.exitCode = 1;
