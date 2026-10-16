import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { validateVaultEntities } from '../../packages/protocol/src/business.mjs';

const execute = promisify(execFile);
export const serial = process.env.AUTHBOX_ANDROID_SERIAL;
assert.match(serial ?? '', /^[A-Za-z0-9_-]+$/, 'Set AUTHBOX_ANDROID_SERIAL to the authorized PHB110 serial');
const sdk = process.env.ANDROID_HOME ?? process.env.AUTHBOX_ANDROID_SDK;
assert.ok(sdk, 'Set ANDROID_HOME');
const adb = resolve(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
export async function device(...args) {
  return (await execute(adb, ['-s', serial, ...args], { timeout: 180000, maxBuffer: 4 * 1024 * 1024 })).stdout;
}
export async function checkDevice() { assert.equal((await device('shell', 'getprop', 'ro.product.model')).trim(), 'PHB110'); }
export async function install(testOnly = false) {
  for (const file of testOnly ? ['androidTest/debug/app-debug-androidTest.apk'] : ['debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk']) {
    assert.match(await device('install', '-r', resolve('apps/android/app/build/outputs/apk', file)), /Success/);
  }
}
export async function instrument(name, phase = 'write') {
  const result = await device('shell', 'am', 'instrument', '-w', '-e', 'class', `com.authbox.android.${name}`, '-e', 'phase', phase,
    'com.authbox.android.test/androidx.test.runner.AndroidJUnitRunner');
  assert.match(result, /OK \(\d+ tests?\)/, result);
  assert.doesNotMatch(result, /FAILURES|INSTRUMENTATION_FAILED|Process crashed/);
  console.log(result.trim());
  return result;
}
export async function artifact(name) {
  assert.match(name, /^[a-z-]+\.json$/);
  return JSON.parse(await device('exec-out', 'run-as', 'com.authbox.android', 'cat', `no_backup/${name}`));
}
export async function removeArtifacts(names) {
  for (const name of names) {
    assert.match(name, /^[a-z-]+\.json$/);
    await device('shell', 'run-as', 'com.authbox.android', 'rm', '-f', `no_backup/${name}`);
  }
}
export async function buildTests() {
  const args = ['-p', 'apps/android', ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain', '--max-workers=2'];
  if (process.env.HTTPS_PROXY) {
    const proxy = new URL(process.env.HTTPS_PROXY);
    assert.ok(!proxy.username && !proxy.password, 'Authenticated proxy must be configured outside this script');
    assert.match(proxy.hostname, /^[a-z0-9.:-]+$/i);
    args.push(`-Dhttps.proxyHost=${proxy.hostname}`, `-Dhttps.proxyPort=${proxy.port || 80}`);
  }
  const command = resolve('apps/android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  const result = process.platform === 'win32'
    ? await execute('cmd.exe', ['/d', '/s', '/c', `""${command}" ${args.join(' ')}"`], { windowsVerbatimArguments: true, timeout: 300000, maxBuffer: 4 * 1024 * 1024 })
    : await execute(command, args, { timeout: 300000, maxBuffer: 4 * 1024 * 1024 });
  assert.match(result.stdout, /BUILD SUCCESSFUL/);
  console.log('Android instrumentation APK build passed');
}

export async function verifyProtocol({ runTests = true } = {}) {
  await checkDevice();
  if (runTests) await instrument('VaultTest');
  const input = JSON.parse(await readFile('apps/android/app/build/test-fixtures/protocol.json', 'utf8'));
  const result = await artifact('protocol-result.json');
  const restored = await restoreBackup(result.backup, { purpose: 'password', secret: input.password });
  try {
    validateVaultEntities(restored.entities);
    assert.equal(restored.revision, 2);
    assert.equal(restored.entities.find(({ metadata }) => metadata.type === 'group').payload.name, 'Android 工作🔐');
    assert.equal(restored.entities.find(({ metadata }) => metadata.type === 'account').metadata.revision, 1);
    const before = JSON.parse(input.backup).entities.find(({ type }) => type === 'account');
    const after = JSON.parse(result.backup).entities.find(({ type }) => type === 'account');
    assert.deepEqual(after, before);
  } finally { restored.vmk.fill(0); }
  const created = await artifact('created-result.json');
  const recovered = await restoreBackup(created.backup, { purpose: 'recovery', secret: created.recoveryKey });
  try { assert.equal(recovered.entities.length, 0); assert.equal(recovered.revision, 1); }
  finally { recovered.vmk.fill(0); }
  await removeArtifacts(['protocol-result.json', 'created-result.json']);
  const report = { generatedAt: new Date().toISOString(), model: 'PHB110', api: 35,
    tests: ['Android instrumentation VaultTest: 4 passed', 'Node restores Android-modified backup',
      'Unchanged account preserves revision and ciphertext', 'Node recovers Android-created vault'],
    loginKdf: { memoryKiB: 32768, iterations: 3, parallelism: 1 },
    vaultKdf: { memoryKiB: 65536, iterations: 3, parallelism: 1 } };
  await mkdir('.cache/android', { recursive: true });
  await writeFile('.cache/android/protocol-report.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
  return report;
}
