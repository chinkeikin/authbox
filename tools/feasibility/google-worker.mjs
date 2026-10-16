import { createLocalJWKSet } from 'jose';
import { createGoogleVerifier } from './google-verifier.mjs';

export default {
  async fetch(request, env) {
    if (!env.PROBE_TOKEN || request.headers.get('Authorization') !== `Bearer ${env.PROBE_TOKEN}`) {
      return new Response('Unauthorized', { status: 401 });
    }
    if (request.method !== 'POST' || new URL(request.url).pathname !== '/verify') {
      return new Response('Not found', { status: 404 });
    }
    const body = await request.text();
    if (body.length > 20000) return new Response('Payload too large', { status: 413 });
    try {
      const input = JSON.parse(body);
      const verify = createGoogleVerifier({
        clientIds: [env.GOOGLE_TEST_CLIENT_ID],
        keySet: createLocalJWKSet(JSON.parse(env.GOOGLE_TEST_JWKS)),
      });
      const identity = await verify(input.token, input.nonce);
      return Response.json(identity, { headers: { 'Cache-Control': 'no-store' } });
    } catch {
      return new Response('Invalid identity token', { status: 401 });
    }
  },
};
