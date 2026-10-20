import { buildChrome } from '../bundling/chrome.mjs';
const origin = process.argv.find((arg) => arg.startsWith('--api-origin='))?.slice('--api-origin='.length) ?? process.env.AUTHBOX_API_ORIGIN;
const result = await buildChrome(origin ? { origin } : {});
console.log(JSON.stringify({ directory: result.directory, extensionId: result.id, origin: result.origin }));
