import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { makeInteropFixture } from './fixtures.mjs';

const serial = process.env.AUTHBOX_ANDROID_SERIAL;
const sdk = process.env.ANDROID_HOME ?? process.env.AUTHBOX_ANDROID_SDK;
const javaHome = process.env.JAVA_HOME;
if (!serial || !sdk || !javaHome) {
  throw new Error('Set AUTHBOX_ANDROID_SERIAL, ANDROID_HOME (or AUTHBOX_ANDROID_SDK), and JAVA_HOME');
}
const buildTools = process.env.AUTHBOX_ANDROID_BUILD_TOOLS ?? '36.1.0';
const platform = process.env.AUTHBOX_ANDROID_PLATFORM ?? 'android-35';
const outputDirectory = path.resolve('.cache/feasibility/android');
await mkdir(outputDirectory, { recursive: true });
const executable = (name) => path.join(javaHome, 'bin', process.platform === 'win32' ? `${name}.exe` : name);
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');

function run(file, args, acceptFailure = false) {
  const result = spawnSync(file, args, { encoding: 'utf8', timeout: 30000 });
  if (result.error) throw result.error;
  if (!acceptFailure && result.status !== 0) {
    throw new Error(`${path.basename(file)} failed: ${result.stderr || result.stdout}`);
  }
  return result;
}
const device = (...args) => run(adb, ['-s', serial, ...args]);
const model = device('shell', 'getprop', 'ro.product.model').stdout.trim();
const apiLevel = device('shell', 'getprop', 'ro.build.version.sdk').stdout.trim();
run(executable('javac'), ['--release', '8', '-d', outputDirectory, 'tools/feasibility/android/AuthBoxCryptoProbe.java']);
run(executable('java'), [
  '-cp', path.join(sdk, 'build-tools', buildTools, 'lib', 'd8.jar'),
  'com.android.tools.r8.D8', '--lib', path.join(sdk, 'platforms', platform, 'android.jar'),
  '--min-api', '26', '--output', outputDirectory, path.join(outputDirectory, 'AuthBoxCryptoProbe.class'),
]);

const fixture = makeInteropFixture();
const key = Buffer.from(fixture.key);
const nonce = Buffer.from(fixture.nonce);
const aad = Buffer.from(fixture.aad);
const plaintext = Buffer.from(fixture.plaintext);
const nodeKey = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt', 'decrypt']);
const nodeCiphertext = Buffer.from(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad }, nodeKey, plaintext));
const remotePath = `/data/local/tmp/authbox-feasibility-${randomUUID()}.dex`;
assert.match(remotePath, /^\/data\/local\/tmp\/authbox-feasibility-[0-9a-f-]+\.dex$/);

function probe(action, data, changes = {}, acceptFailure = false) {
  return run(adb, ['-s', serial, 'shell', `CLASSPATH=${remotePath}`, 'app_process', '/system/bin', 'AuthBoxCryptoProbe',
    action,
    (changes.key ?? key).toString('hex'),
    (changes.nonce ?? nonce).toString('hex'),
    (changes.aad ?? aad).toString('hex'),
    data.toString('hex'),
  ], acceptFailure);
}

let cleanupVerified = false;
let checks = [];
try {
  device('push', path.join(outputDirectory, 'classes.dex'), remotePath);
  const androidCiphertext = Buffer.from(probe('encrypt', plaintext).stdout.trim(), 'hex');
  assert.deepEqual(androidCiphertext, nodeCiphertext);
  assert.equal(probe('decrypt', nodeCiphertext).stdout.trim(), plaintext.toString('hex'));
  const nodePlaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad }, nodeKey, androidCiphertext);
  assert.deepEqual(Buffer.from(nodePlaintext), plaintext);
  checks.push('Android JCA and Node WebCrypto encrypt/decrypt agree');

  for (const item of ['ciphertext', 'tag', 'nonce', 'aad', 'key']) {
    const changes = {};
    const modified = Buffer.from(nodeCiphertext);
    if (item === 'ciphertext') modified[0] ^= 1;
    else if (item === 'tag') modified[modified.length - 1] ^= 1;
    else {
      changes[item] = Buffer.from({ nonce, aad, key }[item]);
      changes[item][0] ^= 1;
    }
    const rejected = probe('decrypt', modified, changes, true);
    assert.equal(rejected.status, 3, `${item} did not fail with an authentication error`);
    assert.match(rejected.stderr, /AEAD_AUTHENTICATION_FAILED/);
    checks.push(`Reject modified ${item}`);
  }
} finally {
  device('shell', 'rm', '-f', remotePath);
  device('shell', 'test', '!', '-e', remotePath);
  cleanupVerified = true;
}
const report = {
  generatedAt: new Date().toISOString(),
  model,
  apiLevel,
  context: 'Android app_process shell JCA; app lifecycle, Keystore and Argon2id not yet validated',
  checks,
  cleanupVerified,
};
await writeFile(path.join(outputDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
