import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { argon2id } from 'hash-wasm';
import { fixturePassword, fixtureSalt, rfcExpected, vaultProfile, wasmOptions } from './fixtures.mjs';

const serial = process.env.AUTHBOX_ANDROID_SERIAL;
const sdk = process.env.ANDROID_HOME ?? process.env.AUTHBOX_ANDROID_SDK;
const javaHome = process.env.JAVA_HOME;
if (!serial || !sdk || !javaHome) {
  throw new Error('Set AUTHBOX_ANDROID_SERIAL, ANDROID_HOME (or AUTHBOX_ANDROID_SDK), and JAVA_HOME');
}
assert.deepEqual(vaultProfile, { m: 65536, t: 3, p: 1, dkLen: 32 });
const dependency = {
  coordinate: 'org.bouncycastle:bcprov-jdk15to18:1.86',
  url: 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.86/bcprov-jdk15to18-1.86.jar',
  sha256: 'fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81',
};
const outputDirectory = path.resolve('.cache/feasibility/android-kdf');
await mkdir(outputDirectory, { recursive: true });
const dependencyPath = path.join(outputDirectory, 'bcprov-jdk15to18-1.86.jar');
let dependencyBytes;
try {
  dependencyBytes = await readFile(dependencyPath);
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  const response = await fetch(dependency.url, { redirect: 'error', signal: AbortSignal.timeout(60000) });
  assert.equal(response.status, 200);
  dependencyBytes = Buffer.from(await response.arrayBuffer());
}
assert.equal(createHash('sha256').update(dependencyBytes).digest('hex'), dependency.sha256);
await writeFile(dependencyPath, dependencyBytes);

const executable = (name) => path.join(javaHome, 'bin', process.platform === 'win32' ? `${name}.exe` : name);
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
function run(file, args) {
  const result = spawnSync(file, args, { encoding: 'utf8', timeout: 120000, maxBuffer: 4 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(file)} failed: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
const device = (...args) => run(adb, ['-s', serial, ...args]);
const model = device('shell', 'getprop', 'ro.product.model');
const apiLevel = device('shell', 'getprop', 'ro.build.version.sdk');
run(executable('javac'), ['--release', '8', '-cp', dependencyPath, '-d', outputDirectory,
  'tools/feasibility/android/AuthBoxKdfProbe.java']);
const dexPath = path.join(outputDirectory, 'probe.zip');
run(executable('java'), ['-cp', path.join(sdk, 'build-tools', process.env.AUTHBOX_ANDROID_BUILD_TOOLS ?? '36.1.0', 'lib', 'd8.jar'),
  'com.android.tools.r8.D8', '--lib', path.join(sdk, 'platforms', process.env.AUTHBOX_ANDROID_PLATFORM ?? 'android-35', 'android.jar'),
  '--min-api', '26', '--output', dexPath, path.join(outputDirectory, 'AuthBoxKdfProbe.class'), dependencyPath]);

const remotePath = `/data/local/tmp/authbox-kdf-${randomUUID()}.zip`;
assert.match(remotePath, /^\/data\/local\/tmp\/authbox-kdf-[0-9a-f-]+\.zip$/);
const unicodePassword = 'AuthBox 公开测试密码🔐';
const changedSalt = Uint8Array.from(fixtureSalt);
changedSalt[0] ^= 1;
const expected = await argon2id(wasmOptions(vaultProfile));
const expectedUnicode = await argon2id({ ...wasmOptions(vaultProfile), password: unicodePassword });
const expectedChangedSalt = await argon2id({ ...wasmOptions(vaultProfile), salt: changedSalt });
let result;
let cleanupVerified = false;
try {
  device('push', dexPath, remotePath);
  result = JSON.parse(device('shell', `CLASSPATH=${remotePath}`, 'app_process', '/system/bin', 'AuthBoxKdfProbe',
    Buffer.from(fixturePassword).toString('hex'), Buffer.from(fixtureSalt).toString('hex'), Buffer.from(unicodePassword).toString('hex')));
  assert.equal(result.rfc, rfcExpected);
  assert.equal(result.samples.length, 3);
  for (const sample of result.samples) {
    assert.equal(sample.hash, expected);
    assert.ok(Number.isFinite(sample.milliseconds) && sample.milliseconds > 0);
  }
  assert.equal(result.unicode, expectedUnicode);
  assert.notEqual(result.unicode, expected);
  assert.equal(result.changedSalt, expectedChangedSalt);
  assert.notEqual(result.changedSalt, expected);
} finally {
  device('shell', 'rm', '-f', remotePath);
  device('shell', 'test', '!', '-e', remotePath);
  cleanupVerified = true;
}
const report = {
  generatedAt: new Date().toISOString(), model, apiLevel, dependency, profile: vaultProfile,
  context: 'Real Android app_process, Bouncy Castle low-level API; not an installed app or memory-pressure test',
  checks: ['RFC 9106 section 5.3', 'Three derivations agree with browser/Node hash-wasm',
    'Explicit UTF8 password agrees', 'Changed salt changes the derived key'],
  milliseconds: result.samples.map((sample) => sample.milliseconds), cleanupVerified,
};
await writeFile(path.join(outputDirectory, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
