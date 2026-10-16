import { failure, json } from './http.mjs';
export { InstallationStore } from './store.mjs';

export default {
  async fetch(request, env) {
    let origin;
    let allowed;
    try {
      origin = new URL(env.API_ORIGIN);
      if (origin.protocol !== 'https:' || origin.origin !== env.API_ORIGIN) return json({ error: 'service_not_configured' }, 503);
      allowed = JSON.parse(env.CLIENT_ORIGINS ?? '[]');
      if (!Array.isArray(allowed) || allowed.length > 16 || allowed.some((value) => typeof value !== 'string' || !/^(https:\/\/|chrome-extension:\/\/)/.test(value))) return json({ error: 'service_not_configured' }, 503);
    } catch { return json({ error: 'service_not_configured' }, 503); }
    const url = new URL(request.url);
    if (url.origin !== origin.origin) return json({ error: 'wrong_origin' }, 400);
    const clientOrigin = request.headers.get('Origin');
    if (clientOrigin && !allowed.includes(clientOrigin)) return json({ error: 'origin_forbidden' }, 403);
    let response;
    if (request.method === 'OPTIONS') {
      const method = request.headers.get('Access-Control-Request-Method');
      const headers = (request.headers.get('Access-Control-Request-Headers') ?? '').toLowerCase().split(',').map((value) => value.trim()).filter(Boolean);
      response = clientOrigin && ['GET', 'POST'].includes(method) && headers.every((value) => ['authorization', 'content-type'].includes(value))
        ? new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store', 'Access-Control-Allow-Methods': 'GET, POST', 'Access-Control-Allow-Headers': 'Authorization, Content-Type' } })
        : json({ error: 'preflight_forbidden' }, 403);
    } else if (url.pathname === '/health' && request.method === 'GET' && !url.search) response = json({ service: 'authbox-api', protocol: 1 });
    else if (!url.pathname.startsWith('/v1/')) response = json({ error: 'not_found' }, 404);
    else {
      try { response = await env.INSTALLATION.get(env.INSTALLATION.idFromName('installation-v1')).fetch(request); }
      catch { response = json({ error: 'service_unavailable' }, 503); }
    }
    try {
      const output = new Response(response.body, response);
      if (clientOrigin) {
        output.headers.set('Access-Control-Allow-Origin', clientOrigin);
        output.headers.set('Vary', 'Origin');
      }
      return output;
    } catch (error) { return failure(error); }
  },
};
