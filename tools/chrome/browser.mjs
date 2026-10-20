import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

export async function openChrome(built, directory, { localTls = false } = {}) {
  await mkdir(directory, { recursive: true });
  const context = await chromium.launchPersistentContext(resolve(directory), { channel: 'chrome', headless: true,
    ignoreHTTPSErrors: localTls, args: ['--enable-unsafe-extension-debugging', ...(localTls ? ['--ignore-certificate-errors'] : [])],
    ignoreDefaultArgs: ['--disable-extensions'] });
  try {
    const cdp = await context.browser().newBrowserCDPSession();
    const existing = (await cdp.send('Extensions.getExtensions')).extensions.find((item) => item.id === built.id);
    if (!existing) assert.equal((await cdp.send('Extensions.loadUnpacked', { path: built.directory })).id, built.id);
    else assert.equal(existing.enabled, true);
    return { context, cdp,
      async page(popup = false) {
        const page = await context.newPage(); await page.goto(`chrome-extension://${built.id}/index.html${popup ? '?popup=1' : ''}`);
        await page.bringToFront(); await page.waitForSelector('#login-form', { state: 'attached' }); return page;
      } };
  } catch (error) { await context.close(); throw error; }
}

export async function message(page, type, value) {
  return page.evaluate(({ type, value }) => chrome.runtime.sendMessage({ type, value }), { type, value });
}
export async function waitRows(page, count) {
  await page.waitForFunction((count) => document.querySelectorAll('#accounts .account').length === count, count, { timeout: 20000 });
}
export async function login(page, account) {
  await page.locator('#username').fill(account.username); await page.locator('#login-password').fill(account.password);
  await page.getByRole('button', { name: '登录只读设备', exact: true }).click();
  await page.waitForSelector('#unlock-form:not([hidden])');
}
export async function unlock(page, password) {
  await page.locator('#vault-password').fill(password); await page.getByRole('button', { name: '解锁', exact: true }).click();
  await page.waitForSelector('#vault:not([hidden])', { timeout: 20000 });
}
