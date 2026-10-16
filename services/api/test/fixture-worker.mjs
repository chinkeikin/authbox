import api, { InstallationStore } from '../src/worker.mjs';
import { json } from '../src/http.mjs';

function gate(request, env) {
  return env.FIXTURE_TOKEN?.length >= 32 && request.headers.get('X-AuthBox-Fixture') === env.FIXTURE_TOKEN
    && Number(env.FIXTURE_EXPIRES_AT) > Date.now();
}

export class FixtureStore extends InstallationStore {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql.exec('CREATE TABLE IF NOT EXISTS fixture_clock (id INTEGER PRIMARY KEY, offset INTEGER NOT NULL)');
    this.sql.exec(`CREATE TABLE IF NOT EXISTS fixture_fault_control (kind TEXT PRIMARY KEY, id TEXT NOT NULL);
      CREATE TRIGGER IF NOT EXISTS fixture_fault_operation BEFORE INSERT ON operations
      WHEN EXISTS(SELECT 1 FROM fixture_fault_control WHERE kind = 'operation' AND id = NEW.operation_id)
      BEGIN SELECT RAISE(ABORT,'Synthetic operation fault'); END;
      CREATE TRIGGER IF NOT EXISTS fixture_fault_session BEFORE INSERT ON sessions
      WHEN EXISTS(SELECT 1 FROM fixture_fault_control WHERE kind = 'session' AND id = NEW.device_id)
      BEGIN SELECT RAISE(ABORT,'Synthetic session fault'); END;`);
    this.offset = this.one('SELECT offset FROM fixture_clock WHERE id = 1')?.offset ?? 0;
  }
  now() { return Date.now() + (this.offset ?? 0); }
  async fetch(request) {
    const url = new URL(request.url);
    if (!gate(request, this.env)) return json({ error: 'fixture_unavailable' }, 503);
    if (url.pathname.startsWith('/__test/')) {
      if (url.pathname === '/__test/clock') {
        const value = Number(url.searchParams.get('offset'));
        if (!Number.isSafeInteger(value) || value < 0) return json({ error: 'invalid_offset' }, 400);
        this.offset = value;
        this.sql.exec('INSERT INTO fixture_clock VALUES (1,?) ON CONFLICT(id) DO UPDATE SET offset = excluded.offset', value);
        return json({ changed: true });
      }
      if (url.pathname === '/__test/reset') this.ctx.abort('AuthBox synthetic service persistence check');
      if (url.pathname === '/__test/fault') {
        const id = url.searchParams.get('id');
        if (id !== null && !/^[0-9a-f-]{36}$/.test(id)) return json({ error: 'invalid_id' }, 400);
        const kind = url.searchParams.get('kind');
        this.sql.exec('DELETE FROM fixture_fault_control');
        if (['operation', 'session'].includes(kind) && id) this.sql.exec('INSERT INTO fixture_fault_control VALUES (?,?)', kind, id);
        return json({ configured: true });
      }
      if (url.pathname === '/__test/audit') {
        return json({ users: this.sql.exec('SELECT * FROM users ORDER BY username').toArray(),
          invitations: this.sql.exec('SELECT * FROM invitations').toArray(),
          devices: this.sql.exec('SELECT * FROM devices').toArray(), sessions: this.sql.exec('SELECT * FROM sessions').toArray(),
          recoveryCodes: this.sql.exec('SELECT * FROM recovery_codes').toArray(),
          snapshotCount: this.count('SELECT COUNT(*) AS n FROM snapshots'),
          operationCount: this.count('SELECT COUNT(*) AS n FROM operations'),
          snapshots: this.sql.exec('SELECT body FROM snapshots').toArray(),
          challenges: this.sql.exec('SELECT * FROM challenges').toArray(), nonces: this.sql.exec('SELECT * FROM nonces').toArray() });
      }
      return json({ error: 'not_found' }, 404);
    }
    const label = request.headers.get('X-AuthBox-Case');
    if (label && /^[a-z0-9-]{1,64}$/.test(label)) console.log(JSON.stringify({ authboxServiceFixture: 1, label }));
    return super.fetch(request);
  }
}

export default {
  async fetch(request, env) {
    if (!gate(request, env)) return json({ error: 'fixture_unavailable' }, 503);
    if (new URL(request.url).pathname.startsWith('/__test/')) {
      try { return await env.INSTALLATION.get(env.INSTALLATION.idFromName('installation-v1')).fetch(request); }
      catch { return json({ error: 'fixture_reset' }, 503); }
    }
    const response = await api.fetch(request, env);
    const output = new Response(response.body, response);
    output.headers.set('X-AuthBox-Service-Fixture', 'api-v1');
    return output;
  },
};
