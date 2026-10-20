import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { artifact, checkDevice, instrument, removeArtifacts } from './device.mjs';
import { inspectBackup, restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { openRecord } from '../../packages/protocol/src/vault.mjs';
import { validateVaultEntities } from '../../packages/protocol/src/business.mjs';
import { validateTransition } from '../../packages/protocol/src/sync.mjs';
import { fixturePassword } from '../protocol/fixtures.mjs';

await checkDevice();
if (!process.argv.includes('--existing-result')) {
  await removeArtifacts(['rotation-result.json']);
  await instrument('KeyRotationTest', 'write', 240000);
  await instrument('KeyRotationScreenTest', 'write', 240000);
}
const result = await artifact('rotation-result.json');
const before = await restoreBackup(result.before, { purpose: 'password', secret: fixturePassword });
try {
  const old = await inspectBackup(result.before);
  const next = await inspectBackup(result.backup);
  validateTransition(old, next, new Map(), [], true);
  assert.throws(() => validateTransition(old, next));
  for (const [purpose, secret] of [['password', result.password], ['recovery', result.recoveryKey]]) {
    const opened = await restoreBackup(result.backup, { purpose, secret });
    try {
      validateVaultEntities(opened.entities);
      assert.equal(opened.vaultId, before.vaultId); assert.equal(opened.revision, before.revision + 1);
      assert.equal(opened.keyGeneration, before.keyGeneration + 1); assert.notDeepEqual(opened.vmk, before.vmk);
      assert.deepEqual(opened.entities.map(({ metadata, payload }) => [metadata.id, payload]), before.entities.map(({ metadata, payload }) => [metadata.id, payload]));
      for (const record of [...next.entities, next.manifest]) await assert.rejects(openRecord(before.vmk, record));
    } finally { opened.vmk.fill(0); }
  }
  await assert.rejects(restoreBackup(result.backup, { purpose: 'password', secret: fixturePassword }));
  await assert.rejects(restoreBackup(result.before, { purpose: 'recovery', secret: result.recoveryKey }));
} finally { before.vmk.fill(0); }
const report = { generatedAt: new Date().toISOString(), model: 'PHB110', localStoreTests: 4, uiTests: 2,
  results: ['Fresh VMK, salts, nonces and both wraps', 'Exact business preservation and full record reencryption',
    'Old VMK and password reject the new snapshot; old snapshot remains recoverable',
    'Candidate confirmation, background clearing and cancellation', 'Encrypted pending candidate survives repository recreation',
    'Lost response retries identical operation; definitive CAS conflict preserves both candidates',
    'Node independently restores Android output with new password and recovery key'],
  loginKdf: { memoryKiB: 32768, iterations: 3, parallelism: 1 }, vaultKdf: { memoryKiB: 65536, iterations: 3, parallelism: 1 },
  cloudValidation: false, realRecoveryDrill: false };
await mkdir('.cache/android', { recursive: true });
await writeFile('.cache/android/key-rotation-report.json', JSON.stringify(report, null, 2) + '\n');
await removeArtifacts(['rotation-result.json']);
console.log(JSON.stringify(report));
