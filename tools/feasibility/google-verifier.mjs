import { createRemoteJWKSet, jwtVerify } from 'jose';

const googleKeys = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));

export function createGoogleVerifier({ clientIds, keySet = googleKeys }) {
  if (!Array.isArray(clientIds) || clientIds.length === 0 || clientIds.some((id) => typeof id !== 'string' || !id.endsWith('.apps.googleusercontent.com'))) {
    throw new Error('Explicit Google OAuth client IDs required');
  }
  return async (token, expectedNonce) => {
    if (typeof token !== 'string' || token.length > 16384 || typeof expectedNonce !== 'string' || expectedNonce.length < 16) {
      throw new Error('Invalid token or nonce input');
    }
    const { payload } = await jwtVerify(token, keySet, {
      algorithms: ['RS256'],
      issuer: ['https://accounts.google.com', 'accounts.google.com'],
      audience: clientIds,
      requiredClaims: ['iss', 'aud', 'sub', 'iat', 'exp', 'nonce'],
      maxTokenAge: '10m',
      clockTolerance: 30,
    });
    if (typeof payload.sub !== 'string' || payload.sub.length === 0 || payload.sub.length > 255) {
      throw new Error('Invalid subject');
    }
    if (payload.nonce !== expectedNonce || payload.exp <= payload.iat) {
      throw new Error('Invalid nonce or lifetime');
    }
    if ((Array.isArray(payload.aud) && payload.aud.length > 1 && !payload.azp)
      || (payload.azp !== undefined && !clientIds.includes(payload.azp))) {
      throw new Error('Invalid authorized party');
    }
    return { issuer: 'https://accounts.google.com', subject: payload.sub };
  };
}
