import { createBackup } from '../../packages/protocol/src/backup.mjs';
import { createVault, sealRecord } from '../../packages/protocol/src/vault.mjs';

export const fixturePassword = 'AuthBox 协议公开测试🔐e\u0301';
export const fixtureTime = 59000;

export async function makeBackupFixture() {
  const vault = await createVault(fixturePassword);
  const metadata = { vaultId: vault.vaultId, revision: 1, keyGeneration: 1 };
  const payloads = [
    { id: '10000000-0000-4000-8000-000000000001', type: 'account', payload: { schemaVersion: 1, issuer: '测试', name: '', account: 'fixture@example.invalid',
      secret: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', algorithm: 'SHA1', digits: 8, period: 30,
      groupId: '20000000-0000-4000-8000-000000000001', tagIds: ['30000000-0000-4000-8000-000000000001'], favorite: true, order: 0, notes: '', deletedAt: null } },
    { id: '20000000-0000-4000-8000-000000000001', type: 'group', payload: { schemaVersion: 1, name: '工作', order: 0 } },
    { id: '30000000-0000-4000-8000-000000000001', type: 'tag', payload: { schemaVersion: 1, name: '常用' } },
    { id: '40000000-0000-4000-8000-000000000001', type: 'settings', payload: { schemaVersion: 1, defaultView: 'favorites' } },
  ];
  const entities = [];
  for (const { id, type, payload } of payloads) entities.push(await sealRecord(vault.vmk, { ...metadata, id, type }, payload));
  const backup = await createBackup(vault.vmk, { magic: 'AUTHBOX-BACKUP', version: 1, ...metadata,
    appVersion: '0.0.0', createdAt: '2026-10-16T00:00:00.000Z', wraps: vault.wraps, entities });
  return { vault, backup, payloads };
}
