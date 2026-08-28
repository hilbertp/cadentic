/**
 * Backend configuration, read once at startup (Epic 2 stories 0 and 2).
 *
 * Auth mode is config, never a request field: the app sends the same bytes in Mode A and
 * Mode B, and which provider answers is a deployment decision the app cannot influence.
 */

export type AuthMode = 'A' | 'B';

export interface Config {
  mode: AuthMode;
  port: number;
  /** Dev binds to the private network only — never 0.0.0.0 unless someone asks for it. */
  host: string;
  sharedSecret: string;
  model: string;
  /**
   * Whole-request budget, covering both generation attempts. Multi-minute generations are
   * expected; the app's client timeout is set above this so the backend's own `timeout`
   * error wins the race and the athlete gets a named reason instead of a dropped socket.
   */
  requestTimeoutMs: number;
  /**
   * How far ahead of `requestDate` a proposed `startDate` may sit (story 3; implementor
   * open point 6, default 14 days). A validity check, not an edit: the backend never moves
   * a date the model chose, it only refuses one it cannot accept.
   */
  startDateWindowDays: number;
  /** Story 2: Mode A runs on the owner's personal subscription and must say so out loud. */
  modeAPersonalUse: boolean;
  /** Raw responses to stderr for debugging (story 3). Off by default. */
  logRawResponses: boolean;
}

const num = (raw: string | undefined, fallback: number, name: string, min = 1): number => {
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < min) throw new Error(`${name} must be a number >= ${min}, got "${raw}"`);
  return n;
};

const bool = (raw: string | undefined): boolean => raw === 'true';

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const mode = (env.AUTH_MODE ?? 'A').toUpperCase();
  if (mode !== 'A' && mode !== 'B') throw new Error(`AUTH_MODE must be A or B, got "${env.AUTH_MODE}"`);

  const sharedSecret = env.CADENTIC_SHARED_SECRET ?? '';
  if (sharedSecret.length < 16) {
    throw new Error(
      'CADENTIC_SHARED_SECRET must be set to at least 16 characters. ' +
        'Generate one with: openssl rand -hex 32',
    );
  }

  return {
    mode,
    // 0 is the ephemeral-port idiom — useful in tests and behind a supervisor.
    port: num(env.PORT, 8787, 'PORT', 0),
    host: env.HOST ?? '127.0.0.1',
    sharedSecret,
    model: env.MESOCYCLE_MODEL ?? 'claude-opus-5',
    requestTimeoutMs: num(env.REQUEST_TIMEOUT_MS, 5 * 60_000, 'REQUEST_TIMEOUT_MS'),
    startDateWindowDays: num(env.START_DATE_WINDOW_DAYS, 14, 'START_DATE_WINDOW_DAYS'),
    modeAPersonalUse: bool(env.MODE_A_PERSONAL_USE),
    logRawResponses: bool(env.LOG_RAW_RESPONSES),
  };
}

/**
 * Which credential Mode A will actually authenticate with.
 *
 * There are two, and **neither is more official than the other** — they are the same
 * subscription reached two ways:
 *
 * - **A setup token** in `CLAUDE_CODE_OAUTH_TOKEN`, minted by `claude setup-token`. Valid a
 *   year, and the only option where no interactive login exists: CI, a container, a server.
 * - **The machine's own Claude Code login**, the credential `claude` stores when you sign in.
 *   The Agent SDK spawns Claude Code, so it resolves this like any other session. For a
 *   backend running on the developer's own laptop this is simply the login that is already
 *   there — minting a token to sit beside it would authenticate the same account twice.
 *
 * The trade-off is lifetime and reach: a `/login` credential expires and is renewed by
 * signing in again, and it lives in the OS keychain, so it only works for the same user on
 * the same machine. A setup token travels.
 */
export type CredentialSource = 'setup-token' | 'claude-code-login';

