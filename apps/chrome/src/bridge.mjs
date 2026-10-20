import { ReaderController, ReaderError } from './reader.mjs';

export function startBackground(request = fetch) {
  const ready = Promise.all(['local', 'session'].map((area) => chrome.storage[area].setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' })));
  const views = new Set();
  const controller = new ReaderController({ storage: chrome.storage, request, alarm: async (when) => {
    if (when === null) await chrome.alarms.clear('authbox-lock');
    else await chrome.alarms.create('authbox-lock', { when });
  } });
  const allowed = (sender) => {
    if (sender.id !== chrome.runtime.id || !sender.url) return false;
    const url = new URL(sender.url);
    return url.origin === `chrome-extension://${chrome.runtime.id}` && url.pathname === '/index.html' && !sender.frameId;
  };
  const hidden = () => {
    if (views.size && [...views].some((view) => view.active)) return;
    controller.invalidate();
    ready.then(() => controller.dispatch('inactive')).catch(() => {});
  };
  chrome.runtime.onConnect.addListener((port) => {
    if (port.name !== 'authbox-view' || !allowed(port.sender)) { port.disconnect(); return; }
    const view = { active: false }; views.add(view);
    port.onMessage.addListener((message) => {
      if (typeof message?.active !== 'boolean') return;
      view.active = message.active;
      if (!view.active) hidden(); else ready.then(() => controller.dispatch('active')).catch(() => {});
    });
    port.onDisconnect.addListener(() => { views.delete(view); hidden(); });
  });
  chrome.runtime.onMessage.addListener((message, sender, reply) => {
    if (!allowed(sender)) { reply({ ok: false, code: 'untrusted_sender', message: '请求来源不受信任' }); return false; }
    if (message?.type === 'lock') controller.invalidate();
    if (message?.type === 'login' && message.value?.origin !== AUTHBOX_API_ORIGIN) {
      reply({ ok: false, code: 'wrong_origin', message: '服务地址与安装包配置不一致' }); return false;
    }
    ready.then(() => controller.dispatch(message?.type, message?.value)).then((value) => reply({ ok: true, value }), (error) => {
      reply({ ok: false, code: error instanceof ReaderError ? error.code : 'invalid_data',
        message: error instanceof ReaderError ? error.message : '数据校验失败，原加密缓存已保留' });
    });
    return true;
  });
  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === 'authbox-lock') ready.then(() => controller.dispatch('status')).catch(() => {});
  });
  chrome.runtime.onStartup.addListener(() => { controller.invalidate(); ready.then(() => controller.dispatch('lock')).catch(() => {}); });
  chrome.runtime.onInstalled.addListener(() => { controller.invalidate(); ready.then(() => controller.dispatch('lock')).catch(() => {}); });
}
