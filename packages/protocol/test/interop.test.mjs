import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { fromUtf8 } from '../src/encoding.mjs';
import { restoreBackup } from '../src/backup.mjs';
import { recordAad, wrapAad } from '../src/vault.mjs';
import { generateTotp } from '../src/totp.mjs';

test('published v1 native-crypto vector restores by password and recovery without a platform account', async () => {
  const fixture = JSON.parse(await readFile(new URL('./fixtures/v1.json', import.meta.url), 'utf8'));
  const backup = JSON.parse(fixture.backup);
  assert.equal(fromUtf8(recordAad(backup.entities[0])), fixture.entityAadUtf8);
  assert.equal(fromUtf8(wrapAad(backup.wraps.password)), fixture.passwordWrapAadUtf8);
  assert.equal(fromUtf8(wrapAad(backup.wraps.recovery)), fixture.recoveryWrapAadUtf8);
  const first = await restoreBackup(fixture.backup, { purpose: 'password', secret: fixture.password });
  const second = await restoreBackup(fixture.backup, { purpose: 'recovery', secret: fixture.recoveryKey });
  assert.deepEqual(first, second);
  assert.equal(Buffer.from(first.vmk).toString('hex'), fixture.vmkHex);
  assert.deepEqual(first.entities.map((entity) => entity.payload), fixture.payloads.map((entity) => entity.payload));
  assert.equal(generateTotp(first.entities[0].payload, 59000).code, '94287082');
});
