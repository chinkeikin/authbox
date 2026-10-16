import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { build } from 'esbuild';
import { argon2id } from 'hash-wasm';
import { chromium } from 'playwright-core';
import { makeInteropFixture, rfcExpected, vaultProfile, wasmOptions } from './fixtures.mjs';

const outputDirectory = '.cache/feasibility/browser';
await mkdir(outputDirectory, { recursive: true });
const fixture = makeInteropFixture();
const key = await crypto.subtle.importKey('raw', fixture.key, 'AES-GCM', false, ['encrypt', 'decrypt']);
const settings = { name: 'AES-GCM', iv: fixture.nonce, additionalData: fixture.aad, tagLength: 128 };
const ciphertext = new Uint8Array(await crypto.subtle.encrypt(settings, key, fixture.plaintext));
const expectedHash = await argon2id(wasmOptions(vaultProfile));
const bundle = await build({
  entryPoints: ['tools/feasibility/browser-entry.mjs'],
  bundle: true,
  write: false,
  platform: 'browser',
  format: 'iife',
  target: 'es2022',
});
const csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; object-src 'none'";
const server = createServer((request, response) => {
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('Content-Security-Policy', csp);
  if (request.url === '/') {
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end('<!doctype html><meta charset="utf-8"><title>AuthBox isolated crypto probe</title><script src="/probe.js"></script>');
  } else if (request.url === '/probe.js') {
    response.setHeader('Content-Type', 'text/javascript; charset=utf-8');
    response.end(bundle.outputFiles[0].text);
  } else {
    response.writeHead(404).end();
  }
});
await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', resolve);
});

let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(() => Boolean(window.authboxProbe));
  const result = await page.evaluate((input) => window.authboxProbe.run(input), Array.from(ciphertext));
  assert.deepEqual(errors, []);
  assert.equal(result.secureContext, true);
  assert.equal(result.ciphertext, Buffer.from(ciphertext).toString('hex'));
  assert.equal(result.plaintext, Buffer.from(fixture.plaintext).toString('hex'));
  assert.equal(result.rfc, rfcExpected);
  assert.deepEqual(result.hashes, [expectedHash, expectedHash, expectedHash]);
  assert.deepEqual(result.tamperChecks, ['ciphertext', 'tag', 'nonce', 'aad', 'key']);
  const decoded = await crypto.subtle.decrypt(settings, key, Buffer.from(result.ciphertext, 'hex'));
  assert.deepEqual(Buffer.from(decoded), Buffer.from(fixture.plaintext));
  const report = {
    generatedAt: new Date().toISOString(),
    browser: browser.version(),
    environment: 'isolated installed Chrome headless profile on loopback HTTP secure context',
    csp,
    aesGcmInterop: 'Chrome and Node agree with the same fixture verified on Android',
    tamperChecks: result.tamperChecks,
    argon2StandardVector: 'RFC 9106 section 5.3 passed',
    vaultProfile,
    hashWasmMatchesNode: true,
    hashWasmWallMs: result.samples,
    scope: 'browser page with restrictive CSP; Manifest V3 extension storage/lifecycle not yet validated',
  };
  await writeFile(`${outputDirectory}/report.json`, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser?.close();
  await new Promise((resolve) => server.close(resolve));
}
