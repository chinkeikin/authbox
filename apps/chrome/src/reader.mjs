import { authenticateBackup, inspectBackup, restoreBackup } from '../../../packages/protocol/src/backup.mjs';
import { validateVaultEntities, username, loginPassword } from '../../../packages/protocol/src/business.mjs';
import { base64url, canonical, fields, unbase64url, uuid, utf8 } from '../../../packages/protocol/src/encoding.mjs';
import { generateTotp } from '../../../packages/protocol/src/totp.mjs';

const MAX_BYTES = 1024 * 1024;
const defaults = () => ({ version: 1, account: null, auth: null, backup: null, candidate: null, refreshUncertain: false,
  preferences: { idleMinutes: 5, backgroundSeconds: 0, theme: 'system' },
  sync: { status: 'none', lastSuccessAt: null, observedRevision: null, failures: 0, nextAttempt: 0 } });

export class ReaderError extends Error {
  constructor(code, message, status = 0) { super(message); this.code = code; this.status = status; }
}
const fail = (condition, code, message) => { if (!condition) throw new ReaderError(code, message); };
export function apiOrigin(value) {
  const url = new URL(value);
  fail(url.protocol === 'https:' && url.origin === value && !url.username && !url.password && !url.port,
    'invalid_origin', '服务地址必须是无路径和端口的 HTTPS 地址');
  return value;
}

export function readerAuth(value, expectedUsername, now) {
  fields(value, ['user', 'device', 'session']); fields(value.user, ['id', 'username']); fields(value.device, ['id', 'role']);
  fields(value.session, ['accessToken', 'refreshToken', 'accessExpiresAt', 'refreshExpiresAt', 'absoluteExpiresAt']);
  uuid(value.user.id); uuid(value.device.id);
  fail(value.user.username === expectedUsername && value.device.role === 'reader', 'reader_required', '服务返回了非只读身份，请使用正确的账号服务');
  let sessionId;
  for (const [name, prefix] of [['accessToken', 'ab1a'], ['refreshToken', 'ab1r']]) {
    const parts = value.session[name]?.split('.') ?? [];
    fail(parts.length === 3 && parts[0] === prefix, 'invalid_session', '会话格式不正确');
    uuid(parts[1]); unbase64url(parts[2], 32);
    sessionId ??= parts[1]; fail(sessionId === parts[1], 'invalid_session', '会话身份不一致');
  }
  const s = value.session;
  fail([s.accessExpiresAt, s.refreshExpiresAt, s.absoluteExpiresAt].every((time) => Number.isSafeInteger(time) && time > now)
    && s.accessExpiresAt <= s.refreshExpiresAt && s.refreshExpiresAt <= s.absoluteExpiresAt, 'invalid_session', '会话期限不正确');
  return value;
}

