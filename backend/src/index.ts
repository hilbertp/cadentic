/**
 * Backend entry point. Everything that can be wrong with the configuration is wrong *here*,
 * before the socket opens — a backend that starts is a backend that can serve.
 */

import { assertModeAReady, loadConfig } from './config.js';
import { assertContractAgreement } from './contract.js';
import { PROMPT_VERSION } from './generate.js';
import { providerFor } from './provider/index.js';
import { createEngineServer } from './server.js';

function main(): void {
  assertContractAgreement();

  const config = loadConfig();
  assertModeAReady(config);

  const server = createEngineServer({ config, provider: providerFor(config) });

  server.listen(config.port, config.host, () => {
    // Story 2: the personal-use constraint is logged at startup, every time, so it is never
    // a thing someone forgot was true of a running process.
    if (config.mode === 'A') {
      console.log(
        'MODE A — every request bills the owner\'s Claude subscription.\n' +
          '  Supported for personal and development use only. A deployment serving accounts\n' +
          '  other than the token owner\'s must run Mode B.',
      );
    } else {
      console.log('MODE B — user-supplied API keys. (Story 6: not implemented; requests return provider-not-available.)');
    }
    console.log(
      `Cadentic Mesocycle Engine on http://${config.host}:${config.port} · ` +
        `model ${config.model} · prompt v${PROMPT_VERSION} · ` +
        `timeout ${Math.round(config.requestTimeoutMs / 1000)}s · ` +
        `startDate window ${config.startDateWindowDays}d`,
    );
    if (config.host === '0.0.0.0') {
      console.warn(
        'WARNING: bound to 0.0.0.0. The dev server is meant for the private network only — ' +
          'set HOST to your LAN address instead.',
      );
    }
  });

  const stop = () => server.close(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

try {
  main();
} catch (e) {
  console.error(`\nCannot start:\n\n  ${e instanceof Error ? e.message : String(e)}\n`);
  process.exit(1);
}
