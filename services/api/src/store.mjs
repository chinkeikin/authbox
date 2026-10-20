import { accountRecoveryHash, validateRecoveryHashes } from '../../../packages/protocol/src/account-recovery.mjs';
import { authenticationContext as authContext, operationDigest as requestHash } from '../../../packages/protocol/src/api.mjs';
import { loginPassword, username } from '../../../packages/protocol/src/business.mjs';
import { base64url, canonical, fields, randomBytes, sha256, unbase64url, utf8, uuid } from '../../../packages/protocol/src/encoding.mjs';
import { authorize, challengePayload, MANAGEMENT_OPERATIONS, publicDeviceKey, verifyChallenge } from '../../../packages/protocol/src/permissions.mjs';
import { validateCommit, validateTransition } from '../../../packages/protocol/src/sync.mjs';
import { checkPassword, hashPassword } from './password.mjs';
import { ApiError, discardBody, failure, input, json, readJson, requireCondition as need } from './http.mjs';
import { schema } from './schema.mjs';

const DAY = 86400000;
const secretHash = (value) => sha256(utf8(value));

function deviceInput(value) {
  fields(value, ['id', 'name', 'publicKey']);
  uuid(value.id);
  deviceName(value.name);
  publicDeviceKey(value.publicKey);
}

function deviceName(value) {
  if (typeof value !== 'string' || !value.isWellFormed() || !value.trim() || utf8(value).length > 128 || /[\u0000-\u001f\u007f]/.test(value)) throw new Error('Invalid device name');
}

