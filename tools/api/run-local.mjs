import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { sha256, utf8 } from '../../packages/protocol/src/encoding.mjs';
import { buildApi } from '../bundling/api.mjs';
import { runApiSuite } from '../../services/api/test/suite.mjs';

const fixtureToken = randomBytes(32).toString('hex');
const invites = Array.from({ length: 2 }, () => randomBytes(32).toString('base64url'));
const bundle = await buildApi({ fixture: true });
const directory = resolve(`.cache/api/local/${randomUUID()}`);
await mkdir(directory, { recursive: true });
const origin = 'https://api.example.invalid';
const mf = new Miniflare(convertV4MiniflareOptions({ name: 'authbox-api-local', ...bundle.localOptions, compatibilityDate: '2026-10-16',
  durableObjects: { INSTALLATION: { className: 'FixtureStore', useSQLite: true } }, durableObjectsPersist: directory,
  bindings: { API_ORIGIN: origin, REGISTRATION_INVITE_HASHES: JSON.stringify(await Promise.all(invites.map((invite) => sha256(utf8(invite))))),
    FIXTURE_TOKEN: fixtureToken, FIXTURE_EXPIRES_AT: String(Date.now() + 1800000), CLIENT_ORIGINS: '["https://client.example.invalid"]' } }));
try {
  const report = await runApiSuite((url, options) => mf.dispatchFetch(url, options), { origin, fixtureToken, invites, onCase: (value) => console.log(JSON.stringify(value)) });
  report.environment = 'Local workerd with actual SQLite DO, not cloud deployment or CPU validation';
  report.bundle = bundle.manifest;
  await writeFile(`${directory}/report.json`, JSON.stringify(report, null, 2) + '\n');
  await writeFile('.cache/api/local/latest.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ passed: report.results.length, checks: report.checks, report: '.cache/api/local/latest.json' }, null, 2));
} finally { await mf.dispose(); }
