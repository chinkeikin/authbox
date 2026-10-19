import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { checkDevice, install, buildTests, instrument, artifact, device, removeArtifacts } from './device.mjs';
import { canonical } from '../../packages/protocol/src/encoding.mjs';
import { inspectBackup, restoreBackup, createBackup } from '../../packages/protocol/src/backup.mjs';
import { validateVaultEntities } from '../../packages/protocol/src/business.mjs';
import { sealRecord } from '../../packages/protocol/src/vault.mjs';

export async function runAndroidCloud({ origin, fixtureToken, invite, managementInvite }) {
  await checkDevice();
  const suffix = randomBytes(5).toString('hex');
  const config = { origin, fixtureToken, invite, managementInvite, username: `android_${suffix}`, namespace: `authbox.test.cloud.${suffix}`,
    password: 'AuthBox Android synthetic login 🔐', vaultPassword: 'AuthBox Android synthetic vault 🔐' };
  const configPath = 'apps/android/app/build/test-fixtures/cloud-config.json';
  const request = async (path, body, token) => {
    const response = await fetch(origin + path, { method: body ? 'POST' : 'GET', redirect: 'error',
      headers: { 'X-AuthBox-Fixture': fixtureToken, ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? canonical(body) : undefined, signal: AbortSignal.timeout(30000) });
    assert.ok(response.ok, `Independent Node request ${path} HTTP ${response.status}`);
    return response;
  };
  const results = [];
  let installed = false;
  let originalFailure;
  try {
    await writeFile(configPath, JSON.stringify(config));
    await buildTests();
    await install();
    installed = true;
    await instrument('CloudTest', 'write');
    const initial = await artifact('cloud-result.json');
    const restored = await restoreBackup(initial.backup, { purpose: 'recovery', secret: initial.recoveryKey });
    try { validateVaultEntities(restored.entities); assert.equal(restored.revision, 2); assert.equal(restored.entities.length, 4); }
    finally { restored.vmk.fill(0); }
    results.push('Android generates recoverable vault and registers first Keystore manager',
      'Android uploads full encrypted snapshot to free SQLite DO', 'Ordinary login stays reader and server rejects writes',
      'Refresh rotates session and encrypted local state', 'Foreign signing contexts are rejected');
    await device('shell', 'am', 'force-stop', 'com.authbox.android');
    await instrument('CloudTest', 'read');
    const resumed = await artifact('cloud-result.json');
    const auth = await (await request('/v1/auth/login', { username: config.username, password: config.password,
      deviceName: 'Independent Node observer', publicKey: null })).json();
    const access = auth.session.accessToken;
    const cloudBackup = await (await request('/v1/vault/snapshot', undefined, access)).text();
    assert.equal(cloudBackup, resumed.backup);
    const cloud = await restoreBackup(cloudBackup, { purpose: 'recovery', secret: initial.recoveryKey });
    try {
      validateVaultEntities(cloud.entities);
      assert.equal(cloud.revision, 3);
      assert.equal(cloud.entities.find(({ metadata }) => metadata.type === 'account').payload.favorite, false);
      const backup = await inspectBackup(cloudBackup);
      const changed = cloud.entities.find(({ metadata }) => metadata.type === 'group');
      changed.payload.name = 'Node concurrent update';
      const entities = [];
      for (const record of backup.entities) entities.push(record.id === changed.metadata.id
        ? await sealRecord(cloud.vmk, { vaultId: record.vaultId, id: record.id, type: record.type, revision: 4, keyGeneration: record.keyGeneration }, changed.payload) : record);
      const { manifest, digest, ...base } = backup;
      config.peerPayload = { operationId: randomUUID(), baseRevision: 3, restoredIds: [], backup: await createBackup(cloud.vmk, {
        ...base, revision: 4, createdAt: new Date().toISOString(), entities }) };
    } finally { cloud.vmk.fill(0); }
    results.push('Pending operation survives force-stop and resumes with the same operation ID',
      'Independent Node login downloads and decrypts Android revision 3', 'Wrong password rejected; existing Keystore device regains manager role');
    await writeFile(configPath, JSON.stringify(config));
    await buildTests(); await install(true);
    await device('shell', 'am', 'force-stop', 'com.authbox.android');
    await instrument('CloudTest', 'conflict');
    const head = await (await request('/v1/vault/head', undefined, access)).json();
    assert.equal(head.revision, 4);
    const latest = await (await request('/v1/vault/snapshot', undefined, access)).text();
    assert.equal(latest, config.peerPayload.backup);
    const untouched = await artifact('cloud-result.json');
    assert.equal(untouched.pending.operationId, resumed.pending.operationId);
    results.push('Stale CAS returns 409 and preserves local edits; download cannot overwrite pending work');
    await request('/v1/auth/logout', {}, access);
    await instrument('CloudManagementTest', 'write');
    await device('shell', 'am', 'force-stop', 'com.authbox.android');
    await instrument('CloudManagementTest', 'read');
    const management = await (await request('/v1/auth/login', { username: config.username + '_m', password: config.password,
      deviceName: 'Node management observer', publicKey: null })).json();
    const managementBackup = await (await request('/v1/vault/snapshot', undefined, management.session.accessToken)).text();
    const managed = await restoreBackup(managementBackup, { purpose: 'password', secret: config.vaultPassword });
    try {
      validateVaultEntities(managed.entities); assert.equal(managed.revision, 2);
      assert.equal(managed.entities.filter(({ metadata }) => metadata.type === 'account').length, 4);
      assert.equal(managed.entities.find(({ metadata }) => metadata.type === 'group').payload.name, 'Cloud QA renamed');
      assert.ok(managed.entities.filter(({ metadata }) => metadata.type === 'account').every(({ payload }) => payload.favorite && payload.deletedAt === null && payload.tagIds.length === 1));
    } finally { managed.vmk.fill(0); }
    await request('/v1/auth/logout', {}, management.session.accessToken);
    results.push('Standard and Google batch imports plus group/tag edits persist as one offline draft',
      'Draft survives force-stop and commits once to SQLite DO', 'Independent Node observer validates all imported accounts and associations');
    return { checks: results.length, results, model: 'PHB110', api: 35,
      instrumentation: 'CloudTest: 2 tests x 3 phases; CloudManagementTest: 1 test x 2 phases passed' };
  } catch (error) {
    originalFailure = error;
    throw error;
  } finally {
    await rm(configPath, { force: true });
    if (installed) {
      try { await instrument('CloudCleanupTest'); }
      catch (error) { if (!originalFailure) throw error; console.log('Android fixture cleanup failed; original failure preserved'); }
    }
    await removeArtifacts(['cloud-result.json']);
  }
}
