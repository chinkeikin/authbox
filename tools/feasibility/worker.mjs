import { argon2id as nobleArgon2id } from '@noble/hashes/argon2.js';
import { argon2id as wasmArgon2id } from 'hash-wasm';
import {
  fixturePassword,
  fixtureSalt,
  hex,
  rfcProfile,
  serverProfile,
  wasmOptions,
} from './fixtures.mjs';

export default {
  async fetch(request, env) {
    if (!env.PROBE_TOKEN || env.PROBE_TOKEN.length < 32) {
      return new Response('Probe not configured', { status: 503 });
    }
    if (request.headers.get('Authorization') !== `Bearer ${env.PROBE_TOKEN}`) {
      return new Response('Unauthorized', { status: 401 });
    }
    if (request.method !== 'POST') {
      return new Response('Method not allowed', { status: 405 });
    }
    const action = new URL(request.url).pathname;
    const headers = { 'Cache-Control': 'no-store' };
    try {
      if (action === '/rfc') {
        const hash = nobleArgon2id(new Uint8Array(32).fill(1), new Uint8Array(16).fill(2), rfcProfile);
        return Response.json({ hash: hex(hash) }, { headers });
      }
      if (action === '/noble') {
        const hash = nobleArgon2id(fixturePassword, fixtureSalt, serverProfile);
        return Response.json({ implementation: 'noble', hash: hex(hash), profile: serverProfile }, { headers });
      }
      if (action === '/wasm') {
        const hash = await wasmArgon2id(wasmOptions(serverProfile));
        return Response.json({ implementation: 'hash-wasm', hash, profile: serverProfile }, { headers });
      }
      return new Response('Not found', { status: 404 });
    } catch (error) {
      return Response.json({ error: error.message }, { status: 422, headers });
    }
  },
};
