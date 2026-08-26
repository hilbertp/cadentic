/**
 * The provider seam (Epic 2 story 0). Everything above it — the endpoint, the prompt, the
 * validation and the re-request loop — is provider-blind: it hands over a prompt and gets
 * back either a parsed JSON answer or a typed [EngineError].
 *
 * That is what makes A→B a config swap rather than a rewrite, and what would make a
 * non-Anthropic provider a backend-only addition (PRD §8) without the app changing a byte.
 */

import type { EngineError } from '../errors.js';

export interface AskInput {
  systemPrompt: string;
  prompt: string;
  /** The model-facing response schema, so a provider can constrain output natively. */
  schema: Record<string, unknown>;
  signal: AbortSignal;
}

export interface AskResult {
  /** The model's answer, already parsed. Shape is the caller's problem to validate. */
  value: unknown;
  /**
   * Set when the answer could not be parsed as JSON at all — prose instead of an object, or
   * output truncated at the token ceiling. Not an [EngineError]: an unreadable answer is a
   * malformed answer, so it goes through the same corrective re-request as a schema failure
   * rather than ending the request outright.
   */
  parseError?: string;
  /** What actually served the request, for the plan's `generatedBy`. */
  model: string;
  /** Raw text, kept for the debug log when the answer turns out to be unusable. */
  raw: string;
  /** Estimated spend for this call, for the backend log. Never sent to the app. */
  costUsd?: number;
}

export interface Provider {
  /** Stamped into every plan's `generatedBy.mode`. */
  readonly mode: 'max-plan-oauth' | 'user-api-key';
  /** @throws {EngineError} — never a raw provider exception. */
  ask(input: AskInput): Promise<AskResult>;
}

export type { EngineError };
