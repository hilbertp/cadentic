/**
 * Provider selection (Epic 2 story 0). Auth mode is backend config; the app's request is
 * byte-identical either way and it never learns which side of this branch served it.
 */

import type { Config } from '../config.js';
import { AgentSdkProvider } from './agentSdk.js';
import { MessagesApiProvider } from './messagesApi.js';
import type { Provider } from './types.js';

export function providerFor(config: Config): Provider {
  return config.mode === 'A' ? new AgentSdkProvider(config.model) : new MessagesApiProvider();
}

export type { AskInput, AskResult, Provider } from './types.js';
