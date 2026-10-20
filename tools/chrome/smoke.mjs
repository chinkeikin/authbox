import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright-core';
import { buildChrome } from '../bundling/chrome.mjs';

const built = await buildChrome();
const directory = resolve('.cache/chrome/smoke-' + randomUUID()); await mkdir(directory, { recursive: true });
const context = await chromium.launchPersistentContext(directory, { channel: 'chrome', headless: true,
  args: ['--enable-unsafe-extension-debugging'], ignoreDefaultArgs: ['--disable-extensions'] });
try {
  const page = await context.newPage();
  const cdp = await context.browser().newBrowserCDPSession();
  const loaded = await cdp.send('Extensions.loadUnpacked', { path: built.directory });
  assert.equal(loaded.id, built.id);
  const errors = []; page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`chrome-extension://${built.id}/index.html`);
  const result = await page.evaluate(() => chrome.runtime.sendMessage({ type: 'status' }));
  assert.equal(result.ok, true, JSON.stringify(result)); assert.equal(result.value.unlocked, false);
  assert.deepEqual(errors, []);
  const report = { generatedAt: new Date().toISOString(), browser: context.browser()?.version(),
    extensionId: built.id, installed: true, runtimeMessagePassed: true, mode: 'isolated Google Chrome, CDP unpacked install, no user profile changed' };
  await writeFile('.cache/chrome/smoke-report.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
} finally { await context.close(); }
