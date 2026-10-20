import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { buildChrome } from '../bundling/chrome.mjs';
import { startLocalFixture } from './fixture.mjs';
import { openChrome, message, login, unlock, waitRows } from './browser.mjs';
import { createBackup } from '../../packages/protocol/src/backup.mjs';
import { sealRecord } from '../../packages/protocol/src/vault.mjs';
import { nativePopup } from './popup.mjs';

const fixture = await startLocalFixture(); let browser; let release;
const results = []; const passed = (name) => { results.push(name); console.log(JSON.stringify({ passed: name })); };
try {
  const built = await buildChrome({ origin: fixture.origin, directory: resolve(fixture.directory, 'extension') });
  browser = await openChrome(built, resolve(fixture.directory, 'profile'), { localTls: true });
  let page = await browser.page(); await login(page, fixture.account); await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  await nativePopup(browser, page, resolve(fixture.directory, 'actual-popup.png'));
  await page.goto('about:blank'); await page.close(); page = await browser.page();
  await page.waitForSelector('#unlock-form:not([hidden])');
  passed('Real Chrome toolbar action opens the popup; leaving all active extension windows locks by default');
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  await page.locator('#settings summary').click(); await page.locator('#idle').selectOption('1'); await page.locator('#background').selectOption('-1');
  await page.locator('#apply-settings').click(); await page.waitForSelector('#unlock-form:not([hidden])');
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  console.log('Waiting for the real 60-second idle deadline; automatic rendering must not extend it');
  await page.waitForSelector('#unlock-form:not([hidden])', { timeout: 70000 });
  assert.equal(await page.locator('#accounts .code').count(), 0); assert.equal((await message(page, 'status')).value.unlocked, false);
  passed('Real 60-second inactivity locks the view even while code countdown and synchronization checks continue');
  await page.locator('#idle').selectOption('5'); await page.locator('#background').selectOption('30'); await page.locator('#apply-settings').click();
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  await page.goto('about:blank'); console.log('Waiting for the real 30-second background grace');
  await page.waitForTimeout(31000); await page.close(); page = await browser.page();
  await page.waitForSelector('#unlock-form:not([hidden])'); assert.equal((await message(page, 'status')).value.unlocked, false);
  passed('Real 30-second background grace expires while the extension view is closed');
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  await page.evaluate(() => chrome.runtime.reload()).catch(() => {}); await new Promise((resolve) => setTimeout(resolve, 1000));
  page = await browser.page(); await page.waitForSelector('#unlock-form:not([hidden])');
  assert.equal((await message(page, 'status')).value.unlocked, false);
  passed('Actual extension reload clears session unlock state and preserves only encrypted cache and reader login');
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  const previous = JSON.parse(fixture.fixture.backup); const { digest, manifest, ...body } = previous;
  body.revision = 2;
  body.entities = await Promise.all(previous.entities.map((record) => record.type === 'group' ? sealRecord(fixture.fixture.vault.vmk,
    { vaultId: previous.vaultId, id: record.id, type: record.type, revision: 2, keyGeneration: 1 }, { schemaVersion: 1, name: 'Late response group', order: 0 }) : record));
  await fixture.commit(await createBackup(fixture.fixture.vault.vmk, body));
  let enter; const entered = new Promise((resolve) => { enter = resolve; });
  fixture.gateSnapshots(() => { enter(); return new Promise((resolve) => { release = resolve; }); });
  await page.locator('#sync').click(); await entered;
  await page.locator('#lock').click(); release(); release = null; fixture.gateSnapshots(null);
  await page.waitForSelector('#unlock-form:not([hidden])');
  assert.equal((await message(page, 'status')).value.unlocked, false); assert.equal(await page.locator('#accounts .code').count(), 0);
  passed('Manual lock during an actual delayed HTTPS snapshot response cannot be undone by the response');
  const outsider = await browser.context.newPage(); await outsider.goto(fixture.origin + '/health');
  const external = await outsider.evaluate((id) => new Promise((resolve) => {
    if (!chrome.runtime?.sendMessage) return resolve('unavailable');
    chrome.runtime.sendMessage(id, { type: 'status' }, (value) => resolve(chrome.runtime.lastError ? 'rejected' : value));
  }), built.id);
  assert.ok(['unavailable', 'rejected'].includes(external));
  passed('Ordinary web page cannot invoke extension vault messages');
  const report = { generatedAt: new Date().toISOString(), browser: browser.context.browser().version(), checks: results.length, results,
    realIdleSeconds: 60, realBackgroundSeconds: 30, actualPopup: true, actualReload: true, directory: fixture.directory };
  await writeFile('.cache/chrome/lifecycle-report.json', JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
} catch (error) {
  const pages = browser?.context.pages() ?? []; console.log(JSON.stringify({ failure: error.message, pages: pages.map((page) => page.url()) }));
  throw error;
} finally { release?.(); await browser?.context.close(); await fixture.close(); }
