import { mkdir, writeFile } from 'node:fs/promises';
import { makeBackupFixture, fixturePassword, fixtureTime } from '../protocol/fixtures.mjs';
import { canonical } from '../../packages/protocol/src/encoding.mjs';
import { rfcTotpVectors } from '../../packages/protocol/test/vectors.mjs';

const fixture = await makeBackupFixture();
await mkdir('apps/android/app/build/test-fixtures', { recursive: true });
await writeFile('apps/android/app/build/test-fixtures/protocol.json', JSON.stringify({
  warning: 'PUBLIC SYNTHETIC TEST DATA ONLY', backup: fixture.backup, password: fixturePassword,
  recoveryKey: fixture.vault.recoveryKey, vmkHex: Buffer.from(fixture.vault.vmk).toString('hex'),
  payloads: fixture.payloads, time: fixtureTime,
  rfcTotpVectors,
  canonicalCases: ['中文🔐e\u0301/<>\u2028\u2029', { '\uffff': 1, '🔐': 2, a: [null, true, -1, '\b\f\n\r\t\u0000'] }]
    .map((value) => ({ value, expected: canonical(value) })),
}));
console.log('Android public protocol fixtures prepared');
