import { canonical, fromUtf8 } from '../../../packages/protocol/src/encoding.mjs';

export class ApiError extends Error {
  constructor(status, code, details = {}) { super(code); this.status = status; this.code = code; this.details = details; }
}

export function requireCondition(condition, status, code, details) {
  if (!condition) throw new ApiError(status, code, details);
}

export async function input(validate) {
  try { return await validate(); }
  catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(400, 'invalid_request'); }
}

export function json(value, status = 200) {
  return Response.json(value, { status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

export function failure(error) {
  return error instanceof ApiError ? json({ error: error.code, ...error.details }, error.status) : json({ error: 'internal_error' }, 500);
}

async function chunk(reader, deadline) {
  let timer;
  try {
    return await Promise.race([reader.read(), new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new ApiError(408, 'request_timeout')), Math.max(0, deadline - Date.now()));
    })]);
  } finally { clearTimeout(timer); }
}

export async function discardBody(request) {
  if (!request.body || request.bodyUsed || request.body.locked) return;
  const reader = request.body.getReader();
  let size = 0;
  const deadline = Date.now() + 1000;
  try {
    for (;;) {
      const { done, value } = await chunk(reader, deadline);
      if (done) break;
      size += value.length;
      if (size > 65536) break;
    }
  } catch {} finally { await reader.cancel(); reader.releaseLock(); }
}

export async function readJson(request) {
  requireCondition(request.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() === 'application/json', 415, 'json_required');
  requireCondition(!request.headers.has('Content-Encoding'), 415, 'unsupported_encoding');
  const maximum = 2 * 1024 * 1024 + 16384;
  const reader = request.body?.getReader();
  requireCondition(reader, 400, 'invalid_request');
  const chunks = [];
  let size = 0;
  const deadline = Date.now() + 10000;
  try {
    for (;;) {
      const { done, value } = await chunk(reader, deadline);
      if (done) break;
      size += value.byteLength;
      requireCondition(size <= maximum, 413, 'request_too_large');
      chunks.push(value);
    }
  } finally { await reader.cancel(); reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return input(() => {
    const text = fromUtf8(bytes);
    const parsed = JSON.parse(text);
    requireCondition(canonical(parsed) === text, 400, 'canonical_json_required');
    return parsed;
  });
}
