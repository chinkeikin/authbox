import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { restoreBackup, inspectBackup } from '../../packages/protocol/src/backup.mjs';
import { validateCommit, validateTransition } from '../../packages/protocol/src/sync.mjs';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';

await checkDevice();
await instrument('RegistrationStoreTest');
const fixture = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
const result = await artifact('registration-result.json');
try {
  const kit = JSON.parse(result.kit);
  assert.equal(kit.magic, 'AUTHBOX-RECOVERY-KIT'); assert.equal(kit.accountRecoveryCodes.length, 8);
  const old = await restoreBackup(result.source, { purpose: 'password', secret: fixture.password });
  try {
    for (const [purpose, secret] of [['recovery', kit.vaultRecoveryKey], ['password', 'AuthBox copied vault password']]) {
      const fresh = await restoreBackup(result.backup, { purpose, secret });
      try {
        assert.notEqual(fresh.vaultId, old.vaultId); assert.notDeepEqual(fresh.vmk, old.vmk);
        assert.equal(fresh.revision, 1); assert.equal(fresh.entities.length, 4);
        assert.deepEqual(fresh.entities.map(({ metadata, payload }) => [metadata.id, payload]), old.entities.map(({ metadata, payload }) => [metadata.id, payload]));
      } finally { fresh.vmk.fill(0); }
    }
    assert.equal(kit.vaultId, (await inspectBackup(result.backup)).vaultId);
    const commit = await validateCommit({ operationId: kit.vaultId, baseRevision: 0, restoredIds: [], backup: result.backup });
    await validateTransition(undefined, commit);
  } finally { old.vmk.fill(0); }
  await assert.rejects(restoreBackup(result.backup, { purpose: 'recovery', secret: fixture.recoveryKey }));
  console.log('Node validates fresh registration snapshot, independent keys, unchanged business data and matching recovery kit');
} finally { await removeArtifacts(['registration-result.json']); }
