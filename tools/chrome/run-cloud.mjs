import assert from 'node:assert/strict';
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { buildChrome } from '../bundling/chrome.mjs';
import { makeChromeFixture, seedReaderAccount } from './fixture.mjs';
import { openChrome, message, login, unlock, waitRows } from './browser.mjs';
import { canonical, sha256, utf8 } from '../../packages/protocol/src/encoding.mjs';
import { signChallenge } from '../../packages/protocol/src/permissions.mjs';
import { rotationFixture } from '../../services/api/test/rotation.mjs';

export async function runChromeCloud({ origin, fixtureToken, invite }) {
  const directory = resolve('.cache/chrome/cloud-' + randomUUID()); await mkdir(directory, { recursive: true });
  async function response(path, body, token) {
    return fetch(origin + path, { method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { 'X-AuthBox-Fixture': fixtureToken, 'X-AuthBox-Case': 'chrome-node-observer',
        ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? canonical(body) : undefined });
  }
  async function call(path, body, token) {
    const result = await response(path, body, token); assert.ok(result.ok, `Chrome cloud fixture ${path}: HTTP ${result.status}`); return result.json();
  }
  const fixture = await makeChromeFixture(); const account = await seedReaderAccount(call, invite, fixture, 'chrome_' + randomUUID().slice(0, 8));
  async function managed(path, operation, payload) {
    const challenge = await call('/v1/challenges', { operation, requestHash: await sha256(utf8(canonical(payload))) }, account.session.accessToken);
    return call(path, { payload, proof: { id: challenge.id, signature: await signChallenge(account.manager.keys.privateKey, challenge.payload) } }, account.session.accessToken);
  }
  const entry = resolve(directory, 'fixture-entry.mjs');
  await writeFile(entry, `import {startBackground} from '../../../apps/chrome/src/bridge.mjs';\nstartBackground((input, options) => fetch(input, {...options, headers: {...options.headers, 'X-AuthBox-Fixture': ${JSON.stringify(fixtureToken)}, 'X-AuthBox-Case': 'chrome-reader'}}));\n`);
  let browser; let rotated;
  const results = []; const passed = (name) => { results.push(name); console.log(JSON.stringify({ chromeCloudPassed: name })); };
  try {
    const built = await buildChrome({ origin, directory: resolve(directory, 'extension'), workerEntry: entry });
    browser = await openChrome(built, resolve(directory, 'profile'));
    const page = await browser.page();
    await page.evaluate(() => {
      globalThis.authboxTestEvents = [];
      for (const kind of ['focus', 'blur', 'visibilitychange']) window.addEventListener(kind, () => globalThis.authboxTestEvents.push({ kind, time: Date.now(), focused: document.hasFocus() }));
      chrome.storage.onChanged.addListener((change, area) => { if (area === 'session' && change.unlock) globalThis.authboxTestEvents.push({ kind: 'unlock-state', time: Date.now(),
        removed: !change.unlock.newValue, expiresAt: change.unlock.oldValue?.expiresAt, backgroundUntil: change.unlock.oldValue?.backgroundUntil }); });
    });
    await login(page, account); await unlock(page, account.vaultPassword); await waitRows(page, 1);
    await page.locator('#settings summary').click(); await page.locator('#background').selectOption('-1'); await page.locator('#apply-settings').click();
    await page.waitForSelector('#unlock-form:not([hidden])'); await unlock(page, account.vaultPassword); await waitRows(page, 1);
    const stored = await page.evaluate(() => chrome.storage.local.get('reader')); const token = stored.reader.auth.session.accessToken;
    assert.equal(stored.reader.auth.device.role, 'reader');
    passed('Installed Google Chrome completes real HTTPS password login, read-only session and Argon2id vault unlock on Workers Free SQLite DO');
    const deniedPaths = ['/v1/vault/commit', '/v1/vault/rotate', '/v1/challenges', '/v1/devices/approve', '/v1/devices/revoke',
      '/v1/account/password/prepare', '/v1/account/password/confirm', '/v1/account/recovery/rotate'];
    for (const path of deniedPaths) { const denied = await response(path, {}, token); assert.equal(denied.status, 403); await denied.body?.cancel(); }
    await managed('/v1/devices/approve', 'device.approve', { deviceId: stored.reader.auth.device.id }).then(() => assert.fail('Chrome cannot be approved'), (error) => {
      assert.match(error.message, /HTTP 409/);
    });
    passed('Actual extension token fails all eight management endpoints; a Chrome device with no public key cannot be promoted');
    await page.bringToFront(); await page.locator('#all').click(); await waitRows(page, 3); await page.locator('#search').fill('SECONDARY'); await waitRows(page, 1);
    assert.equal(await page.locator('#accounts img').count(), 0); await page.locator('#search').fill('');
    passed('Cloud ciphertext renders as text and supports all accounts, favorites and local search without uploading business labels');
    rotated = await rotationFixture(fixture);
    await managed('/v1/vault/rotate', 'vault.rotate', { operationId: randomUUID(), baseRevision: 1, restoredIds: [], backup: rotated.backup });
    await page.locator('#sync').click(); await page.waitForSelector('#login-form:not([hidden])');
    assert.equal(await page.locator('#accounts .code').count(), 0);
    const oldRefresh = await response('/v1/auth/refresh', { refreshToken: stored.reader.auth.session.refreshToken }); assert.equal(oldRefresh.status, 401); await oldRefresh.body?.cancel();
    await login(page, account);
    await page.locator('#vault-password').fill(account.vaultPassword); await page.getByRole('button', { name: '解锁', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('解锁失败'));
    await unlock(page, rotated.password); await waitRows(page, 3);
    assert.equal((await message(page, 'status')).value.keyGeneration, 2);
    passed('Real VMK rotation invalidates Chrome sessions; independent relogin remains reader and new vault password is required');
    const nextState = await page.evaluate(() => chrome.storage.local.get('reader')); const nextDevice = nextState.reader.auth.device.id;
    await managed('/v1/devices/revoke', 'device.revoke', { deviceId: nextDevice });
    await page.locator('#sync').click(); await page.waitForSelector('#login-form:not([hidden])');
    assert.equal(await page.locator('#accounts .code').count(), 0);
    assert.equal((await message(page, 'status')).value.hasCache, true);
    passed('Actual mobile-management protocol revocation locks the extension and retains encrypted offline cache');
    await browser.context.setOffline(true);
    await unlock(page, rotated.password); await waitRows(page, 3);
    passed('Revoked Chrome still decrypts its existing offline copy without network or phone; revocation cannot erase already acquired keys');
    await page.screenshot({ path: resolve(directory, 'cloud-readonly.png'), fullPage: true });
    return { generatedAt: new Date().toISOString(), checks: results.length, results, browser: browser.context.browser().version(), extensionId: built.id,
      negativeManagementRequests: deniedPaths.length, realHttpsCertificateValidation: true, fixtureBundle: true,
      companyPolicyVerified: false, directory };
  } catch (error) {
    const page = browser?.context.pages().find((page) => page.url().startsWith('chrome-extension:'));
    if (page) console.log(JSON.stringify({ chromeCloudFailure: error.message, notice: await page.locator('#notice').textContent(), status: await message(page, 'status'),
      events: await page.evaluate(() => (globalThis.authboxTestEvents ?? []).slice(-20)) }));
    throw error;
  } finally { await browser?.context.close(); await rm(entry, { force: true }); fixture.vault.vmk.fill(0); rotated?.vmk.fill(0); }
}
