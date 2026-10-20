import assert from 'node:assert/strict';
import { createBackup, restoreBackup } from '../../../packages/protocol/src/backup.mjs';
import { encodeRecoveryKey, openRecord, sealRecord, wrapVmk } from '../../../packages/protocol/src/vault.mjs';
import { randomBytes } from '../../../packages/protocol/src/encoding.mjs';
import { fixturePassword } from '../../../tools/protocol/fixtures.mjs';

export async function rotationFixture(fixture) {
  const previous = JSON.parse(fixture.backup);
  const vmk = randomBytes(32);
  const raw = randomBytes(32);
  const recoveryKey = await encodeRecoveryKey(raw); raw.fill(0);
  const password = 'New rotation synthetic password 🔐';
  const context = { vaultId: previous.vaultId, keyGeneration: previous.keyGeneration + 1 };
  const wraps = { password: await wrapVmk(vmk, context, 'password', password), recovery: await wrapVmk(vmk, context, 'recovery', recoveryKey) };
  const entities = [];
  for (const record of previous.entities) entities.push(await sealRecord(vmk, { ...context, id: record.id, type: record.type, revision: previous.revision + 1 },
    await openRecord(fixture.vault.vmk, record)));
  const backup = await createBackup(vmk, { magic: previous.magic, version: 1, ...context, revision: previous.revision + 1,
    createdAt: new Date().toISOString(), appVersion: '0.0.0', wraps, entities });
  return { previous, vmk, password, recoveryKey, backup };
}

export async function runRotationCases({ call, managed, authProof, proofFor, device, fixture, manager, session, loginPassword }) {
  const token = session.session.accessToken;
  const username = session.user.username;
  const peer = await device();
  const candidate = (await call('rotation-peer-login', '/v1/auth/login', { payload: { username, password: loginPassword, deviceName: peer.value.name, publicKey: peer.value.publicKey } })).value;
  peer.value.id = candidate.device.id;
  await managed('rotation-approve-peer', '/v1/devices/approve', 'device.approve', { deviceId: peer.value.id }, manager, token);
  const peerContext = { username, deviceId: peer.value.id };
  const peerSession = (await call('rotation-peer-manager-login', '/v1/auth/device-login', { payload: { ...peerContext, password: loginPassword,
    proof: await authProof('rotation-peer-manager', 'auth.device-login', peerContext, peer) } })).value;
  const reader = (await call('rotation-reader-login', '/v1/auth/login', { payload: { username, password: loginPassword, deviceName: 'Synthetic extension', publicKey: null } })).value;
  const next = await rotationFixture(fixture);
  try {
    const payload = { operationId: crypto.randomUUID(), baseRevision: next.previous.revision, restoredIds: [], backup: next.backup };
    const before = JSON.parse(next.backup);
    await managed('rotation-ordinary-commit-denied', '/v1/vault/commit', 'sync.commit', payload, manager, token, 400);
    await call('rotation-reader-denied', '/v1/vault/rotate', { payload: { payload, proof: {} }, token: reader.session.accessToken, expected: 403 });
    await call('rotation-operation-proof-bound', '/v1/vault/rotate', { payload: { payload, proof: await proofFor('rotation-wrong-operation', 'sync.commit', payload, manager, token) }, token, expected: 403 });
    const missing = structuredClone(before); missing.entities.pop();
    const missingPayload = { ...payload, operationId: crypto.randomUUID(), backup: await createBackup(next.vmk, (({ manifest, digest, ...body }) => body)(missing)) };
    await managed('rotation-missing-entity-denied', '/v1/vault/rotate', 'vault.rotate', missingPayload, manager, token, 400);
    const proof = await proofFor('rotation-confirm', 'vault.rotate', payload, manager, token);
    await call('rotation-fault-set', `/__test/fault?kind=operation&id=${payload.operationId}`);
    await call('rotation-transaction-rollback', '/v1/vault/rotate', { payload: { payload, proof }, token, expected: 500 });
    assert.equal((await call('rotation-rollback-head', '/v1/vault/head', { token })).value.keyGeneration, 1);
    await call('rotation-rollback-peer-still-authorized', '/v1/devices', { token: peerSession.session.accessToken });
    await call('rotation-fault-clear', '/__test/fault');
    const receipt = (await call('rotation-confirm-success', '/v1/vault/rotate', { payload: { payload, proof }, token })).value;
    assert.deepEqual((await call('rotation-idempotent-retry', '/v1/vault/rotate', { payload: { payload, proof }, token })).value, receipt);
    await call('rotation-cross-endpoint-replay-denied', '/v1/vault/commit', { payload: { payload, proof }, token, expected: 409 });
    await call('rotation-peer-session-revoked', '/v1/vault/head', { token: peerSession.session.accessToken, expected: 401 });
    await call('rotation-reader-session-revoked', '/v1/vault/head', { token: reader.session.accessToken, expected: 401 });
    await call('rotation-old-refresh-revoked', '/v1/auth/refresh', { payload: { refreshToken: peerSession.session.refreshToken }, expected: 401 });
    assert.equal((await call('rotation-download-new-generation', '/v1/vault/snapshot', { token })).text, next.backup);
    await assert.rejects(openRecord(fixture.vault.vmk, before.manifest));
    for (const [purpose, secret] of [['password', next.password], ['recovery', next.recoveryKey]]) {
      const opened = await restoreBackup(next.backup, { purpose, secret });
      try { assert.deepEqual(opened.entities.map(({ payload }) => payload), fixture.payloads.map(({ payload }) => payload)); }
      finally { opened.vmk.fill(0); }
    }
    await assert.rejects(restoreBackup(next.backup, { purpose: 'password', secret: fixturePassword }));
    await assert.rejects(restoreBackup(next.backup, { purpose: 'recovery', secret: fixture.vault.recoveryKey }));
    const old = await restoreBackup((await call('rotation-old-history-preserved', '/v1/vault/snapshot?revision=1', { token })).text,
      { purpose: 'recovery', secret: fixture.vault.recoveryKey }); old.vmk.fill(0);
    const relogin = (await call('rotation-peer-relogin-reader', '/v1/auth/device-login', { payload: { ...peerContext, password: loginPassword,
      proof: await authProof('rotation-peer-reader', 'auth.device-login', peerContext, peer) } })).value;
    assert.equal(relogin.device.role, 'reader');
    await call('rotation-demoted-manager-cannot-approve', '/v1/devices/approve', { payload: { payload: { deviceId: peer.value.id }, proof: {} }, token: relogin.session.accessToken, expected: 403 });
    await managed('rotation-reapprove-peer', '/v1/devices/approve', 'device.approve', { deviceId: peer.value.id }, manager, token);
    const restored = (await call('rotation-peer-reauthorized', '/v1/auth/device-login', { payload: { ...peerContext, password: loginPassword,
      proof: await authProof('rotation-peer-reauthorized', 'auth.device-login', peerContext, peer) } })).value;
    assert.equal(restored.device.role, 'manager');
  } finally { next.vmk.fill(0); }
}
