import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readJson } from '../src/http.mjs';

test('HTTP parser rejects media type, duplicate keys and noncanonical body', async () => {
  const make = (body, contentType = 'application/json') => new Request('https://api.example.invalid', { method: 'POST', headers: { 'Content-Type': contentType }, body });
  assert.deepEqual(await readJson(make('{"a":1}')), { a: 1 });
  for (const request of [make('{}', 'text/plain'), make('{"a":1,"a":2}'), make('{ "a":1}'), make('{')]) {
    await assert.rejects(readJson(request), (error) => [400, 415].includes(error.status));
  }
});

test('HTTP parser bounds a slow request independently of the caller connection', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const body = new ReadableStream();
  const request = new Request('https://api.example.invalid', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, duplex: 'half' });
  const pending = readJson(request);
  await Promise.resolve(); await Promise.resolve();
  t.mock.timers.tick(10001);
  await assert.rejects(pending, (error) => error.status === 408);
});
