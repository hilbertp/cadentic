/**
 * The app-facing error vocabulary (Epic 2 story 0). Every failure leaves the backend as one
 * of these codes with a message that is safe to show and safe to log — never a stack trace,
 * never a provider internal, never anything derived from the OAuth token.
 *
 * The codes themselves live in `contracts/mesocycle-api.schema.json`; `ERROR_CODES` below is
 * asserted against that file at startup, so the two cannot drift.
 */

export const ERROR_CODES = [
  'payload-invalid',
  'unauthorized',
  'provider-not-available',
  'provider-unreachable',
  'rate-limited',
  'timeout',
  'format-failed',
  'auth-failed',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

/**
 * HTTP status per code. 501 for provider-not-available says "this backend is configured for a
 * mode it cannot serve" — a deployment fact, not something the app can retry its way out of.
 */
const STATUS: Record<ErrorCode, number> = {
  'payload-invalid': 400,
  unauthorized: 401,
  'rate-limited': 429,
  'provider-not-available': 501,
  'provider-unreachable': 502,
  'format-failed': 502,
  'auth-failed': 502,
  timeout: 504,
};

export interface ErrorDetail {
  retryAfterSeconds?: number;
  /** Named field errors, in Epic 1 story 6's style. Only on payload-invalid. */
  fields?: string[];
}

export class EngineError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
    readonly detail: ErrorDetail = {},
  ) {
    super(message);
    this.name = 'EngineError';
  }

  get status(): number {
    return STATUS[this.code];
  }

  toBody() {
    return {
      error: {
        code: this.code,
        message: this.message,
        ...(this.detail.retryAfterSeconds !== undefined
          ? { retryAfterSeconds: this.detail.retryAfterSeconds }
          : {}),
        ...(this.detail.fields ? { fields: this.detail.fields } : {}),
      },
    };
  }
}

export const isEngineError = (e: unknown): e is EngineError => e instanceof EngineError;
