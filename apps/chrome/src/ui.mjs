const root = document.querySelector('#app');
const element = (tag, text, className) => {
  const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node;
};
const button = (text, action, className = 'secondary') => {
  const node = element('button', text, className); node.type = 'button'; node.addEventListener('click', action); return node;
};
const field = (label, type, name) => {
  const wrapper = element('label', undefined, 'field'); wrapper.append(element('span', label));
  const input = element('input'); input.type = type; input.name = name; input.id = name; input.required = true;
  input.autocomplete = type === 'password' ? 'off' : 'username'; wrapper.append(input); return { wrapper, input };
};
let status = null; let busy = false; let epoch = 0; let active = false; let port; let rendering = false; let nextSync = 0; let lastTouch = 0;
let filters = { query: '', group: 'all', tag: 'all', favorites: null, limit: 50 }; let listingId = null; let selectionKey = '';
const call = async (type, value) => {
  const response = await chrome.runtime.sendMessage({ type, value });
  if (!response?.ok) { const error = new Error(response?.message ?? '扩展暂不可用，请重新打开'); error.code = response?.code; throw error; }
  return response.value;
};
const banner = element('p', '', 'notice'); banner.setAttribute('role', 'status'); banner.id = 'notice';
const announce = (text = '', bad = false) => { banner.textContent = text; banner.classList.toggle('error', bad); };
const heading = element('header');
const brand = element('div', undefined, 'brand'); brand.append(element('span', 'A', 'mark'), element('h1', 'AuthBox'), element('span', '只读', 'badge'));
const actions = element('div', undefined, 'actions');
const expand = button('完整窗口', () => chrome.runtime.openOptionsPage(), 'quiet'); expand.id = 'expand';
const lock = button('锁定', () => { hide(); call('lock').then(() => announce('已锁定保险库'), () => announce('正在锁定，请重新打开窗口', true)).finally(refresh); }); lock.id = 'lock';
actions.append(expand, lock); heading.append(brand, actions);
const identity = element('p', '', 'identity'); identity.id = 'identity';
const syncBar = element('div', undefined, 'sync-bar');
const syncText = element('span'); syncText.id = 'sync-status';
const syncButton = button('刷新同步', () => synchronize(true)); syncButton.id = 'sync'; syncBar.append(syncText, syncButton);
const loginForm = element('form', undefined, 'panel'); loginForm.id = 'login-form';
const accountField = field('平台账号', 'text', 'username'); accountField.input.pattern = '[a-z][a-z0-9_]{2,31}'; accountField.input.maxLength = 32;
const loginField = field('平台登录密码', 'password', 'login-password'); loginField.input.maxLength = 256;
const loginButton = element('button', '登录只读设备', 'primary'); loginButton.type = 'submit';
loginForm.append(element('h2', '连接你的保险库'), element('p', '使用自己的平台账号登录，保险库密码在下一步输入。', 'muted'), accountField.wrapper, loginField.wrapper, loginButton);
loginForm.addEventListener('submit', (event) => {
  event.preventDefault(); const user = accountField.input.value; let password = loginField.input.value; loginField.input.value = '';
  run(async () => { await call('login', { origin: AUTHBOX_API_ORIGIN, user, password }); password = ''; await synchronize(true, true); }, '登录成功，请解锁保险库');
});
const unlockForm = element('form', undefined, 'panel'); unlockForm.id = 'unlock-form';
const vaultField = field('保险库密码', 'password', 'vault-password'); vaultField.input.maxLength = 1024;
const cached = element('input'); cached.type = 'checkbox'; cached.id = 'cached';
const cachedLabel = element('label', undefined, 'check'); cachedLabel.append(cached, element('span', '使用已验证的离线缓存'));
const candidateText = element('p', '', 'muted'); candidateText.id = 'candidate';
const unlockButton = element('button', '解锁', 'primary'); unlockButton.type = 'submit';
unlockForm.append(element('h2', '解锁保险库'), candidateText, vaultField.wrapper, cachedLabel, unlockButton);
unlockForm.addEventListener('submit', (event) => {
  event.preventDefault(); let password = vaultField.input.value; vaultField.input.value = '';
  run(async () => { const result = await call('unlock', { password, cached: cached.checked }); password = ''; filters.limit = 50; return result; }, '保险库已解锁');
});
const sleeping = element('p', '窗口未激活，验证码已隐藏。', 'panel'); sleeping.id = 'sleeping';
const vault = element('section'); vault.id = 'vault';
const controls = element('div', undefined, 'controls');
const search = element('input'); search.type = 'search'; search.id = 'search'; search.placeholder = '搜索平台、账号、备注或标签'; search.setAttribute('aria-label', '搜索账号'); search.maxLength = 1024; search.autocomplete = 'off';
const favorite = button('收藏', () => { filters.favorites = true; filters.limit = 50; refresh(); }); favorite.id = 'favorites';
const all = button('全部', () => { filters.favorites = false; filters.limit = 50; refresh(); }); all.id = 'all';
const group = element('select'); group.id = 'group'; group.setAttribute('aria-label', '分组筛选');
const tag = element('select'); tag.id = 'tag'; tag.setAttribute('aria-label', '标签筛选');
controls.append(search, favorite, all, group, tag);
const count = element('p', '', 'muted'); count.id = 'count';
const accounts = element('div', undefined, 'accounts'); accounts.id = 'accounts';
const more = button('显示更多', () => { filters.limit = Math.min(10000, filters.limit + 50); refresh(); }); more.id = 'more';
vault.append(controls, count, accounts, more);
search.addEventListener('input', () => { filters.query = search.value; filters.limit = 50; refresh(); });
group.addEventListener('change', () => { filters.group = group.value; filters.limit = 50; refresh(); });
tag.addEventListener('change', () => { filters.tag = tag.value; filters.limit = 50; refresh(); });
const settings = element('details', undefined, 'settings'); settings.id = 'settings'; settings.append(element('summary', '本机设置与登录状态'));
function selectSetting(label, id, choices) {
  const wrapper = element('label', undefined, 'field'); const select = element('select'); select.id = id;
  for (const [value, name] of choices) { const option = element('option', name); option.value = String(value); select.append(option); }
  wrapper.append(element('span', label), select); settings.append(wrapper); return select;
}
const idle = selectSetting('闲置后锁定', 'idle', [[1, '1 分钟'], [5, '5 分钟'], [15, '15 分钟']]);
const background = selectSetting('离开所有窗口后锁定', 'background', [[0, '立即'], [30, '30 秒'], [-1, '按闲置期限']]);
const theme = selectSetting('外观', 'theme', [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']]);
const apply = button('保存本机设置并锁定', () => run(() => call('preferences', { idleMinutes: Number(idle.value), backgroundSeconds: Number(background.value), theme: theme.value }), '本机设置已保存，保险库已锁定')); apply.id = 'apply-settings';
const device = element('p', '', 'muted'); device.id = 'device';
const logout = button('退出登录', () => run(async () => {
  hide(); const result = await call('logout'); announce(result.logoutConfirmed ? '已退出登录，保留加密缓存' : '本机会话已清除，云端注销未确认；可在手机撤销该设备'); return result;
})); logout.id = 'logout';
const forget = button('清除本机数据', () => confirmation.showModal(), 'danger'); forget.id = 'forget';
settings.append(apply, device, logout, forget, element('p', '设置只影响这台浏览器。账号、分组、标签和收藏由手机管理；卸载扩展会删除本机缓存。', 'muted'));
const confirmation = element('dialog'); confirmation.id = 'forget-dialog';
const confirmed = element('input'); confirmed.type = 'checkbox'; confirmed.id = 'forget-confirmed';
const confirmationLabel = element('label', undefined, 'check'); confirmationLabel.append(confirmed, element('span', '确认清除登录状态和本机加密缓存'));
const confirmForget = button('确认清除', () => { if (confirmed.checked) { confirmation.close(); hide(); run(() => call('forget', { confirmed: true }), '本机数据已清除'); } }, 'danger'); confirmForget.disabled = true;
confirmed.addEventListener('change', () => { confirmForget.disabled = !confirmed.checked; });
confirmation.append(element('h2', '清除本机数据'), element('p', '云端保险库不会被修改。重新取码需要登录并下载，或使用其他已有设备。'), confirmationLabel, confirmForget, button('取消', () => confirmation.close()));
confirmation.addEventListener('close', () => { confirmed.checked = false; confirmForget.disabled = true; });
root.replaceChildren(heading, identity, syncBar, banner, loginForm, unlockForm, sleeping, vault, settings, confirmation);
if (new URL(location.href).searchParams.has('popup')) document.body.classList.add('popup');

function hide() {
  epoch++; listingId = null; selectionKey = ''; accounts.replaceChildren(); vault.hidden = true;
  loginField.input.value = ''; vaultField.input.value = ''; search.value = ''; filters.query = '';
}
function setBusy(value) {
  busy = value; root.setAttribute('aria-busy', String(value));
  for (const node of [loginButton, unlockButton, syncButton, apply, logout, forget]) node.disabled = value;
}
async function run(operation, success = '') {
  if (busy) return; setBusy(true); announce();
  try { await operation(); if (success) announce(success); }
  catch (error) { announce(error.message, true); }
  finally { setBusy(false); await refresh(); }
}
async function synchronize(manual, nested = false) {
  const action = async () => { nextSync = Date.now() + 60000; return call('sync', { manual }); };
  if (nested) return action();
  await run(action, manual ? '已检查云端版本' : '');
}
function renderStatus(value) {
  status = value;
  identity.textContent = `${value.account?.username ?? '未登录'} · ${AUTHBOX_API_ORIGIN}`;
  accountField.input.readOnly = Boolean(value.account); if (value.account) accountField.input.value = value.account.username;
  loginForm.hidden = !value.needsLogin; unlockForm.hidden = value.unlocked || (!value.hasCache && !value.hasCandidate);
  cachedLabel.hidden = !(value.hasCache && value.hasCandidate);
  candidateText.textContent = value.hasCandidate ? `云端版本 ${value.candidateRevision} 待解锁验证；原离线缓存已保留。` : `已验证的离线版本 ${value.revision}，无需手机在线。`;
  syncButton.hidden = !value.account || value.needsLogin;
  lock.hidden = !value.unlocked; vault.hidden = !value.unlocked || !active; sleeping.hidden = !value.unlocked || active;
  const labels = { none: '尚未拉取', ready: '等待拉取', current: '已检查', unlock_required: '新版本待解锁', offline: '离线缓存 · 稍后重试', invalid: '数据校验失败 · 缓存已保留', login_required: '需要重新登录', signed_out: '已退出登录' };
  syncText.textContent = `${labels[value.sync.status] ?? '等待检查'}${value.revision ? ` · 本机 v${value.revision}` : ''}`;
  syncText.title = value.sync.lastSuccessAt ? `最后成功检查：${new Date(value.sync.lastSuccessAt).toLocaleString()}` : '尚无成功检查记录';
  if (!settings.open) { idle.value = String(value.preferences.idleMinutes); background.value = String(value.preferences.backgroundSeconds); theme.value = value.preferences.theme; }
  document.documentElement.dataset.theme = value.preferences.theme;
  device.textContent = value.deviceId ? `只读设备 ID：${value.deviceId}` : '当前没有登录会话'; logout.hidden = !value.deviceId;
  if (!value.unlocked) { if (listingId !== null || accounts.childElementCount) hide(); sleeping.hidden = true; }
}
function options(select, rows, initial) {
  const value = select.value; const nodes = initial.map(([id, label]) => { const option = element('option', label); option.value = id; return option; });
  for (const row of rows) { const option = element('option', row.name); option.value = row.id; nodes.push(option); }
  select.replaceChildren(...nodes); if ([...select.options].some((option) => option.value === value)) select.value = value;
}
function renderList(value) {
  listingId = value.unlockId; filters.favorites = value.favorites;
  favorite.classList.toggle('selected', value.favorites); favorite.setAttribute('aria-pressed', String(value.favorites));
  all.classList.toggle('selected', !value.favorites); all.setAttribute('aria-pressed', String(!value.favorites));
  const key = JSON.stringify([value.groups, value.tags]);
  if (key !== selectionKey) { options(group, value.groups, [['all', '全部分组'], ['none', '未分组']]); options(tag, value.tags, [['all', '全部标签']]); selectionKey = key; }
  count.textContent = `显示 ${value.rows.length} / ${value.total} 个账号`;
  const nodes = value.rows.map((row) => {
    const card = element('article', undefined, 'account'); card.dataset.accountId = row.id;
    const info = element('div', undefined, 'account-info'); info.append(element('h3', `${row.favorite ? '★ ' : ''}${row.title}`), element('p', row.account, 'muted'), element('p', [row.group, ...row.tags].join(' · '), 'muted'));
    const timer = element('div', undefined, 'timer'); const progress = element('progress'); progress.max = row.period * 1000; progress.value = row.remainingMs;
    progress.setAttribute('aria-label', '验证码剩余时间'); timer.append(progress, element('span', `${Math.ceil(row.remainingMs / 1000)} 秒`));
    const code = button(row.code, () => copy(row.id), 'code'); code.setAttribute('aria-label', `复制 ${row.title} 的验证码`); code.dataset.id = row.id;
    card.append(info, code, timer); return card;
  });
  if (!nodes.length) nodes.push(element('p', '没有匹配的账号。试试“全部”或调整筛选条件。', 'empty'));
  accounts.replaceChildren(...nodes); more.hidden = value.rows.length >= value.total;
}
async function refresh() {
  if (rendering || busy) return; rendering = true; const requestEpoch = epoch;
  try {
    const value = await call('status'); if (requestEpoch !== epoch) return; renderStatus(value);
    if (value.unlocked && active) {
      const currentEpoch = epoch; const list = await call('list', filters);
      if (currentEpoch === epoch && active && list.unlockId === value.unlockId && Date.now() < list.expiresAt) renderList(list);
    }
  } catch (error) { hide(); announce(error.message, true); }
  finally { rendering = false; }
}
async function copy(id) {
  const requestEpoch = epoch; const expected = listingId;
  try {
    const value = await call('copy', { id });
    if (requestEpoch !== epoch || !active || value.unlockId !== expected || Date.now() >= value.expiresAt || Date.now() >= value.validUntil) return;
    await navigator.clipboard.writeText(value.code); announce('验证码已复制');
  } catch (error) { announce(error.message, true); await refresh(); }
}
function visibility() {
  active = document.visibilityState === 'visible' && document.hasFocus(); if (!active) hide();
  try { port?.postMessage({ active }); } catch {} if (active) refresh();
}
function connect() {
  port = chrome.runtime.connect({ name: 'authbox-view' });
  port.onDisconnect.addListener(() => { port = null; hide(); if (document.visibilityState === 'visible') setTimeout(connect, 250); });
  visibility();
}
document.addEventListener('visibilitychange', visibility); window.addEventListener('focus', visibility); window.addEventListener('blur', visibility);
window.addEventListener('pagehide', () => { hide(); try { port?.postMessage({ active: false }); port?.disconnect(); } catch {} });
for (const name of ['pointerdown', 'keydown', 'wheel']) document.addEventListener(name, (event) => {
  if (event.isTrusted && active && Date.now() - lastTouch >= 1000) { lastTouch = Date.now(); call('touch').catch(() => {}); }
}, { passive: true });
chrome.storage.onChanged.addListener((changes, area) => { if ((area === 'session' && changes.unlock) || (area === 'local' && changes.reader)) refresh(); });
connect();
setInterval(async () => { if (!active || busy) return; await refresh(); if (status && !status.needsLogin && Date.now() >= nextSync) await synchronize(false); }, 1000);
refresh().then(() => { if (status && !status.needsLogin) synchronize(false); });
