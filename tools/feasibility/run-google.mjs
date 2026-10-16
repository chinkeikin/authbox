import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT, UnsecuredJWT } from 'jose';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createGoogleVerifier } from './google-verifier.mjs';

const clientId = 'authbox-feasibility.apps.googleusercontent.com';
const nonce = randomBytes(24).toString('hex');
const now = Math.floor(Date.now() / 1000);
const pairs = [await generateKeyPair('RS256'), await generateKeyPair('RS256')];
const keys = await Promise.all(pairs.map(async (pair, index) => ({ ...await exportJWK(pair.publicKey), kid: `test-${index}`, alg: 'RS256', use: 'sig' })));
assert.equal(keys.some((key) => 'd' in key), false);
const jwks = { keys };
const verifier = createGoogleVerifier({ clientIds: [clientId], keySet: createLocalJWKSet(jwks) });
const base = { iss: 'https://accounts.google.com', aud: clientId, sub: 'test-subject', iat: now, exp: now + 300, nonce };
const sign = (claims = {}, keyIndex = 0, header = {}) => new SignJWT({ ...base, ...claims })
  .setProtectedHeader({ alg: 'RS256', kid: `test-${keyIndex}`, ...header }).sign(pairs[keyIndex].privateKey);
const valid = await sign();
const unsigned = new UnsecuredJWT(base).encode();
const hmac = await new SignJWT(base).setProtectedHeader({ alg: 'HS256' }).sign(randomBytes(32));
const malformedParts = valid.split('.');
malformedParts[1] = Buffer.from(JSON.stringify({ ...base, sub: 'altered' })).toString('base64url');
const cases = [
  { name: 'valid RS256 identity', token: valid, accepted: true },
  { name: 'alternate Google issuer canonicalized', token: await sign({ iss: 'accounts.google.com' }), accepted: true },
  { name: 'rotated trusted signing key', token: await sign({}, 1), accepted: true },
  { name: 'wrong issuer', token: await sign({ iss: 'https://attacker.invalid' }) },
  { name: 'wrong audience', token: await sign({ aud: 'other.apps.googleusercontent.com' }) },
  { name: 'expired token', token: await sign({ exp: now - 60, iat: now - 300 }) },
  { name: 'future issuance', token: await sign({ iat: now + 300, exp: now + 600 }) },
  { name: 'stale login token', token: await sign({ iat: now - 1000, exp: now + 300 }) },
  { name: 'missing nonce', token: await sign({ nonce: undefined }) },
  { name: 'wrong nonce', token: await sign({ nonce: 'different-nonce' }) },
  { name: 'empty subject', token: await sign({ sub: '' }) },
  { name: 'missing expiry', token: await sign({ exp: undefined }) },
  { name: 'invalid lifetime', token: await sign({ exp: now, iat: now }) },
  { name: 'unknown key id', token: await sign({}, 0, { kid: 'not-trusted' }) },
  { name: 'wrong key signature', token: await sign({}, 1, { kid: 'test-0' }) },
  { name: 'tampered claims', token: malformedParts.join('.') },
  { name: 'unsigned token', token: unsigned },
  { name: 'symmetric algorithm confusion', token: hmac },
  { name: 'untrusted authorized party', token: await sign({ azp: 'other.apps.googleusercontent.com' }) },
  { name: 'multiple audiences without azp', token: await sign({ aud: [clientId, 'other.apps.googleusercontent.com'] }) },
  { name: 'multiple audiences with trusted azp', token: await sign({ aud: [clientId, 'other.apps.googleusercontent.com'], azp: clientId }), accepted: true },
  { name: 'token key URL ignored', token: await sign({}, 0, { jku: 'https://attacker.invalid/keys' }), accepted: true },
];

for (const item of cases) {
  if (item.accepted) assert.deepEqual(await verifier(item.token, nonce), { issuer: base.iss, subject: base.sub }, item.name);
  else await assert.rejects(verifier(item.token, nonce), undefined, item.name);
}
const firstEmailIdentity = await verifier(await sign({ email: 'same@example.invalid' }), nonce);
const otherIdentity = await verifier(await sign({ sub: 'different-subject', email: 'same@example.invalid' }), nonce);
assert.notEqual(otherIdentity.subject, firstEmailIdentity.subject);

const bundle = await build({ entryPoints: ['tools/feasibility/google-worker.mjs'], bundle: true, write: false, platform: 'browser', format: 'esm', target: 'es2022' });
const token = randomBytes(32).toString('hex');
const mf = new Miniflare(convertV4MiniflareOptions({
  name: 'authbox-google-feasibility',
  modules: true,
  script: bundle.outputFiles[0].text,
  compatibilityDate: '2026-10-16',
  bindings: { PROBE_TOKEN: token, GOOGLE_TEST_CLIENT_ID: clientId, GOOGLE_TEST_JWKS: JSON.stringify(jwks) },
}));
try {
  for (const item of cases) {
    const response = await mf.dispatchFetch('http://localhost/verify', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: item.token, nonce }),
    });
    assert.equal(response.status, item.accepted ? 200 : 401, item.name);
    if (item.accepted) assert.deepEqual(await response.json(), { issuer: base.iss, subject: base.sub });
    else await response.text();
  }
} finally {
  await mf.dispose();
}
const report = {
  generatedAt: new Date().toISOString(),
  casesPerRuntime: cases.length,
  runtimes: ['Node WebCrypto', 'local workerd'],
  cases: cases.map((item) => ({ name: item.name, expected: item.accepted ? 'accept' : 'reject', passed: true })),
  identityKey: 'canonical issuer and subject; no email-based account merge',
  scope: 'synthetic local signing keys; actual Google OAuth, JWKS refresh and session replay consumption not yet validated',
};
await mkdir('.cache/feasibility/google', { recursive: true });
await writeFile('.cache/feasibility/google/report.json', `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
