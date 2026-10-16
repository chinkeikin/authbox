import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { build } from 'esbuild';
import { chromium } from 'playwright-core';
import { restoreBackup } from '../../packages/protocol/src/backup.mjs';
import { generateTotp } from '../../packages/protocol/src/totp.mjs';
import { fixturePassword, fixtureTime, makeBackupFixture } from './fixtures.mjs';

const fixture = await makeBackupFixture();
const golden = JSON.parse(await readFile('packages/protocol/test/fixtures/v1.json', 'utf8'));
const bundle = await build({ entryPoints: ['tools/protocol/browser-entry.mjs'], bundle: true, write: false,
  platform: 'browser', format: 'iife', target: 'es2022', legalComments: 'inline' });
const csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; object-src 'none'";
const server = createServer((request, response) => {
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('Content-Security-Policy', csp);
  if (request.url === '/') {
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end('<!doctype html><meta charset="utf-8"><title>AuthBox protocol verification</title><script src="/probe.js"></script>');
  } else if (request.url === '/probe.js') {
    response.setHeader('Content-Type', 'text/javascript; charset=utf-8');
    response.end(bundle.outputFiles[0].text);
  } else response.writeHead(404).end();
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(() => Boolean(window.authboxProtocol));
  await context.setOffline(true);
  const result = await page.evaluate((input) => window.authboxProtocol(input), { backup: fixture.backup, recoveryKey: fixture.vault.recoveryKey, golden });
  assert.deepEqual(errors, []);
  const byPassword = await restoreBackup(result.backup, { purpose: 'password', secret: fixturePassword });
  const byRecovery = await restoreBackup(result.backup, { purpose: 'recovery', secret: result.recoveryKey });
  assert.deepEqual(byPassword.entities, byRecovery.entities);
  assert.deepEqual(byPassword.entities.map((record) => record.payload), fixture.payloads.map((record) => record.payload));
  assert.equal(generateTotp(byPassword.entities[0].payload, fixtureTime).code, '94287082');
  const report = { generatedAt: new Date().toISOString(), browser: browser.version(), context: 'Isolated Chrome page, restrictive CSP, offline after bundle loaded', csp,
    checks: [...result.checks, 'Chrome backup restored in Node by both secrets', 'All four entity types agree'],
    limits: 'Not an installed MV3 extension; storage, lock and lifecycle are pending' };
  await mkdir('.cache/protocol/browser', { recursive: true });
  await writeFile('.cache/protocol/browser/report.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser?.close();
  await new Promise((resolve) => server.close(resolve));
}
