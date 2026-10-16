import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const target = process.env.AUTHBOX_NAS_SSH_TARGET;
const key = process.env.AUTHBOX_NAS_SSH_KEY;
if (!target || !/^[a-zA-Z0-9_.-]+@[a-zA-Z0-9.-]+$/.test(target) || !key) {
  throw new Error('Set AUTHBOX_NAS_SSH_TARGET and AUTHBOX_NAS_SSH_KEY');
}
const expected = createHash('sha256').update(await readFile('LICENSE')).digest('hex');
const script = (await readFile(new URL('./nas-pull-probe.py', import.meta.url), 'utf8'))
  .replace('EXPECTED_SHA256_PLACEHOLDER', expected)
  .replaceAll('\r\n', '\n');
const result = spawnSync('ssh', [
  '-i', key,
  '-o', 'BatchMode=yes',
  '-o', 'ConnectTimeout=10',
  '-o', 'StrictHostKeyChecking=yes',
  target, 'python3', '-',
], { input: script, encoding: 'utf8', timeout: 60000 });
if (result.error) throw result.error;
if (result.status !== 0) throw new Error(`NAS probe failed: ${result.stderr}`);
const report = JSON.parse(result.stdout.trim());
for (const field of [
  'httpsDownloadPassed', 'sha256MatchesLocalFixture', 'checksumFailurePreservesPreviousFile',
  'httpRejected', 'partialFilesCleaned', 'temporaryDirectoryCleaned',
]) assert.equal(report[field], true, field);
report.generatedAt = new Date().toISOString();
await mkdir('.cache/feasibility/nas', { recursive: true });
await writeFile('.cache/feasibility/nas/report.json', `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
