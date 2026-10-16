export default {
  fetch(request, env) {
    const headers = { 'X-AuthBox-Probe': 'minimal-v1', 'Cache-Control': 'no-store' };
    const expiresAt = Number(env.PROBE_EXPIRES_AT);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      return new Response('Probe expired', { status: 503, headers });
    }
    if (request.method !== 'GET') return new Response('Method not allowed', { status: 405, headers });
    if (new URL(request.url).pathname !== '/') return new Response('Not found', { status: 404, headers });
    return new Response('AuthBox minimal public fixture', { headers });
  },
};
