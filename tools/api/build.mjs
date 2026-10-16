import assert from 'node:assert/strict';
import { buildApi } from '../bundling/api.mjs';

const bundle = await buildApi();
for (const forbidden of ['/__test/', 'FIXTURE_TOKEN', 'FIXTURE_EXPIRES_AT', 'fixture_fault', 'authboxServiceFixture']) {
  assert.equal(bundle.script.includes(forbidden), false, `Fixture code in production bundle: ${forbidden}`);
}
console.log(JSON.stringify({ entry: 'dist/api/worker.mjs', bytes: Buffer.byteLength(bundle.script), wasm: bundle.manifest.modules,
  runtime: 'Workers Free + SQLite Durable Objects', deployed: false }, null, 2));
