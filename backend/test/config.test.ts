/**
 * Startup refusals (Epic 2 story 2). Every one of these is a configuration mistake that
 * would otherwise be discovered by an athlete, or — worse — not discovered at all.
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { assertModeAReady, loadConfig, modeACredentialSource } from '../src/config.js';

const SECRET = 'test-secret-that-is-long-enough';
const env = (over: Record<string, string | undefined> = {}) =>
  ({ CADENTIC_SHARED_SECRET: SECRET, ...over }) as NodeJS.ProcessEnv;

const modeA = { MODE_A_PERSONAL_USE: 'true' };

test('a short or missing shared secret refuses to start', () => {
  assert.throws(() => loadConfig({} as NodeJS.ProcessEnv), /CADENTIC_SHARED_SECRET/);
  assert.throws(
    () => loadConfig({ CADENTIC_SHARED_SECRET: 'short' } as NodeJS.ProcessEnv),
    /at least 16/,
  );
});

test('the dev server binds to loopback unless told otherwise', () => {
  assert.equal(loadConfig(env()).host, '127.0.0.1');
});

test('Mode A refuses to start without the personal-use acknowledgement', () => {
  const e = env({ CLAUDE_CODE_OAUTH_TOKEN: 'sk-ant-oat01-x' });
  assert.throws(() => assertModeAReady(loadConfig(e), e), /MODE_A_PERSONAL_USE=true/);
});

test('Mode A starts on the machine\'s Claude Code login, with no token to mint', () => {
  // The Agent SDK spawns Claude Code, which resolves the credential the developer already
  // signed in with. A setup token is for CI and containers, not for a laptop that is
  // already logged in — requiring one would authenticate the same account twice.
  const e = env(modeA);
  assert.doesNotThrow(() => assertModeAReady(loadConfig(e), e));
  assert.equal(modeACredentialSource(e), 'claude-code-login');
});

test('an explicit setup token is used when there is one', () => {
  const e = env({ ...modeA, CLAUDE_CODE_OAUTH_TOKEN: 'sk-ant-oat01-x' });
  assert.doesNotThrow(() => assertModeAReady(loadConfig(e), e));
  assert.equal(modeACredentialSource(e), 'setup-token');
});

// Each of these outranks the subscription in Claude Code's credential order, so the backend
// would start, requests would succeed, and the bill would land somewhere else entirely.
// A silent wrong answer is worse than a refusal.
const OUTRANKING: Array<[string, Record<string, string>, RegExp]> = [
  ['ANTHROPIC_API_KEY', { ANTHROPIC_API_KEY: 'sk-ant-api03-x' }, /bill the API/],
  ['ANTHROPIC_AUTH_TOKEN', { ANTHROPIC_AUTH_TOKEN: 'bearer-x' }, /bearer token/],
  ['ANTHROPIC_PROFILE', { ANTHROPIC_PROFILE: 'work' }, /Console\/API organization/],
  ['CLAUDE_CODE_USE_BEDROCK', { CLAUDE_CODE_USE_BEDROCK: '1' }, /cloud-provider variable/],
  ['CLAUDE_CODE_USE_VERTEX', { CLAUDE_CODE_USE_VERTEX: '1' }, /cloud-provider variable/],
  [
    'federation variables',
    { ANTHROPIC_FEDERATION_RULE_ID: 'r', ANTHROPIC_ORGANIZATION_ID: 'o' },
    /API\n?\s*organization/,
  ],
];

for (const [name, vars, expected] of OUTRANKING) {
  test(`Mode A refuses to start when ${name} would bill something else`, () => {
    const e = env({ ...modeA, ...vars });
    assert.throws(() => assertModeAReady(loadConfig(e), e), expected);
  });
}

test('one federation variable alone is not enough to outrank', () => {
  // Claude Code needs both to select federation, so one on its own changes nothing.
  const e = env({ ...modeA, ANTHROPIC_FEDERATION_RULE_ID: 'r' });
  assert.doesNotThrow(() => assertModeAReady(loadConfig(e), e));
});

test('Mode B needs none of Mode A\'s credentials', () => {
  const e = env({ AUTH_MODE: 'B' });
  assert.doesNotThrow(() => assertModeAReady(loadConfig(e), e));
});

test('the timeout budget and start-date window are tunable', () => {
  const c = loadConfig(env({ REQUEST_TIMEOUT_MS: '600000', START_DATE_WINDOW_DAYS: '21' }));
  assert.equal(c.requestTimeoutMs, 600_000);
  assert.equal(c.startDateWindowDays, 21);
});
