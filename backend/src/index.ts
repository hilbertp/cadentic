/**
 * Backend entry point. Everything that can be wrong with the configuration is wrong *here*,
 * before the socket opens — a backend that starts is a backend that can serve.
 */

import { existsSync } from 'node:fs';

import { assertModeAReady, describeCredential, loadConfig, modeACredentialSource } from './config.js';
import { assertContractAgreement } from './contract.js';
import { PROMPT_VERSION } from './generate.js';
import { providerFor } from './provider/index.js';
import { createEngineServer } from './server.js';

/** Cloud Run sets K_SERVICE; every Docker container has /.dockerenv. */
const inContainer = (): boolean => Boolean(process.env.K_SERVICE) || existsSync('/.dockerenv');

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
          `  Credential: ${describeCredential(modeACredentialSource())}\n` +
          '  Supported for personal and development use only. A deployment serving accounts\n' +
          '  other than the owner\'s must run Mode B.',
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
    // Binding all interfaces is correct in a container — the runtime reaches it through its
    // own proxy and there is no other network exposure — and wrong on a laptop, where it
    // offers the endpoint to everything on the Wi-Fi. `K_SERVICE` marks Cloud Run and
    // `/.dockerenv` marks a plain container, so the warning fires only where it means
    // something. Checking Cloud Run alone was not enough: local `docker run` tripped it.
    if (config.host === '0.0.0.0' && !inContainer()) {
      console.warn(
        'WARNING: bound to 0.0.0.0 outside a container. The dev server is meant for the ' +
          'private network only — set HOST to your LAN address instead.',
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
