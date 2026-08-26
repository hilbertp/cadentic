/**
 * Mode A — the owner's Claude subscription, through the Agent SDK (Epic 2 story 2).
 *
 * Why the Agent SDK and not a plain HTTPS call: the raw Messages API rejects subscription
 * tokens outright ("OAuth authentication is currently not supported"). A subscription is
 * reachable programmatically only through Claude Code, and the Agent SDK is Claude Code as a
 * library. There is no lighter shortcut to try.
 *
 * The token never leaves this process. It is read from the environment by the SDK itself —
 * this file never reads it, never copies it, and never puts it anywhere a log could reach.
 *
 * Two options here are deliberate and worth not "simplifying" away:
 *
 * - `settingSources: []` — SDK isolation. Without it the SDK loads whatever
 *   `~/.claude/settings.json` and the working directory's `CLAUDE.md` happen to contain, and
 *   an unrelated file on the host machine would silently become part of every athlete's
 *   prompt. The standard prompt must be the whole prompt.
 * - `allowedTools: []` with `permissionMode: 'dontAsk'` — this is a reasoning task with no
 *   filesystem or network in it. Nothing is pre-approved, and a tool attempt is denied
 *   immediately instead of hanging on a prompt no one is there to answer.
 */

import { AbortError, query, USAGE_LIMIT_ERROR_PREFIXES } from '@anthropic-ai/claude-agent-sdk';

import { EngineError } from '../errors.js';
import type { AskInput, AskResult, Provider } from './types.js';

/**
 * `SDKAssistantMessage.error` values, mapped onto the app's vocabulary. Anything unlisted
 * falls through to `provider-unreachable` — the honest answer for "we asked Claude and did
 * not get a plan back".
 */
const ASSISTANT_ERRORS: Record<string, { code: EngineError['code']; message: string }> = {
  authentication_failed: {
    code: 'auth-failed',
    message:
      'The backend\'s Claude credential was rejected. The setup token may have expired or ' +
      'been revoked — mint a new one with `claude setup-token` and restart the backend.',
  },
  oauth_org_not_allowed: {
    code: 'auth-failed',
    message:
      'The backend\'s Claude credential belongs to an organization that is not allowed to ' +
      'use it. Re-run `claude setup-token` under the intended account.',
  },
  account_on_hold: {
    code: 'auth-failed',
    message: 'The Claude account behind this backend is on hold and cannot make requests.',
  },
  billing_error: {
    code: 'auth-failed',
    message: 'The Claude account behind this backend cannot be billed for this request.',
  },
  rate_limit: {
    code: 'rate-limited',
    message: 'The Claude subscription has hit its usage window. Generation will work again once it resets.',
  },
  overloaded: {
    code: 'rate-limited',
    message: 'Claude is overloaded right now. Try generating again in a moment.',
  },
  server_error: {
    code: 'provider-unreachable',
    message: 'Claude returned a server error while planning the mesocycle.',
  },
  model_not_found: {
    code: 'provider-unreachable',
    message: 'The configured model is not available to this backend\'s credential.',
  },
  invalid_request: {
    code: 'provider-unreachable',
    message: 'Claude rejected the generation request.',
  },
};

/**
 * A subscription usage-window hit arrives as prose on a *successful* turn, not as an error
 * flag, so the epic's "usage-window hit surfaces as rate-limited" needs this text check. The
 * prefixes are the SDK's own exported list, so they track the CLI rather than a copy here.
 */
const isUsageLimitText = (text: string): boolean =>
  USAGE_LIMIT_ERROR_PREFIXES.some((p) => text.trimStart().startsWith(p));

export class AgentSdkProvider implements Provider {
  readonly mode = 'max-plan-oauth' as const;

  constructor(private readonly model: string) {}

