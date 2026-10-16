import { Secret, TOTP } from 'otpauth/slim';

const algorithms = new Set(['SHA1', 'SHA256', 'SHA512']);
const parameters = new Set(['secret', 'issuer', 'algorithm', 'digits', 'period']);

export function normalizeSecret(value) {
  if (typeof value !== 'string' || value.length > 208 || !/^[A-Za-z2-7]+={0,6}$/.test(value)) {
    throw new Error('Invalid Base32 secret');
  }
  const upper = value.toUpperCase();
  const unpadded = upper.replace(/=+$/, '');
  const secret = Secret.fromBase32(unpadded);
  if (secret.base32 !== unpadded || secret.bytes.length < 10 || secret.bytes.length > 128
    || (upper.includes('=') && upper !== unpadded.padEnd(Math.ceil(unpadded.length / 8) * 8, '='))) {
    throw new Error('Invalid Base32 secret');
  }
  return unpadded;
}

function text(value, required = false) {
  if (typeof value !== 'string' || value.length > 256 || /[\u0000-\u001f\u007f]/.test(value)
    || (required && value.trim().length === 0)) throw new Error('Invalid TOTP label');
  return value;
}

export function validateTotp(value) {
  if (!value || !algorithms.has(value.algorithm) || ![6, 8].includes(value.digits)
    || !Number.isInteger(value.period) || value.period < 1 || value.period > 86400) {
    throw new Error('Unsupported TOTP parameters');
  }
  return { secret: normalizeSecret(value.secret), algorithm: value.algorithm, digits: value.digits, period: value.period };
}

export function parseTotpUri(value) {
  if (typeof value !== 'string' || value.length > 4096 || /[\s\u0000-\u001f\u007f]/.test(value)
    || /%(?![0-9a-f]{2})/i.test(value)) throw new Error('Invalid TOTP URI');
  const uri = new URL(value);
  if (uri.protocol !== 'otpauth:' || uri.hostname !== 'totp' || uri.username || uri.password || uri.port
    || uri.hash || !uri.pathname.startsWith('/')) throw new Error('Unsupported OTP URI');
  for (const key of uri.searchParams.keys()) {
    if (!parameters.has(key) || uri.searchParams.getAll(key).length !== 1) throw new Error('Unsupported or repeated OTP parameter');
  }
  const label = decodeURIComponent(uri.pathname.slice(1));
  const separator = label.indexOf(':');
  const labelIssuer = separator < 0 ? '' : text(label.slice(0, separator), true);
  const account = text(separator < 0 ? label : label.slice(separator + 1).trimStart(), true);
  if (account.includes(':')) throw new Error('Invalid TOTP label');
  const issuer = text(uri.searchParams.get('issuer') ?? labelIssuer);
  if (labelIssuer && labelIssuer !== issuer) throw new Error('Conflicting TOTP issuer');
  const integer = (name, fallback) => {
    const raw = uri.searchParams.get(name);
    if (raw === null) return fallback;
    if (!/^[1-9][0-9]{0,4}$/.test(raw)) throw new Error('Invalid TOTP parameter');
    return Number(raw);
  };
  return { issuer, account, ...validateTotp({ secret: uri.searchParams.get('secret'),
    algorithm: uri.searchParams.get('algorithm') ?? 'SHA1', digits: integer('digits', 6), period: integer('period', 30) }) };
}

export function generateTotp(value, timestamp = Date.now()) {
  if (!Number.isSafeInteger(timestamp) || timestamp < 0) throw new Error('Invalid timestamp');
  const config = validateTotp(value);
  const code = TOTP.generate({ ...config, secret: Secret.fromBase32(config.secret), timestamp });
  const periodMs = config.period * 1000;
  const validUntil = timestamp - timestamp % periodMs + periodMs;
  return { code, remainingMs: periodMs - timestamp % periodMs, validUntil };
}
