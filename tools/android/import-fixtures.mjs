import { writeFile, mkdir } from 'node:fs/promises';
import { Secret } from 'otpauth/slim';
import { parseTotpUri } from '../../packages/protocol/src/totp.mjs';
import QRCode from 'qrcode';

const secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
const varint = (input) => {
  let value = BigInt.asUintN(64, BigInt(input)); const result = [];
  do { result.push(Number(value & 127n) | (value > 127n ? 128 : 0)); value >>= 7n; } while (value);
  return Buffer.from(result);
};
const integer = (field, value) => Buffer.concat([varint(field * 8), varint(value)]);
const bytes = (field, value) => Buffer.concat([varint(field * 8 + 2), varint(value.length), value]);
const string = (field, value) => bytes(field, Buffer.from(value));
const entry = ({ name = 'Example:alice@example.invalid', issuer = 'Example', algorithm = 1, digits = 1, type = 2, counter = 0 } = {}) =>
  Buffer.concat([bytes(1, Secret.fromBase32(secret).bytes), string(2, name), string(3, issuer), integer(4, algorithm), integer(5, digits), integer(6, type), ...(counter ? [integer(7, counter)] : [])]);
const page = (index, { size = 2, batch = 991, version = 1, entries = [entry({ name: `Example:part${index}@example.invalid`, algorithm: index + 1, digits: 2 })] } = {}) =>
  Buffer.concat([...entries.map((value) => bytes(1, value)), integer(2, version), integer(3, size), integer(4, index), integer(5, batch)]);
const uri = (value) => 'otpauth-migration://offline?data=' + encodeURIComponent(value.toString('base64'));
const standards = [
  `otpauth://totp/Example:alice%40example.invalid?secret=${secret}&issuer=Example`,
  `otpauth://totp/%E6%B5%8B%E8%AF%95:%E8%B4%A6%E5%8F%B7?secret=${secret.toLowerCase()}&algorithm=SHA512&digits=8&period=60`,
  `otpauth://totp/alice%2Btest?secret=${secret}&algorithm=SHA256&digits=8&period=45`,
].map((encoded) => ({ encoded, expected: parseTotpUri(encoded) }));
const migration = [uri(page(0, { entries: [entry(), entry({ name: 'Counter', type: 1 })] })), uri(page(1))];
const invalid = [standards[0].encoded + '&secret=' + secret, standards[0].encoded + '&issuer=Other',
  standards[0].encoded.replace('totp/', 'hotp/'), `https://example.invalid/?secret=${secret}`,
  standards[0].encoded + '&digits=7', standards[0].encoded + '&period=030', standards[0].encoded + '#fragment',
  standards[0].encoded.replace('alice%40example.invalid', '%ff'),
  uri(page(0, { version: 2 })), uri(page(2)), uri(page(0, { size: 101 })), uri(Buffer.concat([page(0), integer(2, 1)])),
  uri(Buffer.from([10, 255, 255])), 'otpauth-migration://offline?data=invalid**'];
await mkdir('apps/android/app/build/test-fixtures', { recursive: true });
await writeFile('apps/android/app/build/test-fixtures/import.json', JSON.stringify({ warning: 'PUBLIC SYNTHETIC DATA ONLY', standards, migration, single: uri(page(0, { size: 1 })),
  differentBatch: uri(page(1, { batch: 992 })), conflictingPage: uri(page(0, { entries: [entry({ name: 'Changed' })] })), invalid }));
console.log('Public standard OTP and independent protobuf migration fixtures prepared');
const images = 'apps/android/app/build/test-fixtures/qr';
await mkdir(images, { recursive: true });
for (const [name, encoded] of [['standard', standards[0].encoded], ['page0', migration[0]], ['page1', migration[1]],
  ['camera', `otpauth://totp/AuthBox%20Camera:camera%40example.invalid?secret=${secret}&issuer=AuthBox%20Camera&digits=8&algorithm=SHA256`]]) {
  await QRCode.toFile(`${images}/${name}.png`, encoded, { width: 768, margin: 4, errorCorrectionLevel: 'M' });
}
console.log('Independent Node QR PNG fixtures prepared');
