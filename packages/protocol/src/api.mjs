import { canonical, sha256, utf8 } from './encoding.mjs';

export function authenticationContext(operation, body) {
  if (operation === 'auth.register') return { username: body.username, device: body.device, recoveryHashes: body.recoveryHashes, backup: body.backup };
  if (operation === 'auth.recover') return { username: body.username, device: body.device };
  if (operation === 'auth.device-login') return { username: body.username, deviceId: body.deviceId };
  throw new Error('Unsupported authentication operation');
}

export const operationDigest = (value) => sha256(utf8(canonical(value)));
