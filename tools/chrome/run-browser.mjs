import assert from 'node:assert/strict';
import { writeFile, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { buildChrome } from '../bundling/chrome.mjs';
import { startLocalFixture } from './fixture.mjs';
import { openChrome, message, login, unlock, waitRows } from './browser.mjs';
import { generateTotp } from '../../packages/protocol/src/totp.mjs';
import { createBackup } from '../../packages/protocol/src/backup.mjs';
import { sealRecord } from '../../packages/protocol/src/vault.mjs';

const fixture = await startLocalFixture(); let browser;
const results = []; const passed = (name) => { results.push(name); console.log(JSON.stringify({ passed: name })); };
try {
  const built = await buildChrome({ origin: fixture.origin, directory: resolve(fixture.directory, 'extension') });
  const profile = resolve(fixture.directory, 'profile');
  browser = await openChrome(built, profile, { localTls: true });
  let page = await browser.page();
  const errors = []; page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') console.log(JSON.stringify({ browserConsole: message.text() })); });
  page.on('requestfailed', (request) => console.log(JSON.stringify({ networkFailure: request.failure()?.errorText })));
  await page.locator('#login-password').fill(fixture.account.password); await page.waitForTimeout(1200);
  assert.equal(await page.locator('#login-password').inputValue(), fixture.account.password);
  await login(page, fixture.account);
  await page.locator('#vault-password').fill('Wrong synthetic vault password'); await page.getByRole('button', { name: '解锁', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('解锁失败'));
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  assert.equal(await page.locator('#login-password').inputValue(), ''); assert.equal(await page.locator('#vault-password').inputValue(), '');
  passed('Real installed Chrome logs in through HTTPS and SQLite DO, checks password and fully authenticates encrypted data');
  await page.locator('#all').click(); await waitRows(page, 3);
  assert.equal(await page.locator('#accounts img').count(), 0);
  assert.equal(await page.locator('#accounts').getByText('<img src=x onerror=alert(1)>', { exact: true }).count(), 1);
  await page.locator('#search').fill('RENEW-ACCESS'); await waitRows(page, 1);
  await page.locator('#search').fill('常用'); await waitRows(page, 2);
  await page.locator('#search').fill(''); await page.locator('#group').selectOption('none'); await waitRows(page, 1);
  await page.locator('#group').selectOption('all'); await page.locator('#tag').selectOption(fixture.fixture.payloads.find((item) => item.type === 'tag').id); await waitRows(page, 2);
  await page.locator('#tag').selectOption('all'); await page.locator('#favorites').click(); await waitRows(page, 1);
  passed('Favorites, all accounts, notes and label search, groups, tags and deleted-account exclusion; labels render as text');
  const codeButton = page.locator('#accounts .code').first();
  await browser.context.grantPermissions(['clipboard-read']);
  await codeButton.click(); await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('已复制'));
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  const input = fixture.fixture.payloads.find((item) => item.type === 'account' && item.payload.favorite && item.payload.deletedAt === null).payload;
  assert.ok([Date.now(), Date.now() - 1000].map((time) => generateTotp(input, time).code).includes(copied));
  passed('User copy writes a current TOTP to the real browser clipboard');
  const persisted = await page.evaluate(() => chrome.storage.local.get(null));
  const serialized = JSON.stringify(persisted);
  for (const forbidden of [fixture.account.vaultPassword, fixture.fixture.vault.recoveryKey, input.secret, 'fixture@example.invalid', 'GitHub work', 'SECONDARY keyword']) assert.equal(serialized.includes(forbidden), false);
  assert.equal(JSON.stringify(await page.evaluate(() => chrome.storage.sync.get(null))), '{}');
  assert.equal((await page.evaluate(() => chrome.storage.session.get('unlock'))).unlock.vmk.length, 43);
  passed('Persistent extension storage contains no vault password, VMK, TOTP secret or business plaintext; storage.sync stays empty');
  const token = persisted.reader.auth.session.accessToken;
  for (const path of ['/v1/vault/commit', '/v1/vault/rotate', '/v1/challenges', '/v1/devices/approve', '/v1/devices/revoke',
    '/v1/account/password/prepare', '/v1/account/password/confirm', '/v1/account/recovery/rotate']) {
    const result = await page.evaluate(async ({ path, token, origin }) => (await fetch(origin + path, { method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}' })).status, { path, token, origin: fixture.origin });
    assert.equal(result, 403);
  }
  for (const command of ['edit', 'import', 'export', 'approve', 'rotate']) assert.equal((await message(page, command)).ok, false);
  passed('Actual Chrome reader token is denied all eight management routes; extension has no mutation or export commands');
  await page.locator('#settings summary').click();
  await page.locator('#background').selectOption('-1'); await page.locator('#apply-settings').click();
  await page.waitForSelector('#unlock-form:not([hidden])'); await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  const deadline = (await page.evaluate(() => chrome.storage.session.get('unlock'))).unlock.expiresAt;
  const current = JSON.parse(fixture.fixture.backup); const { digest, manifest, ...body } = current;
  body.revision = 2;
  body.entities = await Promise.all(current.entities.map((record) => record.type === 'group' ? sealRecord(fixture.fixture.vault.vmk,
    { vaultId: current.vaultId, id: record.id, type: record.type, revision: 2, keyGeneration: 1 }, { schemaVersion: 1, name: 'Updated cloud group', order: 0 }) : record));
  const next = await createBackup(fixture.fixture.vault.vmk, body); await fixture.commit(next);
  await message(page, 'sync', { manual: true });
  await page.waitForFunction(() => document.querySelector('#sync-status').textContent.includes('v2'));
  assert.equal((await page.evaluate(() => chrome.storage.session.get('unlock'))).unlock.expiresAt, deadline);
  await page.waitForFunction(() => document.querySelector('#accounts').textContent.includes('Updated cloud group'));
  passed('Authenticated pull sees a manager update and preserves the original unlock deadline');
  await page.screenshot({ path: resolve(fixture.directory, 'full-window.png'), fullPage: true });
  await page.close();
  const blank = await browser.context.newPage(); const swCdp = await browser.context.newCDPSession(blank);
  await swCdp.send('ServiceWorker.enable'); await swCdp.send('ServiceWorker.stopAllWorkers');
  page = await browser.page(); await waitRows(page, 1);
  passed('A real stopped MV3 service worker resumes from trusted session storage without requiring the password again');
  await fixture.pause(); await browser.context.close(); browser = await openChrome(built, profile, { localTls: true });
  page = await browser.page(); await page.waitForSelector('#unlock-form:not([hidden])');
  assert.equal((await message(page, 'status')).value.unlocked, false);
  await unlock(page, fixture.account.vaultPassword); await waitRows(page, 1);
  passed('Browser restart clears unlock state; existing encrypted cache decrypts and generates codes with the API unavailable');
  await page.screenshot({ path: resolve(fixture.directory, 'offline-window.png'), fullPage: true });
  await page.locator('#lock').click(); await page.waitForSelector('#unlock-form:not([hidden])');
  assert.equal(await page.locator('#accounts .code').count(), 0);
  await page.locator('#settings summary').click(); await page.locator('#forget').click();
  assert.equal(await page.getByRole('button', { name: '确认清除', exact: true }).isDisabled(), true);
  await page.locator('#forget-confirmed').check(); await page.getByRole('button', { name: '确认清除', exact: true }).click();
  await page.waitForFunction(async () => !(await chrome.storage.local.get('reader')).reader);
  passed('Manual lock clears all code nodes; erasing local data requires confirmation and leaves server vault intact');
  assert.deepEqual(errors, []);
  const report = { generatedAt: new Date().toISOString(), browser: browser.context.browser().version(), extensionId: built.id,
    checks: results.length, results, mode: 'Google Chrome installed MV3, loopback HTTPS with synthetic TLS, real local workerd SQLite DO',
    productionEndpoint: false, companyPolicyVerified: false, directory: fixture.directory };
  await writeFile('.cache/chrome/browser-report.json', JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
} catch (error) {
  const page = browser?.context.pages().find((page) => page.url().startsWith('chrome-extension:'));
  if (page) {
    console.log(JSON.stringify({ uiFailure: error.message, notice: await page.locator('#notice').textContent(), apiCalls: fixture.calls,
      status: await message(page, 'status') }));
    await page.screenshot({ path: resolve(fixture.directory, 'failure.png'), fullPage: true });
  }
  throw error;
} finally { await browser?.context.close(); await fixture.close(); }
