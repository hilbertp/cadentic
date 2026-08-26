/**
 * Copies the shared contract next to the compiled output so `dist/` is self-contained.
 * In development `src/contract.ts` reads it straight from `contracts/` at the repo root —
 * there is one canonical file either way, and it is never edited in two places.
 */

import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, 'dist', 'contracts');

mkdirSync(target, { recursive: true });
copyFileSync(
  join(root, '..', 'contracts', 'mesocycle-api.schema.json'),
  join(target, 'mesocycle-api.schema.json'),
);

console.log('contract → dist/contracts/mesocycle-api.schema.json');
