import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { generateTotp } from '../../packages/protocol/src/totp.mjs';
import { fixturePassword, fixtureTime, makeBackupFixture } from './fixtures.mjs';

const serial = process.env.AUTHBOX_ANDROID_SERIAL;
const sdk = process.env.ANDROID_HOME ?? process.env.AUTHBOX_ANDROID_SDK;
const javaHome = process.env.JAVA_HOME;
if (!serial || !sdk || !javaHome) throw new Error('Set AUTHBOX_ANDROID_SERIAL, ANDROID_HOME and JAVA_HOME');
const directory = path.resolve('.cache/protocol/android');
await mkdir(directory, { recursive: true });
const dependency = path.resolve('.cache/feasibility/android-kdf/bcprov-jdk15to18-1.86.jar');
const jar = await readFile(dependency);
assert.equal(createHash('sha256').update(jar).digest('hex'), 'fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81',
  'Run verify:android-kdf first to download the pinned dependency');
const executable = (name) => path.join(javaHome, 'bin', process.platform === 'win32' ? `${name}.exe` : name);
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
function run(file, args) {
  const result = spawnSync(file, args, { encoding: 'utf8', timeout: 60000, maxBuffer: 4 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(file)} failed: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
const device = (...args) => run(adb, ['-s', serial, ...args]);
const model = device('shell', 'getprop', 'ro.product.model');
assert.equal(model, 'PHB110', 'Only the user-selected PHB110 may run this probe');
const apiLevel = device('shell', 'getprop', 'ro.build.version.sdk');
const androidJar = path.join(sdk, 'platforms', process.env.AUTHBOX_ANDROID_PLATFORM ?? 'android-35', 'android.jar');
run(executable('javac'), ['--release', '8', '-cp', `${dependency}${path.delimiter}${androidJar}`, '-d', directory, 'tools/protocol/android/AuthBoxProtocolProbe.java']);
const dex = path.join(directory, 'probe.zip');
run(executable('java'), ['-cp', path.join(sdk, 'build-tools', process.env.AUTHBOX_ANDROID_BUILD_TOOLS ?? '36.1.0', 'lib', 'd8.jar'),
  'com.android.tools.r8.D8', '--lib', androidJar, '--min-api', '26', '--output', dex, path.join(directory, 'AuthBoxProtocolProbe.class'), dependency]);
const fixture = await makeBackupFixture();
const input = path.join(directory, 'input.json');
await writeFile(input, JSON.stringify({ backup: fixture.backup, password: fixturePassword, recoveryKey: fixture.vault.recoveryKey }));
const golden = JSON.parse(await readFile('packages/protocol/test/fixtures/v1.json', 'utf8'));
const goldenPath = path.join(directory, 'golden-input.json');
await writeFile(goldenPath, JSON.stringify({ backup: golden.backup, password: golden.password, recoveryKey: golden.recoveryKey }));
const remote = `/data/local/tmp/authbox-protocol-${randomUUID()}`;
assert.match(remote, /^\/data\/local\/tmp\/authbox-protocol-[0-9a-f-]+$/);
let result;
let cleanupVerified = false;
try {
  device('shell', 'mkdir', remote);
  device('push', dex, `${remote}/probe.zip`);
  device('push', input, `${remote}/input.json`);
  result = JSON.parse(device('shell', `CLASSPATH=${remote}/probe.zip`, 'app_process', '/system/bin', 'AuthBoxProtocolProbe', `${remote}/input.json`));
  const byPassword = await restoreBackup(result.backup, { purpose: 'password', secret: fixturePassword });
  const byRecovery = await restoreBackup(result.backup, { purpose: 'recovery', secret: fixture.vault.recoveryKey });
  assert.deepEqual(byPassword.entities, byRecovery.entities);
  assert.deepEqual(byPassword.entities.map((record) => record.payload), fixture.payloads.map((record) => record.payload));
  assert.deepEqual(byPassword.vmk, fixture.vault.vmk);
  assert.equal(generateTotp(byRecovery.entities[0].payload, fixtureTime).code, '94287082');
  device('push', goldenPath, `${remote}/input.json`);
  const fixed = JSON.parse(device('shell', `CLASSPATH=${remote}/probe.zip`, 'app_process', '/system/bin', 'AuthBoxProtocolProbe', `${remote}/input.json`));
  const restored = await restoreBackup(fixed.backup, { purpose: 'recovery', secret: golden.recoveryKey });
  assert.equal(Buffer.from(restored.vmk).toString('hex'), golden.vmkHex);
  assert.deepEqual(restored.entities.map((record) => record.payload), golden.payloads.map((record) => record.payload));
} finally {
  device('shell', 'rm', '-f', `${remote}/probe.zip`, `${remote}/input.json`);
  device('shell', 'rmdir', remote);
  device('shell', 'test', '!', '-e', remote);
  cleanupVerified = true;
}
const report = { generatedAt: new Date().toISOString(), model, apiLevel,
  context: 'Real Android app_process; JCA AES-GCM, Bouncy Castle Argon2id/HKDF, synthetic data only',
  checks: [...result.checks, 'Android backup restored in Node by both secrets', 'Four entity types and restored TOTP agree', 'Published native vector restored and re-encrypted on Android'],
  cleanupVerified, limits: 'No APK, Keystore, application storage or lifecycle verification' };
await writeFile(path.join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
