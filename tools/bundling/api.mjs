import { readFile, writeFile } from 'node:fs/promises';
import { buildStaticWasm } from './static-wasm.mjs';

export async function buildApi({ fixture = false, directory = fixture ? '.cache/api/bundle' : 'dist/api' } = {}) {
  const notices = await readFile('THIRD-PARTY-NOTICES.txt', 'utf8');
  const bundle = await buildStaticWasm(fixture ? 'services/api/test/fixture-worker.mjs' : 'services/api/src/worker.mjs', { directory, notices });
  await writeFile(`${directory}/worker.mjs`, bundle.script);
  await writeFile(`${directory}/THIRD-PARTY-NOTICES.txt`, notices);
  return bundle;
}
