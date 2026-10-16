import { argon2id, argon2Verify } from 'hash-wasm';
import { argon2id as nobleArgon2id } from '@noble/hashes/argon2.js';
import { durableMarker, hex, rfcExpected, rfcProfile, serverProfile, wasmOptions } from './fixtures.mjs';

const actions = new Set(['register', 'login', 'inspect', 'vector', 'reset', 'clear']);
const labels = /^[a-z][a-z0-9-]{0,47}$/;
const encodedPrefix = '$argon2id$v=19$m=32768,t=3,p=1$';

function response(value, status = 200) {
  return Response.json(value, { status, headers: { 'Cache-Control': 'no-store', 'X-AuthBox-Probe': durableMarker } });
}

function gate(request, env) {
  const expires = Number(env.PROBE_EXPIRES_AT);
  if (!Number.isFinite(expires) || expires <= Date.now() || !env.PROBE_TOKEN || env.PROBE_TOKEN.length < 32) return response({ error: 'Probe unavailable' }, 503);
  if (request.headers.get('Authorization') !== `Bearer ${env.PROBE_TOKEN}`) return response({ error: 'Unauthorized' }, 401);
  if (request.method !== 'POST') return response({ error: 'Method not allowed' }, 405);
}

function route(request) {
  const parts = new URL(request.url).pathname.split('/');
  if (parts.length !== 4 || parts[1] !== 'do' || !labels.test(parts[2]) || !actions.has(parts[3])) return undefined;
  return { label: parts[2], action: parts[3] };
}

function event(label, action, hashed) {
  console.log(JSON.stringify({ authboxProbe: durableMarker, kind: 'object', label, action, hashed }));
}

async function credentials(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new Error('Missing fixture input');
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 2048) throw new Error('Fixture input too large');
      chunks.push(value);
    }
  } finally {
    await reader.cancel();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const value = JSON.parse(new TextDecoder().decode(bytes));
  if (typeof value.username !== 'string' || !/^fixture-[a-z0-9-]{1,48}$/.test(value.username)
    || typeof value.password !== 'string' || value.password.length < 8 || new TextEncoder().encode(value.password).length > 256) {
    throw new Error('Only bounded synthetic credentials are accepted');
  }
  return value;
}

export class AuthProbe {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    this.instance = crypto.randomUUID();
    this.waiters = [];
    this.locked = false;
    this.activeHashes = 0;
    this.maxActiveHashes = 0;
    this.sql.exec('CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, memory_kib INTEGER NOT NULL, iterations INTEGER NOT NULL, parallelism INTEGER NOT NULL, hash_bytes INTEGER NOT NULL)');
  }

  async fetch(request) {
    const rejected = gate(request, this.env);
    if (rejected) return rejected;
    const target = route(request);
    if (!target) return response({ error: 'Not found' }, 404);
    if (this.waiters.length >= 8) return response({ error: 'Probe queue full' }, 429);
    if (this.locked) await new Promise((resolve) => this.waiters.push(resolve));
    else this.locked = true;
    try { return await this.perform(request, target); }
    finally {
      const next = this.waiters.shift();
      if (next) next();
      else this.locked = false;
    }
  }

  async perform(request, { label, action }) {
    if (action === 'reset') {
      event(label, action, false);
      this.ctx.abort('AuthBox synthetic persistence test');
    }
    if (action === 'inspect') {
      event(label, action, false);
      return response({ engine: 'SQLite', instance: this.instance, maxActiveHashes: this.maxActiveHashes,
        rows: this.sql.exec('SELECT * FROM accounts ORDER BY username').toArray() });
    }
    if (action === 'clear') {
      this.sql.exec('DELETE FROM accounts');
      event(label, action, false);
      return response({ cleared: true });
    }
    if (action === 'vector') {
      event(label, action, true);
      const hash = hex(nobleArgon2id(new Uint8Array(32).fill(1), new Uint8Array(16).fill(2), rfcProfile));
      return response({ hash, matches: hash === rfcExpected });
    }
    let input;
    try { input = await credentials(request); }
    catch { event(label, action, false); return response({ error: 'Invalid fixture input' }, 400); }
    const row = this.sql.exec('SELECT * FROM accounts WHERE username = ?', input.username).toArray()[0];
    if (action === 'register' && row) {
      event(label, action, false);
      return response({ error: 'Fixture already registered' }, 409);
    }
    if (row && (row.memory_kib !== serverProfile.m || row.iterations !== serverProfile.t || row.parallelism !== serverProfile.p
      || row.hash_bytes !== serverProfile.dkLen || !row.password_hash.startsWith(encodedPrefix))) {
      event(label, action, false);
      return response({ error: 'Stored profile mismatch' }, 409);
    }
    this.activeHashes += 1;
    this.maxActiveHashes = Math.max(this.maxActiveHashes, this.activeHashes);
    event(label, action, true);
    try {
      if (action === 'register') {
        const salt = crypto.getRandomValues(new Uint8Array(16));
        const hash = await argon2id({ ...wasmOptions(serverProfile), password: input.password, salt, outputType: 'encoded' });
        this.sql.exec('INSERT INTO accounts VALUES (?, ?, ?, ?, ?, ?)', input.username, hash,
          serverProfile.m, serverProfile.t, serverProfile.p, serverProfile.dkLen);
        return response({ registered: true, profile: serverProfile }, 201);
      }
      const verified = row ? await argon2Verify({ password: input.password, hash: row.password_hash }) : false;
      if (!row) await argon2id({ ...wasmOptions(serverProfile), password: input.password, salt: crypto.getRandomValues(new Uint8Array(16)) });
      return response(verified ? { verified: true, profile: serverProfile } : { error: 'Invalid credentials' }, verified ? 200 : 401);
    } finally {
      this.activeHashes -= 1;
    }
  }
}

export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname === '/health') {
      const expires = Number(env.PROBE_EXPIRES_AT);
      return response({ probe: durableMarker }, Number.isFinite(expires) && expires > Date.now() ? 200 : 503);
    }
    const rejected = gate(request, env);
    if (rejected) return rejected;
    if (!route(request)) return response({ error: 'Not found' }, 404);
    const id = env.AUTH_PROBE.idFromName('synthetic-account-store');
    try { return await env.AUTH_PROBE.get(id).fetch(request); }
    catch { return response({ error: 'Durable Object invocation failed' }, 502); }
  },
};
