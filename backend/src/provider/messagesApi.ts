/**
 * Mode B — the user's own Anthropic API key, through the Messages API (Epic 2 story 6).
 *
 * **Not built yet, and this file is the "now" half of story 6.** Selecting `AUTH_MODE=B`
 * gets the typed `provider-not-available` error the app already knows how to render, rather
 * than a 500 or a silent fall-back to the owner's subscription. That last part is the point:
 * there is no path in this backend by which a Mode B deployment quietly bills Mode A.
 *
 * What lands here when story 6 ships:
 *
 * - The official `@anthropic-ai/sdk`, `client.messages.create` with
 *   `output_config.format` carrying the same `planDraftSchema` Mode A hands to the Agent
 *   SDK — one schema, both modes.
 * - The key arrives per request from the app (Android Keystore-backed storage, TLS only,
 *   never logged, never persisted here) and is used for that call alone.
 * - `mode: 'user-api-key'` in the plan's `generatedBy`, so a persisted plan always says
 *   which billing path produced it.
 *
 * Nothing above this file changes: the endpoint contract, the prompt, the validation and the
 * re-request loop are all provider-blind. That is what "A→B is a config swap" has to mean.
 *
 * Mode B routing stays **backend-only**. A direct-from-app variant would duplicate the prompt
 * template and the validation client-side and break exactly that property — it is an owner
 * decision, not an implementor one (Epic 2, open points).
 */

import { EngineError } from '../errors.js';
import type { AskInput, AskResult, Provider } from './types.js';

export class MessagesApiProvider implements Provider {
  readonly mode = 'user-api-key' as const;

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  async ask(_input: AskInput): Promise<AskResult> {
    throw new EngineError(
      'provider-not-available',
      'This backend is configured for Mode B (user-supplied API key), which is not built yet. ' +
        'Run it with AUTH_MODE=A to generate against the owner\'s subscription.',
    );
  }
}