export function modeACredentialSource(env: NodeJS.ProcessEnv = process.env): CredentialSource {
  return env.CLAUDE_CODE_OAUTH_TOKEN ? 'setup-token' : 'claude-code-login';
}

export const describeCredential = (source: CredentialSource): string =>
  source === 'setup-token'
    ? 'CLAUDE_CODE_OAUTH_TOKEN from the environment (`claude setup-token`)'
    : "this machine's Claude Code login — no token needed, and none is stored here";

/**
 * Everything in Claude Code's credential order that outranks the subscription and would
 * quietly bill something else. Each of these is a *silent* wrong answer: the backend starts,
 * requests succeed, and the bill lands somewhere the owner did not intend — which is exactly
 * the failure this epic set out to avoid, so it is a refusal rather than a warning.
 */
const OUTRANKS_SUBSCRIPTION: Array<{ test: (e: NodeJS.ProcessEnv) => boolean; why: string }> = [
  {
    test: (e) => Boolean(e.CLAUDE_CODE_USE_BEDROCK || e.CLAUDE_CODE_USE_VERTEX || e.CLAUDE_CODE_USE_FOUNDRY),
    why:
      'A cloud-provider variable (CLAUDE_CODE_USE_BEDROCK / _VERTEX / _FOUNDRY) is set.\n' +
      '  Cloud providers outrank every other credential, so requests would bill that cloud\n' +
      '  account rather than the Claude subscription.',
  },
  {
    test: (e) => Boolean(e.ANTHROPIC_AUTH_TOKEN),
    why:
      'ANTHROPIC_AUTH_TOKEN is set. It is sent as a bearer token and outranks both the setup\n' +
      '  token and the Claude Code login, so requests would go wherever it points.',
  },
  {
    test: (e) => Boolean(e.ANTHROPIC_API_KEY),
    why:
      'ANTHROPIC_API_KEY is set. It outranks both the setup token and the Claude Code login,\n' +
      '  so requests would bill the API instead of the subscription.',
  },
  {
    test: (e) => Boolean(e.ANTHROPIC_PROFILE),
    why:
      'ANTHROPIC_PROFILE is set. A named `ant auth login` profile outranks the Claude Code\n' +
      '  login and bills a Console/API organization — the same browser flow, a different bill.',
  },
  {
    test: (e) => Boolean(e.ANTHROPIC_FEDERATION_RULE_ID && e.ANTHROPIC_ORGANIZATION_ID),
    why:
      'Workload Identity Federation variables are set (ANTHROPIC_FEDERATION_RULE_ID and\n' +
      '  ANTHROPIC_ORGANIZATION_ID). They outrank the Claude Code login and bill an API\n' +
      '  organization.',
  },
];

/**
 * Mode A's preconditions, checked before the socket opens (story 2).
 *
 * The personal-use gate is not ceremony. Anthropic supports a subscription in the Agent SDK
 * headless **for personal and development use**; a deployment serving anyone but the account
 * owner must run Mode B instead. Making that an explicit, logged opt-in means nobody stands
 * one of these up for a user base by accident.
 */
export function assertModeAReady(config: Config, env: NodeJS.ProcessEnv = process.env): void {
  if (config.mode !== 'A') return;

  if (!config.modeAPersonalUse) {
    throw new Error(
      'Mode A refuses to start without MODE_A_PERSONAL_USE=true.\n' +
        '  Mode A bills every request to the owner\'s Claude subscription, which is supported\n' +
        '  for personal and development use only. Any deployment serving accounts other than\n' +
        '  the owner\'s must run Mode B (AUTH_MODE=B) with per-user API keys.',
    );
  }

  const conflict = OUTRANKS_SUBSCRIPTION.find((c) => c.test(env));
  if (conflict) {
    throw new Error(
      `${conflict.why}\n` +
        '  Unset it, or run Mode B (AUTH_MODE=B) deliberately.',
    );
  }
}
