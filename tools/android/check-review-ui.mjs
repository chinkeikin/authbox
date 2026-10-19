import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { restoreBackup, inspectBackup } from '../../packages/protocol/src/backup.mjs';
import { validateCommit, validateTransition } from '../../packages/protocol/src/sync.mjs';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';
await checkDevice();
if (!process.argv.includes('--check-only')) await instrument('SyncReviewScreenTest');
const fixture = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
const output = await artifact('review-ui-result.json');
try {
  const before = await inspectBackup(output.removed);
  const after = await validateCommit(output.pending);
  assert.equal(before.revision, 3); assert.equal(before.entities.length, 3);
  validateTransition(before, after, new Map(output.pending.restoredIds.map((id) => [id, 'account'])), output.pending.restoredIds);
  const opened = await restoreBackup(output.backup, { purpose: 'password', secret: fixture.password });
  try {
    assert.equal(opened.revision, 4); assert.equal(opened.entities.length, 4);
    assert.equal(opened.entities.find(({ metadata }) => metadata.type === 'account').payload.favorite, false);
  } finally { opened.vmk.fill(0); }
  assert.deepEqual(after.wraps, before.wraps);
  console.log('Node validates real UI confirmation, retired identity submission and exported preserved snapshot');
} finally { await removeArtifacts(['review-ui-result.json']); }