export class ReaderController {
  constructor({ storage, request = fetch, now = Date.now, alarm = async () => {} }) {
    this.storage = storage; this.request = request; this.now = now; this.alarm = alarm;
    this.epoch = 0; this.opened = null; this.tail = Promise.resolve(); this.state = null;
  }
  async initialize() {
    if (this.state) return;
    const stored = (await this.storage.local.get('reader')).reader;
    fail(!stored || stored.version === 1, 'unsupported_state', '本机数据版本不受支持，请更新扩展');
    this.state = stored ?? defaults();
  }
  async save(next) { await this.storage.local.set({ reader: next }); this.state = structuredClone(next); }
  dispatch(type, value = {}) {
    if (type === 'lock') this.invalidate();
    const run = async () => {
      await this.initialize();
      switch (type) {
        case 'status': return this.status();
        case 'login': return this.login(value);
        case 'unlock': return this.unlock(value);
        case 'list': return this.list(value);
        case 'copy': return this.copy(value);
        case 'touch': return this.touch();
        case 'sync': return this.synchronize(Boolean(value.manual));
        case 'lock': await this.clearUnlock(); return this.status();
        case 'inactive': return this.inactive();
        case 'active': return this.active();
        case 'preferences': return this.preferences(value);
        case 'logout': return this.logout();
        case 'forget': return this.forget(value);
        default: throw new ReaderError('unsupported_action', '扩展只支持查看与拉取');
      }
    };
    const result = this.tail.then(run, run); this.tail = result.catch(() => {}); return result;
  }
  invalidate() { this.epoch++; this.opened?.vmk.fill(0); this.opened = null; }
  async clearUnlock() { this.invalidate(); await this.storage.session.remove('unlock'); await this.alarm(null); }
  async unlockState() {
    const value = (await this.storage.session.get('unlock')).unlock;
    if (!value) return null;
    const now = this.now();
    if (now < value.lastSeenAt || now >= value.expiresAt || (value.backgroundUntil !== null && now >= value.backgroundUntil)
      || !this.state.backup || value.digest !== (await inspectBackup(this.state.backup)).digest) {
      await this.clearUnlock(); return null;
    }
    value.lastSeenAt = now;
    await this.storage.session.set({ unlock: value }); return value;
  }
  async status() {
    const unlock = await this.unlockState();
    const backup = this.state.backup ? await inspectBackup(this.state.backup) : null;
    const candidate = this.state.candidate ? await inspectBackup(this.state.candidate) : null;
    return { account: this.state.account, deviceId: this.state.auth?.device.id ?? null,
      needsLogin: !this.state.auth || this.state.refreshUncertain, unlocked: Boolean(unlock), unlockId: unlock?.id ?? null,
      expiresAt: unlock?.expiresAt ?? null, revision: backup?.revision ?? null, keyGeneration: backup?.keyGeneration ?? null,
      candidateRevision: candidate?.revision ?? null, hasCache: Boolean(backup), hasCandidate: Boolean(candidate),
      preferences: this.state.preferences, sync: this.state.sync };
  }
  async network(path, body = null, token = null) {
    fail(['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/logout', '/v1/vault/head', '/v1/vault/snapshot'].includes(path.split('?')[0]),
      'forbidden_route', '扩展不允许调用管理接口');
    const origin = this.state.account?.origin;
    fail(origin, 'login_required', '请先登录'); apiOrigin(origin);
    let response;
    try {
      response = await this.request(origin + path, { method: body ? 'POST' : 'GET', redirect: 'error', cache: 'no-store', credentials: 'omit',
        headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: body ? canonical(body) : undefined, signal: AbortSignal.timeout(30000) });
    } catch { throw new ReaderError('network', '网络不可用，已验证的加密缓存仍可离线解锁'); }
    const reader = response.body?.getReader(); const chunks = []; let length = 0;
    try {
      if (reader) while (true) {
        const item = await reader.read(); if (item.done) break;
        length += item.value.length; fail(length <= MAX_BYTES, 'response_size', '服务响应超过大小限制，原缓存已保留'); chunks.push(item.value);
      }
    } finally { await reader?.cancel().catch(() => {}); }
    const bytes = new Uint8Array(length); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (!response.ok) {
      let code; try { code = JSON.parse(text).error; } catch {}
      if (!/^[a-z_]{1,64}$/.test(code ?? '')) code = 'request_failed';
      throw new ReaderError(code, response.status === 401 ? '登录已失效，请重新登录；离线缓存已保留'
        : response.status === 403 ? '服务拒绝访问，请确认设备授权和服务配置'
          : response.status === 429 ? '请求过于频繁，请稍后重试' : '服务暂时无法完成请求，原缓存已保留', response.status);
    }
    return text;
  }
  async login({ origin, user, password }) {
    apiOrigin(origin); username(user); loginPassword(password);
    const owner = this.state.account;
    fail(!owner || (owner.origin === origin && owner.username === user), 'account_switch', '更换账号或服务前，请先清除本机数据');
    const epoch = this.epoch;
    const prior = structuredClone(this.state);
    this.state.account = { origin, username: user, userId: owner?.userId ?? null };
    try {
      const auth = readerAuth(JSON.parse(await this.network('/v1/auth/login', { username: user, password, deviceName: 'AuthBox Chrome', publicKey: null })), user, this.now());
      fail(epoch === this.epoch, 'cancelled', '界面已锁定，请重新登录');
      fail(!owner?.userId || owner.userId === auth.user.id, 'account_identity', '账号身份已变化，请先清除旧的本机数据');
      await this.clearUnlock();
      await this.save({ ...this.state, account: { origin, username: user, userId: auth.user.id }, auth, refreshUncertain: false,
        sync: { ...this.state.sync, status: 'ready', failures: 0, nextAttempt: 0 } });
      return this.status();
    } catch (error) { this.state = prior; throw error; }
  }
  async access() {
    fail(this.state.auth && !this.state.refreshUncertain, 'login_required', '请重新登录；加密缓存仍可离线使用');
    const auth = this.state.auth;
    if (auth.session.accessExpiresAt > this.now() + 30000) return auth.session.accessToken;
    await this.save({ ...this.state, refreshUncertain: true });
    try {
      const next = readerAuth(JSON.parse(await this.network('/v1/auth/refresh', { refreshToken: auth.session.refreshToken })), this.state.account.username, this.now());
      fail(next.user.id === auth.user.id && next.device.id === auth.device.id, 'invalid_session', '刷新身份不一致');
      await this.save({ ...this.state, auth: next, refreshUncertain: false }); return next.session.accessToken;
    } catch {
      await this.clearUnlock(); throw new ReaderError('refresh_uncertain', '会话刷新未确认，请重新登录；不会重复使用旧刷新令牌');
    }
  }
  async checkRemote(header) {
    const old = this.state.backup ? await inspectBackup(this.state.backup) : null;
    const pending = this.state.candidate ? await inspectBackup(this.state.candidate) : null;
    for (const prior of [old, pending].filter(Boolean)) {
      fail(prior.vaultId === header.vaultId && header.revision >= prior.revision && header.keyGeneration >= prior.keyGeneration
        && (header.revision !== prior.revision || header.digest === prior.digest), 'snapshot_regression', '云端版本回退或身份变化，原缓存已保留');
    }
  }
  async synchronize(manual) {
    const now = this.now();
    if (!manual && now < this.state.sync.nextAttempt) return this.status();
    const epoch = this.epoch;
    try {
      const token = await this.access();
      const head = JSON.parse(await this.network('/v1/vault/head', null, token)); fields(head, ['vaultId', 'revision', 'keyGeneration']); uuid(head.vaultId);
      fail(Number.isSafeInteger(head.revision) && head.revision > 0 && Number.isSafeInteger(head.keyGeneration) && head.keyGeneration > 0,
        'invalid_head', '云端版本格式不正确');
      const current = this.state.candidate ?? this.state.backup;
      const prior = current ? await inspectBackup(current) : null;
      if (prior) fail(prior.vaultId === head.vaultId && head.revision >= prior.revision && head.keyGeneration >= prior.keyGeneration
        && (head.revision !== prior.revision || head.keyGeneration === prior.keyGeneration), 'snapshot_regression', '云端版本回退或身份变化，原缓存已保留');
      if (!prior || prior.revision !== head.revision) {
        const encoded = await this.network(`/v1/vault/snapshot?revision=${head.revision}`, null, await this.access());
        const header = await inspectBackup(encoded); await this.checkRemote(header);
        fail(header.vaultId === head.vaultId && header.revision === head.revision && header.keyGeneration === head.keyGeneration,
          'invalid_snapshot', '快照与云端版本不一致，原缓存已保留');
        const unlock = epoch === this.epoch ? await this.unlockState() : null;
        const old = this.state.backup ? await inspectBackup(this.state.backup) : null;
        if (unlock && old.keyGeneration === header.keyGeneration) {
          const key = unbase64url(unlock.vmk, 32);
          let opened;
          try { opened = await authenticateBackup(encoded, key); validateVaultEntities(opened.entities); }
          finally { key.fill(0); }
          try {
            if (epoch === this.epoch && this.now() < unlock.expiresAt && (unlock.backgroundUntil === null || this.now() < unlock.backgroundUntil)) {
              await this.save({ ...this.state, backup: encoded, candidate: null });
              unlock.digest = header.digest; await this.storage.session.set({ unlock });
              if (epoch !== this.epoch) await this.clearUnlock();
              else { this.opened?.vmk.fill(0); this.opened = { ...opened, digest: header.digest, unlockId: unlock.id }; opened = null; }
            } else await this.save({ ...this.state, candidate: encoded });
          } finally { opened?.vmk.fill(0); }
        } else {
          if (unlock && old.keyGeneration !== header.keyGeneration) await this.clearUnlock();
          await this.save({ ...this.state, candidate: encoded });
        }
      }
      await this.save({ ...this.state, sync: { status: this.state.candidate ? 'unlock_required' : 'current', lastSuccessAt: this.now(),
        observedRevision: head.revision, failures: 0, nextAttempt: this.now() + 60000 } });
      return this.status();
    } catch (error) {
      const authFailure = error.status === 401 || error.status === 403 || error.code === 'refresh_uncertain';
      if (authFailure) { await this.clearUnlock(); await this.save({ ...this.state, refreshUncertain: true }); }
      const retry = error.code === 'network' || error.status >= 500 || [408, 429].includes(error.status);
      const failures = Math.min(this.state.sync.failures + 1, 8);
      await this.save({ ...this.state, sync: { ...this.state.sync, status: authFailure ? 'login_required' : retry ? 'offline' : 'invalid', failures,
        nextAttempt: retry ? this.now() + Math.min(300000, 5000 * 2 ** (failures - 1)) + Math.floor(Math.random() * 1000) : Number.MAX_SAFE_INTEGER } });
      throw error;
    }
  }
  async unlock({ password, cached = false }) {
    const encoded = cached ? this.state.backup : this.state.candidate ?? this.state.backup;
    fail(encoded, 'no_cache', '请先登录并拉取保险库');
    const epoch = this.epoch; let opened;
    try {
      opened = await restoreBackup(encoded, { purpose: 'password', secret: password }); validateVaultEntities(opened.entities);
      fail(epoch === this.epoch, 'cancelled', '界面已锁定，请重新解锁');
      const header = await inspectBackup(encoded);
      const now = this.now();
      const value = { id: crypto.randomUUID(), vmk: base64url(opened.vmk), digest: header.digest,
        expiresAt: now + this.state.preferences.idleMinutes * 60000, lastSeenAt: now, backgroundUntil: null };
      if (!cached) await this.save({ ...this.state, backup: encoded, candidate: null });
      await this.storage.session.set({ unlock: value });
      if (epoch !== this.epoch) { await this.clearUnlock(); throw new ReaderError('cancelled', '界面已锁定，请重新解锁'); }
      this.opened?.vmk.fill(0); this.opened = { ...opened, digest: header.digest, unlockId: value.id }; opened = null;
      await this.alarm(value.expiresAt); return this.status();
    } catch (error) {
      if (error instanceof ReaderError) throw error;
      throw new ReaderError('unlock_failed', '解锁失败：密码不正确或密文损坏，原缓存已保留');
    } finally { opened?.vmk.fill(0); }
  }
  async open() {
    const value = await this.unlockState(); fail(value, 'locked', '保险库已锁定，请重新解锁');
    if (!this.opened || this.opened.digest !== value.digest || this.opened.unlockId !== value.id) {
      const key = unbase64url(value.vmk, 32);
      try {
        const epoch = this.epoch;
        const opened = await authenticateBackup(this.state.backup, key); validateVaultEntities(opened.entities);
        if (epoch !== this.epoch) { opened.vmk.fill(0); throw new ReaderError('locked', '保险库已锁定'); }
        this.opened = { ...opened, digest: value.digest, unlockId: value.id };
      } finally { key.fill(0); }
    }
    return { vault: this.opened, unlock: value };
  }
  async list({ query = '', group = 'all', tag = 'all', favorites = null, limit = 50 } = {}) {
    fail(typeof query === 'string' && query.length <= 1024 && typeof group === 'string' && typeof tag === 'string'
      && (favorites === null || typeof favorites === 'boolean') && Number.isSafeInteger(limit) && limit >= 1 && limit <= 10000, 'invalid_filter', '筛选条件不正确');
    const epoch = this.epoch; const { vault, unlock } = await this.open();
    const groups = vault.entities.filter((item) => item.metadata.type === 'group').sort((a, b) => a.payload.order - b.payload.order || a.metadata.id.localeCompare(b.metadata.id));
    const tags = vault.entities.filter((item) => item.metadata.type === 'tag').sort((a, b) => a.payload.name.localeCompare(b.payload.name));
    const groupNames = new Map(groups.map((item) => [item.metadata.id, item.payload.name]));
    const tagNames = new Map(tags.map((item) => [item.metadata.id, item.payload.name]));
    const defaultFavorites = vault.entities.find((item) => item.metadata.type === 'settings')?.payload.defaultView === 'favorites';
    const onlyFavorites = favorites ?? defaultFavorites;
    const normalized = query.trim().toLocaleLowerCase();
    const accounts = vault.entities.filter(({ metadata, payload: p }) => metadata.type === 'account' && p.deletedAt === null
      && (!onlyFavorites || p.favorite) && (group === 'all' || (group === 'none' ? p.groupId === null : p.groupId === group))
      && (tag === 'all' || p.tagIds.includes(tag))
      && (!normalized || [p.issuer, p.name, p.account, p.notes, ...p.tagIds.map((id) => tagNames.get(id))].join('\n').toLocaleLowerCase().includes(normalized)))
      .sort((a, b) => a.payload.order - b.payload.order || a.metadata.id.localeCompare(b.metadata.id));
    const timestamp = this.now();
    const rows = accounts.slice(0, limit).map(({ metadata, payload: p }) => ({ id: metadata.id, title: p.name || p.issuer || p.account,
      issuer: p.issuer, account: p.account, favorite: p.favorite, group: groupNames.get(p.groupId) ?? '未分组',
      tags: p.tagIds.map((id) => tagNames.get(id)), ...generateTotp(p, timestamp), period: p.period }));
    fail(epoch === this.epoch && this.now() < unlock.expiresAt, 'locked', '保险库已锁定');
    return { rows, total: accounts.length, groups: groups.map((item) => ({ id: item.metadata.id, name: item.payload.name })),
      tags: tags.map((item) => ({ id: item.metadata.id, name: item.payload.name })), favorites: onlyFavorites,
      unlockId: unlock.id, expiresAt: unlock.expiresAt, timestamp };
  }
  async copy({ id }) {
    uuid(id); const epoch = this.epoch; const { vault, unlock } = await this.open();
    const account = vault.entities.find((item) => item.metadata.type === 'account' && item.metadata.id === id && item.payload.deletedAt === null);
    fail(account && epoch === this.epoch, 'missing_account', '条目已不可用，请刷新列表');
    const code = generateTotp(account.payload, this.now());
    fail(this.now() < unlock.expiresAt, 'locked', '保险库已锁定');
    return { ...code, unlockId: unlock.id, expiresAt: unlock.expiresAt };
  }
  async touch() {
    const unlock = await this.unlockState();
    if (unlock) {
      unlock.expiresAt = this.now() + this.state.preferences.idleMinutes * 60000; unlock.backgroundUntil = null;
      await this.storage.session.set({ unlock }); await this.alarm(unlock.expiresAt);
    }
    return this.status();
  }
  async inactive() {
    const unlock = await this.unlockState();
    if (!unlock) return this.status();
    const seconds = this.state.preferences.backgroundSeconds;
    if (seconds === 0) { await this.clearUnlock(); return this.status(); }
    if (seconds > 0) {
      unlock.backgroundUntil = Math.min(unlock.backgroundUntil ?? Number.MAX_SAFE_INTEGER, this.now() + seconds * 1000);
      await this.storage.session.set({ unlock }); await this.alarm(Math.min(unlock.expiresAt, unlock.backgroundUntil));
    }
    return this.status();
  }
  async active() {
    const unlock = await this.unlockState();
    if (unlock) { unlock.backgroundUntil = null; await this.storage.session.set({ unlock }); await this.alarm(unlock.expiresAt); }
    return this.status();
  }
  async preferences(value) {
    fields(value, ['idleMinutes', 'backgroundSeconds', 'theme']);
    fail([1, 5, 15].includes(value.idleMinutes) && [0, 30, -1].includes(value.backgroundSeconds)
      && ['system', 'light', 'dark'].includes(value.theme), 'invalid_preferences', '本机设置不正确');
    await this.clearUnlock(); await this.save({ ...this.state, preferences: value }); return this.status();
  }
  async logout() {
    await this.clearUnlock(); let confirmed = false;
    try { if (this.state.auth && !this.state.refreshUncertain) { await this.network('/v1/auth/logout', {}, await this.access()); confirmed = true; } } catch {}
    await this.save({ ...this.state, auth: null, refreshUncertain: false, sync: { ...this.state.sync, status: 'signed_out' } });
    return { ...await this.status(), logoutConfirmed: confirmed };
  }
  async forget({ confirmed }) {
    fail(confirmed === true, 'confirmation', '请确认清除本机缓存与登录状态');
    await this.logout(); await this.storage.local.remove('reader'); this.state = defaults(); return this.status();
  }
}
