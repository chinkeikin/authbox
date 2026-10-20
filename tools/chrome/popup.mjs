import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

export async function nativePopup(browser, page, screenshot) {
  await page.evaluate(() => chrome.action.openPopup());
  let target;
  for (let index = 0; index < 50 && !target; index++) {
    target = (await browser.cdp.send('Target.getTargets', { filter: [{}] })).targetInfos.find((item) => item.type === 'page' && item.url.endsWith('/index.html?popup=1'));
    if (!target) await new Promise((resolve) => setTimeout(resolve, 50));
  }
  assert.ok(target, 'Native Chrome action popup was not created');
  const { sessionId } = await browser.cdp.send('Target.attachToTarget', { targetId: target.targetId, flatten: false });
  let sequence = 0; const callbacks = new Map();
  const receive = (event) => {
    if (event.sessionId !== sessionId) return;
    const value = JSON.parse(event.message); const pending = callbacks.get(value.id);
    if (!pending) return; callbacks.delete(value.id); clearTimeout(pending.timer);
    if (value.error) pending.reject(new Error(value.error.message)); else pending.resolve(value.result);
  };
  browser.cdp.on('Target.receivedMessageFromTarget', receive);
  async function send(method, params = {}) {
    const id = ++sequence;
    const result = new Promise((resolve, reject) => { callbacks.set(id, { resolve, reject, timer: setTimeout(() => reject(new Error('Popup CDP timeout')), 10000) }); });
    await browser.cdp.send('Target.sendMessageToTarget', { sessionId, message: JSON.stringify({ id, method, params }) });
    return result;
  }
  try {
    for (let index = 0; index < 50; index++) {
      const value = await send('Runtime.evaluate', { expression: 'Boolean(document.querySelector("#app") && document.body.classList.contains("popup"))', returnByValue: true });
      if (value.result.value) break; await new Promise((resolve) => setTimeout(resolve, 50));
      assert.ok(index < 49, 'Popup script did not run');
    }
    await send('Runtime.evaluate', { expression: 'document.fonts.ready', awaitPromise: true });
    const image = await send('Page.captureScreenshot'); await writeFile(screenshot, Buffer.from(image.data, 'base64'));
    await browser.cdp.send('Target.closeTarget', { targetId: target.targetId });
  } finally {
    browser.cdp.off('Target.receivedMessageFromTarget', receive);
    for (const pending of callbacks.values()) { clearTimeout(pending.timer); pending.reject(new Error('Popup closed')); }
    await browser.cdp.send('Target.detachFromTarget', { sessionId }).catch(() => {});
  }
}
