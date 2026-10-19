import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { restoreBackup, inspectBackup } from '../../packages/protocol/src/backup.mjs';
import { validateCommit, validateTransition } from '../../packages/protocol/src/sync.mjs';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';
await checkDevice();
await instrument('SyncReviewCleanupTest');
await instrument('SyncReviewStoreTest');
const fixture = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
const output = await artifact('review-result.json');
try {
  const before = await inspectBackup(output.removed);
  const after = await validateCommit(output.pending);
  const id = output.pending.restoredIds[0];
  validateTransition(before, after, new Map([[id, 'account']]), output.pending.restoredIds);
  const vault = await restoreBackup(output.backup, { purpose: 'password', secret: fixture.password });
  try { assert.equal(vault.revision, 4); assert.equal(vault.entities.length, 4); assert.equal(vault.entities.find(({ metadata }) => metadata.type === 'account').payload.favorite, false); }
  finally { vault.vmk.fill(0); }
  assert.deepEqual(after.wraps, before.wraps);
  console.log('Node verifies Android conflict rebase, current wraps, increasing revision and explicit retired identity restoration');
} finally { await removeArtifacts(['review-result.json']); }