export class InstallationStore {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    this.waiters = [];
    this.locked = false;
    this.invites = JSON.parse(env.REGISTRATION_INVITE_HASHES ?? '[]');
    if (!Array.isArray(this.invites) || this.invites.length > 16 || new Set(this.invites).size !== this.invites.length) throw new Error('Invalid invite configuration');
    for (const hash of this.invites) unbase64url(hash, 32);
    this.sql.exec(schema);
    if (this.sql.exec('SELECT version FROM app_meta').toArray().some((row) => row.version !== 1)) throw new Error('Unsupported database version');
    for (const hash of this.invites) this.sql.exec('INSERT OR IGNORE INTO invitations(hash) VALUES (?)', hash);
  }

  now() { return Date.now(); }
  one(query, ...bindings) { return this.sql.exec(query, ...bindings).toArray()[0]; }
  count(query, ...bindings) { return this.one(query, ...bindings).n; }

  async fetch(request) {
    if (this.waiters.length >= 8) { await discardBody(request); return json({ error: 'busy' }, 429); }
    if (this.locked) await new Promise((resolve) => this.waiters.push(resolve));
    else this.locked = true;
    try { return await this.route(request); }
    catch (error) { await discardBody(request); return failure(error); }
    finally {
      const next = this.waiters.shift();
      if (next) next();
      else this.locked = false;
    }
  }

  rate(keys) {
    const window = Math.floor(this.now() / 60000);
    this.sql.exec('DELETE FROM rate_limits WHERE window < ?', window - 1);
    for (const [key, limit] of keys) {
      const current = this.one('SELECT * FROM rate_limits WHERE key = ?', key);
      need(!current || current.window !== window || current.count < limit, 429, 'rate_limited');
    }
    this.ctx.storage.transactionSync(() => {
      for (const [key] of keys) this.sql.exec('INSERT INTO rate_limits VALUES (?,?,1) ON CONFLICT(key) DO UPDATE SET count = CASE WHEN window = excluded.window THEN count + 1 ELSE 1 END, window = excluded.window', key, window);
    });
  }

  async limitAuth(request, subject) {
    const ip = await secretHash(request.headers.get('CF-Connecting-IP') ?? 'unavailable');
    this.rate([['auth:global', 100], [`auth:ip:${ip}`, 40], [`auth:subject:${await secretHash(subject)}`, 20]]);
  }

  async newSession(userId, deviceId, absolute = this.now() + 90 * DAY, id = crypto.randomUUID()) {
    const accessToken = `ab1a.${id}.${base64url(randomBytes(32))}`;
    const refreshToken = `ab1r.${id}.${base64url(randomBytes(32))}`;
    return { id, userId, deviceId, accessToken, refreshToken, accessHash: await secretHash(accessToken), refreshHash: await secretHash(refreshToken),
      accessExpires: Math.min(this.now() + 600000, absolute), refreshExpires: Math.min(this.now() + 30 * DAY, absolute), absolute };
  }

  saveSession(session) {
    this.sql.exec('DELETE FROM refresh_used WHERE expires_at <= ?', this.now());
    this.sql.exec('DELETE FROM sessions WHERE absolute_expires <= ?', this.now());
    need(this.count('SELECT COUNT(*) AS n FROM sessions WHERE user_id = ? AND revoked = 0', session.userId) < 64, 409, 'session_limit');
    need(this.count('SELECT COUNT(*) AS n FROM sessions WHERE user_id = ?', session.userId) < 256, 409, 'session_archive_limit');
    this.sql.exec('INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,0)', session.id, session.userId, session.deviceId,
      session.accessHash, session.accessExpires, session.refreshHash, session.refreshExpires, session.absolute);
  }

  sessionResponse(user, device, session) {
    return { user: { id: user.id, username: user.username }, device: { id: device.id, role: device.role }, session: {
      accessToken: session.accessToken, refreshToken: session.refreshToken, accessExpiresAt: session.accessExpires,
      refreshExpiresAt: session.refreshExpires, absoluteExpiresAt: session.absolute } };
  }

  live(sessionId) {
    const principal = this.one('SELECT s.*, d.role, d.public_key, d.revoked AS device_revoked, u.username, u.vault_id, u.revision, u.key_generation FROM sessions s JOIN devices d ON d.id = s.device_id AND d.user_id = s.user_id JOIN users u ON u.id = s.user_id WHERE s.id = ?', sessionId);
    need(principal && !principal.revoked && !principal.device_revoked && principal.absolute_expires > this.now()
      && principal.refresh_expires > this.now(), 401, 'unauthorized');
    return principal;
  }

  async authenticate(request) {
    const match = /^Bearer (ab1a\.([0-9a-f-]{36})\.[A-Za-z0-9_-]{43})$/.exec(request.headers.get('Authorization') ?? '');
    need(match, 401, 'unauthorized');
    const hash = await secretHash(match[1]);
    const principal = this.live(match[2]);
    need(principal.access_hash === hash && principal.access_expires > this.now(), 401, 'unauthorized');
    return principal;
  }

  permit(principal, operation) { need(authorize(principal, operation), 403, 'forbidden'); }

  async makeChallenge(subject, deviceId, sessionId, operation, hash, prepared = null) {
    this.sql.exec('DELETE FROM challenges WHERE expires_at <= ? OR used = 1', this.now());
    need(this.count('SELECT COUNT(*) AS n FROM challenges') < 1000, 429, 'challenge_limit');
    const value = { origin: this.env.API_ORIGIN, id: crypto.randomUUID(), subject, deviceId, sessionId, operation,
      requestHash: hash, expiresAt: this.now() + 90000, nonce: base64url(randomBytes(32)) };
    const payload = challengePayload(value);
    this.sql.exec('INSERT INTO challenges VALUES (?,?,?,?,?,?,?,?,0,?)', value.id, subject, deviceId, sessionId, operation, hash, payload, value.expiresAt, prepared);
    return { id: value.id, payload, expiresAt: value.expiresAt };
  }

  async proof(proof, expected, publicKey) {
    await input(() => { fields(proof, ['id', 'signature']); uuid(proof.id); unbase64url(proof.signature, 64); });
    const row = this.one('SELECT * FROM challenges WHERE id = ?', proof.id);
    need(row && !row.used && row.expires_at > this.now() && row.subject === expected.subject && row.device_id === expected.deviceId
      && row.session_id === expected.sessionId && row.operation === expected.operation && row.request_hash === expected.requestHash, 403, 'invalid_proof');
    let verified = false;
    try { verified = await verifyChallenge(publicKey, row.payload, proof.signature); } catch {}
    need(verified, 403, 'invalid_proof');
    return row;
  }

  consume(proof) {
    need(proof.expires_at > this.now(), 403, 'invalid_proof');
    const changed = this.sql.exec('UPDATE challenges SET used = 1, prepared_password_hash = NULL WHERE id = ? AND used = 0 AND expires_at > ? RETURNING id', proof.id, this.now()).toArray();
    need(changed.length === 1, 403, 'invalid_proof');
  }

  async managed(principal, operation, body, effect) {
    this.permit(principal, operation);
    const proof = await this.proof(body.proof, { subject: principal.user_id, deviceId: principal.device_id, sessionId: principal.id,
      operation, requestHash: await requestHash(body.payload) }, JSON.parse(principal.public_key));
    return this.ctx.storage.transactionSync(() => {
      this.permit(this.live(principal.id), operation);
      const result = effect();
      this.consume(proof);
      return result;
    });
  }

  addDevice(userId, device, role) {
    need(!this.one('SELECT id FROM devices WHERE id = ?', device.id), 409, 'device_exists');
    need(this.count('SELECT COUNT(*) AS n FROM devices WHERE user_id = ? AND revoked = 0', userId) < 20
      && this.count('SELECT COUNT(*) AS n FROM devices WHERE user_id = ?', userId) < 200, 409, 'device_limit');
    this.sql.exec('INSERT INTO devices VALUES (?,?,?,?,?,0)', device.id, userId, device.name, device.publicKey ? canonical(device.publicKey) : null, role);
  }

  saveRecovery(userId, hashes) {
    for (const hash of hashes) need(!this.one('SELECT hash FROM recovery_codes WHERE user_id = ? AND hash = ?', userId, hash), 409, 'recovery_code_reused');
    need(this.count('SELECT COUNT(*) AS n FROM recovery_codes WHERE user_id = ?', userId) + hashes.length <= 1024, 507, 'recovery_archive_limit');
    this.sql.exec('UPDATE recovery_codes SET used = 2 WHERE user_id = ? AND used = 0', userId);
    for (const hash of hashes) this.sql.exec('INSERT INTO recovery_codes VALUES (?,?,0)', userId, hash);
  }

  async snapshotPlan(backup) {
    const signatures = [];
    for (const record of [...backup.entities, backup.manifest]) signatures.push({ nonce: record.nonce, digest: await requestHash(record) });
    return signatures;
  }

  saveSnapshot(user, backup, encoded, signatures, transition) {
    this.sql.exec('DELETE FROM snapshots WHERE user_id = ? AND revision <> ? AND created_at < ?', user.id, user.revision, this.now() - 30 * DAY);
    const size = utf8(encoded).length;
    need(this.count('SELECT COALESCE(SUM(byte_count),0) AS n FROM snapshots WHERE user_id = ?', user.id) + size <= 32 * 1024 * 1024
      && this.count('SELECT COALESCE(SUM(byte_count),0) AS n FROM snapshots') + size <= 128 * 1024 * 1024, 507, 'snapshot_capacity');
    for (const record of backup.entities) {
      const existing = this.one('SELECT * FROM entity_ids WHERE user_id = ? AND id = ?', user.id, record.id);
      if (!existing) this.sql.exec('INSERT INTO entity_ids VALUES (?,?,?,0)', user.id, record.id, record.type);
      else if (existing.retired) this.sql.exec('UPDATE entity_ids SET retired = 0 WHERE user_id = ? AND id = ?', user.id, record.id);
    }
    need(this.count('SELECT COUNT(*) AS n FROM entity_ids WHERE user_id = ?', user.id) <= 10000, 507, 'identity_capacity');
    for (const id of transition.removed) this.sql.exec('UPDATE entity_ids SET retired = 1 WHERE user_id = ? AND id = ?', user.id, id);
    for (const item of signatures) {
      const previous = this.one('SELECT record_digest FROM nonces WHERE user_id = ? AND generation = ? AND nonce = ?', user.id, backup.keyGeneration, item.nonce);
      need(!previous || previous.record_digest === item.digest, 409, 'nonce_reuse');
      if (!previous) this.sql.exec('INSERT INTO nonces VALUES (?,?,?,?)', user.id, backup.keyGeneration, item.nonce, item.digest);
    }
    need(this.count('SELECT COUNT(*) AS n FROM nonces WHERE user_id = ?', user.id) <= 100000, 507, 'nonce_capacity');
    this.sql.exec('INSERT INTO snapshots VALUES (?,?,?,?,?,?)', user.id, backup.revision, encoded, size, this.now(), backup.digest);
    const changed = this.sql.exec('UPDATE users SET revision = ?, key_generation = ? WHERE id = ? AND revision = ? RETURNING id', backup.revision, backup.keyGeneration, user.id, user.revision).toArray();
    need(changed.length === 1, 409, 'revision_conflict');
  }

  async route(request) {
    const url = new URL(request.url);
    need(url.origin === this.env.API_ORIGIN, 400, 'wrong_origin');
    const path = url.pathname;
    const publicRoutes = new Set(['/v1/auth/challenge', '/v1/auth/register', '/v1/auth/login', '/v1/auth/device-login', '/v1/auth/recover', '/v1/auth/refresh']);
    if (publicRoutes.has(path)) {
      need(request.method === 'POST' && !url.search, 405, 'method_not_allowed');
      const body = await readJson(request);
      return this.publicRoute(request, path, body);
    }
    const principal = await this.authenticate(request);
    if (request.method === 'GET') return this.readRoute(url, principal);
    need(request.method === 'POST' && !url.search, 405, 'method_not_allowed');
    if (path === '/v1/auth/logout') {
      await discardBody(request);
      this.sql.exec('UPDATE sessions SET revoked = 1 WHERE id = ?', principal.id);
      return json({ loggedOut: true });
    }
    const operation = { '/v1/vault/commit': 'sync.commit', '/v1/vault/rotate': 'vault.rotate', '/v1/devices/approve': 'device.approve', '/v1/devices/revoke': 'device.revoke',
      '/v1/account/recovery/rotate': 'recovery.rotate', '/v1/account/password/prepare': 'password.change', '/v1/account/password/confirm': 'password.change' }[path];
    if (path === '/v1/challenges') this.permit(principal, 'sync.commit');
    else { need(operation, 404, 'not_found'); this.permit(principal, operation); }
    this.rate([[`manage:${principal.user_id}`, 120]]);
    const body = await readJson(request);
    if (path === '/v1/challenges') {
      await input(() => { fields(body, ['operation', 'requestHash']); unbase64url(body.requestHash, 32); });
      need(MANAGEMENT_OPERATIONS.includes(body.operation) && body.operation !== 'password.change', 400, 'invalid_operation');
      return json(await this.makeChallenge(principal.user_id, principal.device_id, principal.id, body.operation, body.requestHash));
    }
    if (path === '/v1/account/password/prepare') return this.passwordPrepare(request, principal, body);
    if (path === '/v1/account/password/confirm') return this.passwordConfirm(principal, body);
    await input(() => fields(body, ['payload', 'proof']));
    if (operation === 'sync.commit' || operation === 'vault.rotate') return this.commit(principal, body, operation === 'vault.rotate');
    if (operation === 'recovery.rotate') {
      await input(() => { fields(body.payload, ['hashes']); validateRecoveryHashes(body.payload.hashes); });
      return json(await this.managed(principal, operation, body, () => { this.saveRecovery(principal.user_id, body.payload.hashes); return { rotated: true }; }));
    }
    await input(() => { fields(body.payload, ['deviceId']); uuid(body.payload.deviceId); });
    return json(await this.managed(principal, operation, body, () => {
      const target = this.one('SELECT * FROM devices WHERE id = ? AND user_id = ? AND revoked = 0', body.payload.deviceId, principal.user_id);
      need(target, 404, 'device_not_found');
      if (operation === 'device.approve') {
        need(target.public_key && target.role === 'reader', 409, 'device_cannot_be_approved');
        this.sql.exec("UPDATE devices SET role = 'manager' WHERE id = ?", target.id);
      } else this.sql.exec('UPDATE devices SET revoked = 1 WHERE id = ?', target.id);
      this.sql.exec('UPDATE sessions SET revoked = 1 WHERE device_id = ?', target.id);
      return { updated: true };
    }));
  }

  async publicRoute(request, path, body) {
    if (path === '/v1/auth/refresh') return this.refresh(request, body);
    await input(() => username(body.username));
    await this.limitAuth(request, body.username);
    if (path === '/v1/auth/challenge') {
      await input(() => {
        fields(body, ['operation', 'username', 'deviceId', 'requestHash', 'invite']);
        uuid(body.deviceId); unbase64url(body.requestHash, 32);
      });
      need(['auth.register', 'auth.device-login', 'auth.recover'].includes(body.operation), 400, 'invalid_operation');
      if (body.operation === 'auth.register') await this.invitation(body.invite);
      else need(body.invite === null, 400, 'invalid_request');
      return json(await this.makeChallenge(body.username, body.deviceId, '', body.operation, body.requestHash));
    }
    if (path === '/v1/auth/register') return this.register(body);
    if (path === '/v1/auth/recover') return this.recover(body);
    await input(() => loginPassword(body.password));
    if (path === '/v1/auth/login') {
      await input(() => { fields(body, ['username', 'password', 'deviceName', 'publicKey']); deviceName(body.deviceName); if (body.publicKey !== null) publicDeviceKey(body.publicKey); });
      if (body.publicKey) await input(() => crypto.subtle.importKey('jwk', body.publicKey, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']));
    } else await input(() => { fields(body, ['username', 'password', 'deviceId', 'proof']); uuid(body.deviceId); });
    const user = this.one('SELECT * FROM users WHERE username = ?', body.username);
    need(await checkPassword(body.password, user?.password_hash), 401, 'invalid_credentials');
    let device;
    let proof;
    if (path === '/v1/auth/device-login') {
      device = this.one('SELECT * FROM devices WHERE id = ? AND user_id = ? AND revoked = 0', body.deviceId, user.id);
      need(device?.public_key, 401, 'invalid_credentials');
      proof = await this.proof(body.proof, { subject: body.username, deviceId: device.id, sessionId: '', operation: 'auth.device-login',
        requestHash: await requestHash(authContext('auth.device-login', body)) }, JSON.parse(device.public_key));
    } else device = { id: crypto.randomUUID(), name: body.deviceName, publicKey: body.publicKey, role: 'reader' };
    const session = await this.newSession(user.id, device.id);
    this.ctx.storage.transactionSync(() => {
      if (proof) this.consume(proof); else this.addDevice(user.id, device, 'reader');
      this.saveSession(session);
    });
    return json(this.sessionResponse(user, device, session));
  }

  async invitation(value) {
    await input(() => unbase64url(value, 32));
    const hash = await secretHash(value);
    need(this.invites.includes(hash) && this.one('SELECT user_id FROM invitations WHERE hash = ?', hash)?.user_id === null, 403, 'invalid_invite');
    return hash;
  }

  async register(body) {
    await input(() => { fields(body, ['username', 'password', 'invite', 'device', 'recoveryHashes', 'backup', 'proof']);
      loginPassword(body.password); deviceInput(body.device); validateRecoveryHashes(body.recoveryHashes); });
    const invitation = await this.invitation(body.invite);
    const backup = await input(() => validateCommit({ operationId: body.device.id, baseRevision: 0, restoredIds: [], backup: body.backup }));
    const transition = await input(() => validateTransition(undefined, backup));
    const proof = await this.proof(body.proof, { subject: body.username, deviceId: body.device.id, sessionId: '', operation: 'auth.register',
      requestHash: await requestHash(authContext('auth.register', body)) }, body.device.publicKey);
    need(!this.one('SELECT id FROM users WHERE username = ? OR vault_id = ?', body.username, backup.vaultId), 409, 'account_exists');
    const hash = await hashPassword(body.password);
    const user = { id: crypto.randomUUID(), username: body.username, vault_id: backup.vaultId, revision: 0 };
    const session = await this.newSession(user.id, body.device.id);
    const signatures = await this.snapshotPlan(backup);
    this.ctx.storage.transactionSync(() => {
      const consumed = this.sql.exec('UPDATE invitations SET user_id = ? WHERE hash = ? AND user_id IS NULL RETURNING hash', user.id, invitation).toArray();
      need(consumed.length === 1, 403, 'invalid_invite');
      need(this.count('SELECT COUNT(*) AS n FROM users') < 16, 507, 'account_capacity');
      this.sql.exec('INSERT INTO users VALUES (?,?,?,?,0,1)', user.id, body.username, hash, backup.vaultId);
      this.addDevice(user.id, body.device, 'manager');
      this.saveRecovery(user.id, body.recoveryHashes);
      this.saveSnapshot(user, backup, body.backup, signatures, transition);
      this.saveSession(session);
      this.consume(proof);
    });
    return json(this.sessionResponse(user, { id: body.device.id, role: 'manager' }, session), 201);
  }

  async refresh(request, body) {
    await input(() => fields(body, ['refreshToken']));
    const match = /^ab1r\.([0-9a-f-]{36})\.[A-Za-z0-9_-]{43}$/.exec(body.refreshToken ?? '');
    need(match, 401, 'unauthorized');
    await this.limitAuth(request, `refresh:${match[1]}`);
    const hash = await secretHash(body.refreshToken);
    const used = this.one('SELECT session_id FROM refresh_used WHERE hash = ?', hash);
    if (used) { this.sql.exec('UPDATE sessions SET revoked = 1 WHERE id = ?', used.session_id); throw new ApiError(401, 'refresh_reuse'); }
    const principal = this.live(match[1]);
    need(principal.refresh_hash === hash, 401, 'unauthorized');
    this.sql.exec('DELETE FROM refresh_used WHERE expires_at <= ?', this.now());
    need(this.count('SELECT COUNT(*) AS n FROM refresh_used WHERE session_id = ?', principal.id) < 4096
      && this.count('SELECT COUNT(*) AS n FROM refresh_used r JOIN sessions s ON s.id = r.session_id WHERE s.user_id = ?', principal.user_id) < 8192, 429, 'refresh_history_limit');
    const session = await this.newSession(principal.user_id, principal.device_id, principal.absolute_expires, principal.id);
    this.ctx.storage.transactionSync(() => {
      this.live(principal.id);
      this.sql.exec('INSERT INTO refresh_used VALUES (?,?,?)', hash, principal.id, principal.absolute_expires);
      this.sql.exec('UPDATE sessions SET access_hash = ?, access_expires = ?, refresh_hash = ?, refresh_expires = ? WHERE id = ? AND refresh_hash = ?',
        session.accessHash, session.accessExpires, session.refreshHash, session.refreshExpires, principal.id, hash);
    });
    return json(this.sessionResponse({ id: principal.user_id, username: principal.username }, { id: principal.device_id, role: principal.role }, session));
  }

  async recover(body) {
    await input(() => { fields(body, ['username', 'code', 'password', 'device', 'proof']); loginPassword(body.password); deviceInput(body.device); });
    const hash = await input(() => accountRecoveryHash(body.code));
    const user = this.one('SELECT * FROM users WHERE username = ?', body.username);
    need(user && this.one('SELECT used FROM recovery_codes WHERE user_id = ? AND hash = ?', user.id, hash)?.used === 0, 401, 'invalid_credentials');
    const proof = await this.proof(body.proof, { subject: body.username, deviceId: body.device.id, sessionId: '', operation: 'auth.recover',
      requestHash: await requestHash(authContext('auth.recover', body)) }, body.device.publicKey);
    const passwordHash = await hashPassword(body.password);
    const session = await this.newSession(user.id, body.device.id);
    this.ctx.storage.transactionSync(() => {
      const changed = this.sql.exec('UPDATE recovery_codes SET used = 1 WHERE user_id = ? AND hash = ? AND used = 0 RETURNING hash', user.id, hash).toArray();
      need(changed.length === 1, 401, 'invalid_credentials');
      this.sql.exec('UPDATE users SET password_hash = ? WHERE id = ?', passwordHash, user.id);
      this.sql.exec('UPDATE sessions SET revoked = 1 WHERE user_id = ?', user.id);
      this.sql.exec('UPDATE devices SET revoked = 1 WHERE user_id = ?', user.id);
      this.addDevice(user.id, body.device, 'manager');
      this.saveSession(session);
      this.consume(proof);
    });
    return json(this.sessionResponse(user, { id: body.device.id, role: 'manager' }, session));
  }

  async passwordPrepare(request, principal, body) {
    await input(() => { fields(body, ['currentPassword', 'newPassword']); loginPassword(body.currentPassword); loginPassword(body.newPassword); });
    await this.limitAuth(request, principal.username);
    const user = this.one('SELECT * FROM users WHERE id = ?', principal.user_id);
    need(await checkPassword(body.currentPassword, user.password_hash), 401, 'invalid_credentials');
    const hash = await hashPassword(body.newPassword);
    return json(await this.makeChallenge(principal.user_id, principal.device_id, principal.id, 'password.change', await secretHash(hash), hash));
  }

  async passwordConfirm(principal, body) {
    await input(() => { fields(body, ['proof']); fields(body.proof, ['id', 'signature']); uuid(body.proof.id); });
    const candidate = this.one('SELECT request_hash, prepared_password_hash FROM challenges WHERE id = ?', body.proof.id);
    need(candidate?.prepared_password_hash, 403, 'invalid_proof');
    const proof = await this.proof(body.proof, { subject: principal.user_id, deviceId: principal.device_id, sessionId: principal.id,
      operation: 'password.change', requestHash: candidate.request_hash }, JSON.parse(principal.public_key));
    const session = await this.newSession(principal.user_id, principal.device_id);
    this.ctx.storage.transactionSync(() => {
      this.permit(this.live(principal.id), 'password.change');
      this.sql.exec('UPDATE users SET password_hash = ? WHERE id = ?', candidate.prepared_password_hash, principal.user_id);
      this.sql.exec('UPDATE sessions SET revoked = 1 WHERE user_id = ?', principal.user_id);
      this.saveSession(session);
      this.consume(proof);
    });
    return json(this.sessionResponse({ id: principal.user_id, username: principal.username }, { id: principal.device_id, role: principal.role }, session));
  }

  async commit(principal, body, rotation = false) {
    const backup = await input(() => validateCommit(body.payload));
    const hash = await requestHash(rotation ? { operation: 'vault.rotate', payload: body.payload } : body.payload);
    const prior = this.one('SELECT * FROM operations WHERE user_id = ? AND device_id = ? AND operation_id = ?', principal.user_id, principal.device_id, body.payload.operationId);
    if (prior) { need(prior.request_hash === hash, 409, 'operation_mismatch'); return json(JSON.parse(prior.result)); }
    const user = this.one('SELECT * FROM users WHERE id = ?', principal.user_id);
    need(user.revision === body.payload.baseRevision, 409, 'revision_conflict', { revision: user.revision });
    const previous = JSON.parse(this.one('SELECT body FROM snapshots WHERE user_id = ? AND revision = ?', user.id, user.revision).body);
    const retired = new Map(this.sql.exec('SELECT id,type FROM entity_ids WHERE user_id = ? AND retired = 1', user.id).toArray().map((row) => [row.id, row.type]));
    const transition = await input(() => validateTransition(previous, backup, retired, body.payload.restoredIds, rotation));
    const signatures = await this.snapshotPlan(backup);
    const result = await this.managed(principal, rotation ? 'vault.rotate' : 'sync.commit', body, () => {
      need(this.one('SELECT revision FROM users WHERE id = ?', user.id).revision === body.payload.baseRevision, 409, 'revision_conflict');
      this.sql.exec('DELETE FROM operations WHERE user_id = ? AND created_at < ?', user.id, this.now() - 30 * DAY);
      need(this.count('SELECT COUNT(*) AS n FROM operations WHERE user_id = ?', user.id) < 10000, 507, 'operation_capacity');
      this.saveSnapshot(user, backup, body.payload.backup, signatures, transition);
      if (rotation) {
        this.sql.exec("UPDATE devices SET role = 'reader' WHERE user_id = ? AND id <> ? AND revoked = 0", user.id, principal.device_id);
        this.sql.exec('UPDATE sessions SET revoked = 1 WHERE user_id = ? AND id <> ?', user.id, principal.id);
      }
      const receipt = { operationId: body.payload.operationId, revision: backup.revision, digest: backup.digest };
      this.sql.exec('INSERT INTO operations VALUES (?,?,?,?,?,?)', user.id, principal.device_id, body.payload.operationId, hash, canonical(receipt), this.now());
      return receipt;
    });
    return json(result);
  }

  readRoute(url, principal) {
    const path = url.pathname;
    if (path === '/v1/devices') {
      this.permit(principal, 'device.list'); need(!url.search, 400, 'invalid_query');
      return json({ devices: this.sql.exec('SELECT id,name,role,revoked,public_key IS NOT NULL AS hasKey FROM devices WHERE user_id = ? ORDER BY id', principal.user_id).toArray() });
    }
    need(['/v1/vault/head', '/v1/vault/snapshot', '/v1/vault/changes', '/v1/vault/history', '/v1/vault/retired'].includes(path), 404, 'not_found');
    this.permit(principal, 'vault.read');
    const user = this.one('SELECT * FROM users WHERE id = ?', principal.user_id);
    if (path === '/v1/vault/retired') {
      this.permit(principal, 'sync.commit'); need(!url.search, 400, 'invalid_query');
      return json({ vaultId: user.vault_id, revision: user.revision, keyGeneration: user.key_generation,
        retiredIds: this.sql.exec('SELECT id FROM entity_ids WHERE user_id = ? AND retired = 1 ORDER BY id', user.id).toArray().map((row) => row.id) });
    }
    if (path === '/v1/vault/history') {
      need([...url.searchParams.keys()].every((name) => ['before', 'limit'].includes(name))
        && ['before', 'limit'].every((name) => url.searchParams.getAll(name).length <= 1), 400, 'invalid_query');
      const before = url.searchParams.get('before'); const rawLimit = url.searchParams.get('limit') ?? '20';
      need(/^(?:[1-9]|1[0-9]|20)$/.test(rawLimit), 400, 'invalid_query');
      need(before === null || (/^[1-9][0-9]{0,15}$/.test(before) && Number.isSafeInteger(Number(before)) && Number(before) <= user.revision), 400, 'invalid_query');
      const limit = Number(rawLimit);
      const rows = this.sql.exec(`SELECT revision,created_at AS createdAt,byte_count AS bytes,digest FROM snapshots WHERE user_id = ?
        AND (created_at >= ? OR revision = ?) AND revision ${before === null ? '<=' : '<'} ? ORDER BY revision DESC LIMIT ?`,
      user.id, this.now() - 30 * DAY, user.revision, before === null ? user.revision : Number(before), limit + 1).toArray();
      const entries = rows.slice(0, limit);
      return json({ vaultId: user.vault_id, revision: user.revision, keyGeneration: user.key_generation, entries,
        nextBefore: rows.length > limit ? entries.at(-1).revision : null });
    }
    if (path === '/v1/vault/head') { need(!url.search, 400, 'invalid_query'); return json({ vaultId: user.vault_id, revision: user.revision, keyGeneration: user.key_generation }); }
    const key = path.endsWith('/changes') ? 'after' : 'revision';
    need([...url.searchParams.keys()].every((name) => name === key) && url.searchParams.getAll(key).length <= 1, 400, 'invalid_query');
    const raw = url.searchParams.get(key) ?? (key === 'revision' ? String(user.revision) : '0');
    need(/^(0|[1-9][0-9]{0,15})$/.test(raw) && Number.isSafeInteger(Number(raw)) && Number(raw) <= user.revision, 400, 'invalid_query');
    const revision = Number(raw);
    if (key === 'after') {
      if (revision === user.revision) return json({ mode: 'unchanged', revision });
      need(revision === 0 || this.one('SELECT revision FROM snapshots WHERE user_id = ? AND revision = ? AND created_at >= ?', user.id, revision, this.now() - 30 * DAY), 410, 'full_sync_required', { revision: user.revision });
      return json({ mode: 'full', revision: user.revision, snapshot: `/v1/vault/snapshot?revision=${user.revision}` });
    }
    const row = this.one('SELECT body,created_at FROM snapshots WHERE user_id = ? AND revision = ?', user.id, revision);
    need(row && (revision === user.revision || row.created_at >= this.now() - 30 * DAY), 404, 'snapshot_not_found');
    return new Response(row.body, { headers: { 'Content-Type': 'application/vnd.authbox.backup+json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
  }
}
