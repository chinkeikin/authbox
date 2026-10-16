import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

const target = process.env.AUTHBOX_NAS_SSH_TARGET;
const key = process.env.AUTHBOX_NAS_SSH_KEY;
if (!target || !/^[a-zA-Z0-9_.-]+@[a-zA-Z0-9.-]+$/.test(target) || !key) {
  throw new Error('Set AUTHBOX_NAS_SSH_TARGET and AUTHBOX_NAS_SSH_KEY');
}
const script = await readFile(new URL('./nas-inspect.sh', import.meta.url), 'utf8');
const result = spawnSync('ssh', [
  '-i', key,
  '-o', 'BatchMode=yes',
  '-o', 'ConnectTimeout=10',
  '-o', 'StrictHostKeyChecking=yes',
  target, 'sh', '-s',
], { input: script.replaceAll('\r\n', '\n'), encoding: 'utf8', timeout: 30000 });
if (result.stdout) process.stdout.write(result.stdout);
if (result.stderr) process.stderr.write(result.stderr);
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
