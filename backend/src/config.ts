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
 * Mode A's preconditions, checked before the socket opens (story 2).
 *
 * The personal-use gate is not ceremony. Anthropic supports a subscription token in the
 * Agent SDK headless **for personal and development use**; a deployment serving anyone but
 * the token's owner must run Mode B instead. Making that an explicit, logged opt-in means
 * nobody stands one of these up for a user base by accident.
 */
export function assertModeAReady(config: Config, env: NodeJS.ProcessEnv = process.env): void {
  if (config.mode !== 'A') return;

  if (!config.modeAPersonalUse) {
    throw new Error(
      'Mode A refuses to start without MODE_A_PERSONAL_USE=true.\n' +
        '  Mode A bills every request to the owner\'s Claude subscription, which is supported\n' +
        '  for personal and development use only. Any deployment serving accounts other than\n' +
        '  the token owner\'s must run Mode B (AUTH_MODE=B) with per-user API keys.',
    );
  }

  if (!env.CLAUDE_CODE_OAUTH_TOKEN) {
    throw new Error(
      'Mode A needs CLAUDE_CODE_OAUTH_TOKEN. Mint one with `claude setup-token` (a one-year\n' +
        '  OAuth token, printed once) and put it in the backend environment.',
    );
  }

  if (env.ANTHROPIC_API_KEY) {
    throw new Error(
      'ANTHROPIC_API_KEY is set and outranks CLAUDE_CODE_OAUTH_TOKEN in Claude Code\'s\n' +
        '  credential order, so requests would bill the API instead of the subscription.\n' +
        '  Unset it (`unset ANTHROPIC_API_KEY`) or run Mode B deliberately.',
    );
  }
}
