const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });

export const utf8 = (value) => encoder.encode(value);
export const fromUtf8 = (value) => decoder.decode(value);
export const randomBytes = (size) => crypto.getRandomValues(new Uint8Array(size));

export function bytes(value, size) {
  if (!(value instanceof Uint8Array) || value.length !== size) throw new Error('Invalid byte length');
  return value;
}

export function base64url(value) {
  let binary = '';
  for (let offset = 0; offset < value.length; offset += 8192) binary += String.fromCharCode(...value.subarray(offset, offset + 8192));
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

export function unbase64url(value, minimum, maximum = minimum) {
  if (typeof value !== 'string' || value.length > Math.ceil(maximum * 4 / 3)
    || !/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) throw new Error('Invalid base64url');
  const decoded = Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (char) => char.charCodeAt(0));
  if (decoded.length < minimum || decoded.length > maximum || base64url(decoded) !== value) throw new Error('Invalid base64url');
  return decoded;
}

export function canonical(value, depth = 0) {
  if (depth > 32) throw new Error('JSON nesting limit exceeded');
  if (value === null || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'string') {
    if (value.isWellFormed() === false) throw new Error('Invalid Unicode');
    return JSON.stringify(value);
  }
  if (typeof value === 'number' && Number.isSafeInteger(value)) return JSON.stringify(value);
  if (Array.isArray(value)) {
    if (Object.keys(value).length !== value.length) throw new Error('Invalid JSON array');
    return `[${Array.from(value, (item) => canonical(item, depth + 1)).join(',')}]`;
  }
  if (value && Object.getPrototypeOf(value) === Object.prototype) {
    return `{${Object.keys(value).sort().map((key) => `${canonical(key, depth + 1)}:${canonical(value[key], depth + 1)}`).join(',')}}`;
  }
  throw new Error('Unsupported JSON value');
}

export function fields(value, expected) {
  if (!value || Object.getPrototypeOf(value) !== Object.prototype) throw new Error('Invalid object fields');
  const keys = Object.keys(value).sort();
  const required = [...expected].sort();
  if (keys.length !== required.length || keys.some((key, index) => key !== required[index])) throw new Error('Invalid object fields');
}

export function positive(value) {
  if (!Number.isSafeInteger(value) || value < 1) throw new Error('Invalid positive integer');
}

export function uuid(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
    throw new Error('Invalid UUID v4');
  }
}

export async function sha256(value) {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', value)));
}
