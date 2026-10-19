import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { checkDevice, instrument, artifact, removeArtifacts } from './device.mjs';

await checkDevice();
await removeArtifacts(['protection-device-result.json', 'quick-device-result.json']);
await instrument('LocalProtectionDeviceTest');
const device = await artifact('protection-device-result.json');
await instrument('LocalProtectionTest');
await instrument('ClipboardTimerTest');
if (device.deviceSecure && device.systemAuthenticationSupported) await instrument('QuickUnlockStoreTest');
else {
  assert.equal(device.deviceSecure, false, 'This device needs separate legacy Android compatibility validation');
  await instrument('QuickUnlockUnavailableTest');
  const rejected = await artifact('quick-device-result.json');
  assert.equal(rejected.deviceSecure, false);
  assert.equal(rejected.positiveAuthenticationVerified, false);
}
const report = { generatedAt: new Date().toISOString(), model: 'PHB110', ...device,
  lockAndClipboardChecksPassed: true, positiveAuthenticationVerified: false,
  authenticationChecks: device.deviceSecure ? 'Keystore rejects unauthenticated use; real authentication still needs user verification'
    : 'Missing secure lock is refused; password unlock stays available' };
await mkdir('.cache/android', { recursive: true });
await writeFile('.cache/android/protection-report.json', JSON.stringify(report, null, 2) + '\n');
await removeArtifacts(['protection-device-result.json', 'quick-device-result.json']);
console.log(JSON.stringify(report));
