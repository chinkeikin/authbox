import { verifyProtocol } from './device.mjs';
await verifyProtocol({ runTests: !process.argv.includes('--read-only') });