  async ask({ systemPrompt, prompt, schema, signal }: AskInput): Promise<AskResult> {
    const abortController = new AbortController();
    const forward = () => abortController.abort();
    signal.addEventListener('abort', forward, { once: true });

    try {
      const stream = query({
        prompt,
        options: {
          model: this.model,
          systemPrompt,
          // The schema the answer is validated against is the schema the model is given.
          outputFormat: { type: 'json_schema', schema },
          allowedTools: [],
          permissionMode: 'dontAsk',
          maxTurns: 1,
          settingSources: [],
          abortController,
        },
      });

      let assistantError: string | undefined;

      for await (const message of stream) {
        if (message.type === 'assistant' && message.error) {
          // Remembered, not thrown: the result message that follows carries the detail, and
          // throwing here would leave the subprocess mid-stream.
          assistantError = message.error;
          continue;
        }

        if (message.type !== 'result') continue;

        if (message.subtype !== 'success') {
          throw this.resultError(message.subtype, (message as any).errors, assistantError);
        }

        if (assistantError || message.is_error) {
          throw this.assistantError(assistantError ?? 'unknown');
        }

        return this.readAnswer(message);
      }

      // The stream ended without a result. Nothing to report but the absence itself.
      throw assistantError
        ? this.assistantError(assistantError)
        : new EngineError(
            'provider-unreachable',
            'Claude ended the session without returning a plan.',
          );
    } catch (e) {
      throw this.translate(e);
    } finally {
      signal.removeEventListener('abort', forward);
    }
  }

  /**
   * `structured_output` is the authoritative answer when the SDK's json_schema output format
   * produced one. The text `result` is the fallback for the case where it did not — and an
   * unparseable fallback is reported as a parse failure, not an error, so the caller's
   * corrective re-request gets its turn.
   */
  private readAnswer(message: any): AskResult {
    const raw =
      typeof message.result === 'string' ? message.result : JSON.stringify(message.structured_output);
    const base = {
      model: this.model,
      raw,
      costUsd: message.total_cost_usd as number | undefined,
    };

    if (message.structured_output !== undefined && message.structured_output !== null) {
      return { ...base, value: message.structured_output };
    }

    const text = typeof message.result === 'string' ? message.result.trim() : '';

    if (isUsageLimitText(text)) {
      throw new EngineError(
        'rate-limited',
        'The Claude subscription has hit its usage window. Generation will work again once it resets.',
      );
    }

    if (message.stop_reason === 'max_tokens') {
      return {
        ...base,
        value: undefined,
        parseError: 'the answer was cut off at the output limit before the JSON object closed',
      };
    }

    try {
      return { ...base, value: JSON.parse(stripFence(text)) };
    } catch {
      return {
        ...base,
        value: undefined,
        parseError: 'the answer was not a JSON object',
      };
    }
  }

  private assistantError(kind: string): EngineError {
    const known = ASSISTANT_ERRORS[kind];
    return known
      ? new EngineError(known.code, known.message)
      : new EngineError('provider-unreachable', `Claude could not complete the request (${kind}).`);
  }

  private resultError(subtype: string, errors: string[] | undefined, assistantError?: string): EngineError {
    if (assistantError) return this.assistantError(assistantError);
    if ((errors ?? []).some(isUsageLimitText)) {
      return new EngineError(
        'rate-limited',
        'The Claude subscription has hit its usage window. Generation will work again once it resets.',
      );
    }
    // Structured-output retries are exhausted only after the SDK has already re-asked, so
    // this is the same condition as a malformed answer twice over.
    if (subtype === 'error_max_structured_output_retries') {
      return new EngineError(
        'format-failed',
        'Claude could not produce a plan in the required format.',
      );
    }
    return new EngineError('provider-unreachable', `Claude ended the session (${subtype}).`);
  }

  /** Everything that escapes the SDK becomes a named error. Nothing raw reaches the app. */
  private translate(e: unknown): EngineError {
    if (e instanceof EngineError) return e;
    if (e instanceof AbortError || (e as any)?.name === 'AbortError') {
      // The caller aborted: either the athlete pressed back, or the request budget ran out.
      // Which one it was is the caller's to say, so this stays neutral.
      return new EngineError('timeout', 'Generation was cancelled before it finished.');
    }
    const detail = e instanceof Error ? e.message : String(e);
    return new EngineError(
      'provider-unreachable',
      `The backend could not reach Claude (${firstLine(detail)}).`,
    );
  }
}

/** Models sometimes wrap JSON in a fence despite being told not to. Cheap to forgive. */
function stripFence(text: string): string {
  const fenced = /^```(?:json)?\s*\n([\s\S]*?)\n?```$/.exec(text.trim());
  return fenced ? fenced[1] : text;
}

/** Keeps a stack trace out of an app-facing message. */
const firstLine = (s: string): string => s.split('\n', 1)[0].slice(0, 200);
