import { Secret } from 'otpauth/slim';

const seconds = [59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000];
const outputs = {
  SHA1: ['94287082', '07081804', '14050471', '89005924', '69279037', '65353130'],
  SHA256: ['46119246', '68084774', '67062674', '91819424', '90698825', '77737706'],
  SHA512: ['90693936', '25091201', '99943326', '93441116', '38618901', '47863826'],
};
const secrets = { SHA1: '12345678901234567890', SHA256: '12345678901234567890123456789012',
  SHA512: '1234567890123456789012345678901234567890123456789012345678901234' };

export const rfcTotpVectors = Object.entries(outputs).flatMap(([algorithm, codes]) =>
  codes.map((code, index) => ({ algorithm, seconds: seconds[index], code, secret: Secret.fromUTF8(secrets[algorithm]).base32 })));
