/**
 * Startup refusals (Epic 2 story 2). Every one of these is a configuration mistake that
 * would otherwise be discovered by an athlete, or — worse — not discovered at all.
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { assertModeAReady, loadConfig } from '../src/config.js';

const SECRET = 'test-secret-that-is-long-enough';
const env = (over: Record<string, string | undefined> = {}) =>
  ({ CADENTIC_SHARED_SECRET: SECRET, ...over }) as NodeJS.ProcessEnv;

const modeA = { CLAUDE_CODE_OAUTH_TOKEN: 'sk-ant-oat01-x', MODE_A_PERSONAL_USE: 'true' };

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

test('Mode A refuses to start without a token', () => {
  const e = env({ MODE_A_PERSONAL_USE: 'true' });
  assert.throws(() => assertModeAReady(loadConfig(e), e), /claude setup-token/);
});

test('Mode A refuses to start when ANTHROPIC_API_KEY would bill the API instead', () => {
  const e = env({ ...modeA, ANTHROPIC_API_KEY: 'sk-ant-api03-x' });
  assert.throws(() => assertModeAReady(loadConfig(e), e), /outranks CLAUDE_CODE_OAUTH_TOKEN/);
});

test('a fully configured Mode A starts', () => {
  const e = env(modeA);
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
