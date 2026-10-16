import { canonical } from '../../packages/protocol/src/encoding.mjs';
import { inspectBackup, restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { generateTotp } from '../../packages/protocol/src/totp.mjs';
import { rfcTotpVectors } from '../../packages/protocol/test/vectors.mjs';
import { fixturePassword, fixtureTime, makeBackupFixture } from './fixtures.mjs';

function check(condition, label) {
  if (!condition) throw new Error(label);
}

window.authboxProtocol = async ({ backup, recoveryKey, golden }) => {
  check(window.isSecureContext, 'Secure context');
  for (const vector of rfcTotpVectors) {
    check(generateTotp({ ...vector, digits: 8, period: 30 }, vector.seconds * 1000).code === vector.code, 'RFC 6238');
  }
  const byPassword = await restoreBackup(backup, { purpose: 'password', secret: fixturePassword });
  const byRecovery = await restoreBackup(backup, { purpose: 'recovery', secret: recoveryKey });
  check(canonical(byPassword.entities) === canonical(byRecovery.entities), 'Independent recovery');
  const code = generateTotp(byRecovery.entities[0].payload, fixtureTime).code;
  check(code === '94287082', 'Restored TOTP');
  const changed = JSON.parse(backup);
  changed.wraps.password.kdf.memoryKiB = 1073741824;
  let rejected = false;
  try { await inspectBackup(canonical(changed)); } catch { rejected = true; }
  check(rejected, 'KDF allocation bound');
  const fixedPassword = await restoreBackup(golden.backup, { purpose: 'password', secret: golden.password });
  const fixedRecovery = await restoreBackup(golden.backup, { purpose: 'recovery', secret: golden.recoveryKey });
  check(canonical(fixedPassword.entities) === canonical(fixedRecovery.entities), 'Published native vector');
  const generated = await makeBackupFixture();
  return { checks: ['18 RFC TOTP vectors', 'Node backup by password', 'Node backup by recovery', 'Offline restored TOTP', 'Untrusted KDF rejected', 'Published native vector by both secrets'],
    backup: generated.backup, recoveryKey: generated.vault.recoveryKey, vaultId: generated.vault.vaultId };
};
