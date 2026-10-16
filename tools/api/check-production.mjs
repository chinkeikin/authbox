import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { buildApi } from '../bundling/api.mjs';
import { canonical, sha256, utf8 } from '../../packages/protocol/src/encoding.mjs';
import { createAccountRecoveryCodes } from '../../packages/protocol/src/account-recovery.mjs';
import { signChallenge } from '../../packages/protocol/src/permissions.mjs';
import { makeBackupFixture } from '../protocol/fixtures.mjs';
import { device, apiFixturePassword } from '../../services/api/test/suite.mjs';

const bundle = await buildApi();
for (const name of ['/__test/', 'FIXTURE_TOKEN', 'fixture_fault']) assert.equal(bundle.script.includes(name), false);
const invitation = randomBytes(32).toString('base64url');
const origin = 'https://api.example.invalid';
const mf = new Miniflare(convertV4MiniflareOptions({ name: 'authbox-production-smoke', ...bundle.localOptions, compatibilityDate: '2026-10-16',
  durableObjects: { INSTALLATION: { className: 'InstallationStore', useSQLite: true } },
  bindings: { API_ORIGIN: origin, REGISTRATION_INVITE_HASHES: JSON.stringify([await sha256(utf8(invitation))]), CLIENT_ORIGINS: '[]' } }));
let checks = 0;
async function call(path, payload, token, expected = 200) {
  const response = await mf.dispatchFetch(origin + path, { method: payload ? 'POST' : 'GET',
    headers: { ...(payload ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: payload ? canonical(payload) : undefined });
  const value = await response.json();
  assert.equal(response.status, expected, JSON.stringify(value)); checks += 1; return value;
}
try {
  await call('/health');
  await call('/__test/audit', undefined, undefined, 404);
  const fixture = await makeBackupFixture();
  const manager = await device();
  const recovery = await createAccountRecoveryCodes();
  const context = { username: 'fixture_smoke', device: manager.value, recoveryHashes: recovery.hashes, backup: fixture.backup };
  const challenge = await call('/v1/auth/challenge', { operation: 'auth.register', username: context.username, deviceId: manager.value.id,
    requestHash: await sha256(utf8(canonical(context))), invite: invitation });
  const registered = await call('/v1/auth/register', { ...context, invite: invitation, password: apiFixturePassword,
    proof: { id: challenge.id, signature: await signChallenge(manager.keys.privateKey, challenge.payload) } }, undefined, 201);
  assert.equal(registered.device.role, 'manager');
  const reader = await call('/v1/auth/login', { username: context.username, password: apiFixturePassword, deviceName: 'Chrome smoke', publicKey: null });
  await call('/v1/vault/head', undefined, reader.session.accessToken);
  await call('/v1/vault/commit', {}, reader.session.accessToken, 403);
  await call('/v1/auth/logout', {}, reader.session.accessToken);
  await call('/v1/vault/head', undefined, reader.session.accessToken, 401);
  console.log(JSON.stringify({ productionBundleChecks: checks, fixtureCodeAbsent: true, cloudDeployment: false }));
} finally { await mf.dispose(); }
