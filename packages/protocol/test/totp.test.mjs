import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { test } from 'node:test';
import { Secret } from 'otpauth/slim';
import { generateTotp, normalizeSecret, parseTotpUri } from '../src/totp.mjs';
import { rfcTotpVectors } from './vectors.mjs';

for (const vector of rfcTotpVectors) {
  test(`RFC 6238 ${vector.algorithm} at ${vector.seconds}`, () => {
    const config = { secret: vector.secret, algorithm: vector.algorithm, digits: 8, period: 30 };
    assert.equal(generateTotp(config, vector.seconds * 1000).code, vector.code);
    assert.equal(generateTotp({ ...config, digits: 6 }, vector.seconds * 1000).code, vector.code.slice(-6));
  });
}

const uri = 'otpauth://totp/Example:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example';
test('URI defaults, Unicode labels, explicit parameters and padded Base32', () => {
  assert.deepEqual(parseTotpUri(uri), { issuer: 'Example', account: 'alice@example.com',
    secret: 'JBSWY3DPEHPK3PXP', algorithm: 'SHA1', digits: 6, period: 30 });
  const config = parseTotpUri('otpauth://totp/%E6%B5%8B%E8%AF%95:%E8%B4%A6%E5%8F%B7?secret=jbswy3dpehpk3pxp&algorithm=SHA512&digits=8&period=60');
  assert.equal(config.issuer, '测试');
  assert.equal(config.account, '账号');
  assert.equal(config.secret, 'JBSWY3DPEHPK3PXP');
  assert.equal(config.period, 60);
  const secret = Secret.fromUTF8('12345678901').base32;
  assert.equal(normalizeSecret(secret.padEnd(24, '=')), secret);
});

test('time boundary, six digit leading zero, non-default period and 64-bit counter', () => {
  const config = parseTotpUri(uri);
  assert.equal(generateTotp(config, 29999).remainingMs, 1);
  assert.equal(generateTotp(config, 30000).remainingMs, 30000);
  assert.equal(generateTotp(config, 30000).validUntil, 60000);
  const rfc = rfcTotpVectors.find((vector) => vector.algorithm === 'SHA1' && vector.seconds === 1111111109);
  assert.equal(generateTotp({ ...config, secret: rfc.secret }, rfc.seconds * 1000).code, '081804');
  for (const seconds of [59, 60, 200000000000]) {
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(Math.floor(seconds / 60)));
    const hmac = createHmac('sha256', Secret.fromBase32(config.secret).bytes).update(counter).digest();
    const offset = hmac[hmac.length - 1] & 15;
    const expected = ((hmac.readUInt32BE(offset) & 0x7fffffff) % 100000000).toString().padStart(8, '0');
    assert.equal(generateTotp({ ...config, algorithm: 'SHA256', digits: 8, period: 60 }, seconds * 1000).code, expected);
  }
  for (const timestamp of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => generateTotp(config, timestamp));
  }
});

test('reject HOTP, external links, ambiguous parameters and unsupported configurations', () => {
  for (const invalid of [
    uri.replace('//totp/', '//hotp/'), uri.replace('otpauth:', 'https:'), uri + '&counter=1', uri + '#fragment',
    uri + '&secret=JBSWY3DPEHPK3PXP', uri + '&issuer=Other', uri.replace('Example:', 'Other:'),
    uri + '&algorithm=MD5', uri + '&digits=7', uri + '&period=0', uri + '&period=86401',
    uri + '&period=3e1', uri + '&period=030', uri.replace('alice%40example.com', ''),
    uri.replace('alice%40example.com', '%00alice'), uri.replace('alice%40example.com', '%GG'),
    uri.replace('alice%40example.com', 'alice:another'), uri.replace('//totp/', '//user@totp/'),
    uri.replace('JBSWY3DPEHPK3PXP', 'INVALID0189'), uri + '&unknown=1', ' ' + uri,
  ]) assert.throws(() => parseTotpUri(invalid), 'Accepted invalid URI');
});

test('reject noncanonical Base32, incomplete bytes and oversized input', () => {
  for (const invalid of ['A', 'JBSWY3DPEHPK3PXP=', 'JBSWY3DPEHPK3PXPA', 'JBSWY3DPEHPK3PXPAB',
    'JBSWY3DP EHPK3PXP', 'A'.repeat(209), '', null]) {
    assert.throws(() => normalizeSecret(invalid), 'Accepted invalid secret');
  }
});
