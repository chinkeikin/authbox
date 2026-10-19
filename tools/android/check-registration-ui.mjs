import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';

await checkDevice();
if (!process.argv.includes('--check-only')) await instrument('RegistrationScreenTest');
const fixture = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
const result = await artifact('registration-ui-result.json');
try {
  const kit = JSON.parse(result.kit);
  assert.equal(kit.magic, 'AUTHBOX-RECOVERY-KIT'); assert.equal(kit.accountRecoveryCodes.length, 8);
  const original = await restoreBackup(fixture.backup, { purpose: 'password', secret: fixture.password });
  try {
    for (const [purpose, secret] of [['recovery', kit.vaultRecoveryKey], ['password', 'AuthBox copied UI vault password']]) {
      const copied = await restoreBackup(result.backup, { purpose, secret });
      try {
        assert.equal(copied.vaultId, kit.vaultId); assert.notEqual(copied.vaultId, original.vaultId);
        assert.notDeepEqual(copied.vmk, original.vmk); assert.equal(copied.revision, 1);
        assert.deepEqual(copied.entities.map(({ metadata, payload }) => [metadata.id, payload]), original.entities.map(({ metadata, payload }) => [metadata.id, payload]));
      } finally { copied.vmk.fill(0); }
    }
  } finally { original.vmk.fill(0); }
  await assert.rejects(restoreBackup(result.backup, { purpose: 'recovery', secret: fixture.recoveryKey }));
  console.log('Node validates the actual UI recovery file, new vault identity and credentials, and all copied business data');
} finally { await removeArtifacts(['registration-ui-result.json']); }
