import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { restoreBackup, inspectBackup } from '../../packages/protocol/src/backup.mjs';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';

await checkDevice();
await instrument('BackupStoreTest');
const input = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
const result = await artifact('backup-store-result.json');
try {
  const original = await inspectBackup(input.backup);
  const changed = await inspectBackup(result.changed);
  const rotated = await inspectBackup(result.rotated);
  assert.deepEqual(changed.entities, original.entities);
  assert.deepEqual(rotated.entities, original.entities);
  for (const [backup, purpose, secret] of [[result.changed, 'password', input.password + ' changed'],
    [result.changed, 'recovery', input.recoveryKey], [result.rotated, 'password', input.password + ' changed'],
    [result.rotated, 'recovery', result.recoveryKey]]) {
    const opened = await restoreBackup(backup, { purpose, secret });
    try { assert.equal(opened.entities.length, 4); }
    finally { opened.vmk.fill(0); }
  }
  await assert.rejects(restoreBackup(result.changed, { purpose: 'password', secret: input.password }));
  await assert.rejects(restoreBackup(result.rotated, { purpose: 'recovery', secret: input.recoveryKey }));
  console.log('Node independently verifies Android password/recovery wraps, unchanged entity ciphertext and obsolete credentials rejected');
} finally { await removeArtifacts(['backup-store-result.json']); }
