import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { checkDevice, install, buildTests, instrument, artifact, device, removeArtifacts } from './device.mjs';
import { canonical } from '../../packages/protocol/src/encoding.mjs';
import { inspectBackup, restoreBackup, createBackup } from '../../packages/protocol/src/backup.mjs';
import { validateVaultEntities } from '../../packages/protocol/src/business.mjs';
import { sealRecord } from '../../packages/protocol/src/vault.mjs';
import { validateCommit, validateTransition } from '../../packages/protocol/src/sync.mjs';

export async function runAndroidCloud({ origin, fixtureToken, invite, managementInvite, registrationInvite }) {
  await checkDevice();
  const suffix = randomBytes(5).toString('hex');
  const config = { origin, fixtureToken, invite, managementInvite, registrationInvite, username: `android_${suffix}`, namespace: `authbox.test.cloud.${suffix}`,
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
    await instrument('CloudSecurityTest', 'write', 300000);
    const oldLogin = await fetch(origin + '/v1/auth/login', { method: 'POST', headers: {
      'X-AuthBox-Fixture': fixtureToken, 'Content-Type': 'application/json' },
      body: canonical({ username: config.username + '_m', password: config.password, deviceName: 'Old password observer', publicKey: null }),
      redirect: 'error', signal: AbortSignal.timeout(30000) });
    assert.equal(oldLogin.status, 401); await oldLogin.body?.cancel();
    const recoveredLogin = await (await request('/v1/auth/login', { username: config.username + '_m', password: config.password + ' recovered',
      deviceName: 'Recovery observer', publicKey: null })).json();
    assert.equal(recoveredLogin.device.role, 'reader');
    assert.equal(await (await request('/v1/vault/snapshot', undefined, recoveredLogin.session.accessToken)).text(), managementBackup);
    await request('/v1/auth/logout', {}, recoveredLogin.session.accessToken);
    results.push('Keystore device approval requires fresh device login; revocation rejects the former manager',
      'Platform password changes preserve encrypted vault and invalidate the old password',
      'Rotated account recovery code works once and revokes all old devices',
      'Independent Node observer logs in with the recovered password and downloads identical ciphertext');
    await instrument('CloudBackupTest');
    const exported = await artifact('cloud-backup-result.json');
    const backupLogin = await (await request('/v1/auth/login', { username: config.username + '_m', password: config.password + ' recovered',
      deviceName: 'Backup observer', publicKey: null })).json();
    const backupAfterWrap = await (await request('/v1/vault/snapshot', undefined, backupLogin.session.accessToken)).text();
    assert.equal(backupAfterWrap, exported.backup);
    assert.deepEqual((await inspectBackup(backupAfterWrap)).entities, (await inspectBackup(managementBackup)).entities);
    for (const [purpose, secret] of [['password', config.vaultPassword + ' changed'], ['recovery', exported.recoveryKey]]) {
      const opened = await restoreBackup(backupAfterWrap, { purpose, secret });
      try { validateVaultEntities(opened.entities); assert.equal(opened.revision, 3); assert.equal(opened.entities.length, 6); }
      finally { opened.vmk.fill(0); }
    }
    await assert.rejects(restoreBackup(backupAfterWrap, { purpose: 'password', secret: config.vaultPassword }));
    await request('/v1/auth/logout', {}, backupLogin.session.accessToken);
    results.push('Password and recovery wraps commit as one draft while entity ciphertext stays unchanged',
      'Android recovery download and independent Node decryption verify the new wraps and reject the old password');
    await instrument('CloudRegistrationTest');
    const copied = await artifact('cloud-registration-result.json');
    const kit = JSON.parse(copied.kit);
    const source = await restoreBackup(copied.source, { purpose: 'recovery', secret: exported.recoveryKey });
    try {
      const fresh = await restoreBackup(copied.registered, { purpose: 'recovery', secret: kit.vaultRecoveryKey });
      try {
        assert.notEqual(fresh.vaultId, source.vaultId); assert.notDeepEqual(fresh.vmk, source.vmk);
        assert.equal(fresh.revision, 1); assert.equal(kit.vaultId, fresh.vaultId);
        assert.equal(kit.origin, origin); assert.equal(kit.username, config.username + '_copy'); assert.equal(kit.accountRecoveryCodes.length, 8);
        assert.deepEqual(fresh.entities.map(({ metadata, payload }) => [metadata.id, payload]), source.entities.map(({ metadata, payload }) => [metadata.id, payload]));
      } finally { fresh.vmk.fill(0); }
    } finally { source.vmk.fill(0); }
    const copyLogin = await (await request('/v1/auth/login', { username: config.username + '_copy', password: config.password + ' copied',
      deviceName: 'Copied vault observer', publicKey: null })).json();
    const copyBackup = await (await request('/v1/vault/snapshot', undefined, copyLogin.session.accessToken)).text();
    assert.equal(copyBackup, copied.backup);
    const syncedCopy = await restoreBackup(copyBackup, { purpose: 'password', secret: config.vaultPassword + ' copied' });
    try {
      validateVaultEntities(syncedCopy.entities); assert.equal(syncedCopy.revision, 2); assert.equal(syncedCopy.entities.length, 6);
      assert.equal(syncedCopy.entities.find(({ metadata }) => metadata.type === 'group').payload.name, 'Copied cloud workspace');
    } finally { syncedCopy.vmk.fill(0); }
    await request('/v1/auth/logout', {}, copyLogin.session.accessToken);
    const originalLogin = await (await request('/v1/auth/login', { username: config.username + '_m', password: config.password + ' recovered',
      deviceName: 'Unchanged source observer', publicKey: null })).json();
    assert.equal(await (await request('/v1/vault/snapshot', undefined, originalLogin.session.accessToken)).text(), backupAfterWrap);
    await request('/v1/auth/logout', {}, originalLogin.session.accessToken);
    results.push('Offline copy registers a fresh manager vault with independent keys and identical business data',
      'Recovery kit matches the new account; reader downloads it and independent Node verifies subsequent sync',
      'Original cloud snapshot and local offline copy remain unchanged');
    await instrument('CloudSyncReviewTest');
    const reviewed = await artifact('cloud-review-result.json');
    const reviewLogin = await (await request('/v1/auth/login', { username: config.username, password: config.password,
      deviceName: 'Review observer', publicKey: null })).json();
    const reviewBackup = await (await request('/v1/vault/snapshot', undefined, reviewLogin.session.accessToken)).text();
    assert.equal(reviewBackup, reviewed.backup);
    const finalReview = await restoreBackup(reviewBackup, { purpose: 'recovery', secret: initial.recoveryKey });
    const originalReview = await restoreBackup(initial.backup, { purpose: 'password', secret: config.vaultPassword });
    try {
      validateVaultEntities(finalReview.entities); assert.equal(finalReview.revision, 8);
      assert.deepEqual(finalReview.entities.map(({ metadata, payload }) => [metadata.id, payload]), originalReview.entities.map(({ metadata, payload }) => [metadata.id, payload]));
      const before = await inspectBackup(reviewed.beforeHistory);
      const after = await validateCommit({ operationId: randomUUID(), baseRevision: 7, restoredIds: reviewed.restoredIds, backup: reviewBackup });
      validateTransition(before, after, new Map(reviewed.restoredIds.map((id) => [id, 'account'])), reviewed.restoredIds);
      assert.deepEqual(after.wraps, before.wraps);
    } finally { finalReview.vmk.fill(0); originalReview.vmk.fill(0); }
    const preserved = [];
    for (const encoded of reviewed.saved) {
      const opened = await restoreBackup(encoded, { purpose: 'recovery', secret: initial.recoveryKey });
      try { preserved.push(opened.entities.map(({ payload }) => payload)); } finally { opened.vmk.fill(0); }
    }
    assert.ok(preserved.some((rows) => rows.some((row) => row.name === 'Android 本地待解决')));
    assert.ok(preserved.some((rows) => rows.some((row) => row.name === 'Node concurrent update')));
    assert.ok(preserved.some((rows) => rows.some((row) => row.notes === 'Concurrent review peer')));
    const reviewHistory = await (await request('/v1/vault/history?limit=2', undefined, reviewLogin.session.accessToken)).json();
    assert.deepEqual(reviewHistory.entries.map((row) => row.revision), [8, 7]); assert.equal(reviewHistory.nextBefore, 7);
    const deniedRetired = await fetch(origin + '/v1/vault/retired', { headers: { 'X-AuthBox-Fixture': fixtureToken,
      Authorization: `Bearer ${reviewLogin.session.accessToken}` }, redirect: 'error', signal: AbortSignal.timeout(30000) });
    assert.equal(deniedRetired.status, 403); await deniedRetired.body?.cancel();
    await request('/v1/auth/logout', {}, reviewLogin.session.accessToken);
    results.push('Concurrent manager update invalidates review without altering the original pending request',
      'Confirmed resolution commits a fresh operation and preserves local and both remote candidates',
      'Historical removed account restores explicitly as revision 8 using current wraps; Node verifies exact business data',
      'Reader receives scoped paged history and is refused retired identity metadata');
    return { checks: results.length, results, model: 'PHB110', api: 35,
      instrumentation: 'CloudTest: 2 tests x 3 phases; CloudManagementTest: 1 test x 2 phases; CloudSecurityTest: 1 test; CloudBackupTest: 1 test; CloudRegistrationTest: 1 test; CloudSyncReviewTest: 1 test passed' };
  } catch (error) {
    originalFailure = error;
    throw error;
  } finally {
    await rm(configPath, { force: true });
    if (installed) {
      try { await instrument('CloudCleanupTest'); }
      catch (error) { if (!originalFailure) throw error; console.log('Android fixture cleanup failed; original failure preserved'); }
    }
    try { await removeArtifacts(['cloud-result.json', 'cloud-backup-result.json', 'cloud-registration-result.json', 'cloud-review-result.json']); }
    catch (error) { if (!originalFailure) throw error; console.log('Android output cleanup pending; original failure preserved'); }
  }
}
