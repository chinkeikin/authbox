import probe from './worker.mjs';

export default {
  async fetch(request, env) {
    const expiresAt = Number(env.PROBE_EXPIRES_AT);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      return new Response('Probe expired', { status: 503, headers: { 'X-AuthBox-Probe': 'synthetic-v1' } });
    }
    const response = await probe.fetch(request, env);
    response.headers.set('X-AuthBox-Probe', 'synthetic-v1');
    return response;
  },
};
