/**
 * The re-request loop and the stamping rule (Epic 2 stories 1 and 3).
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { EngineError } from '../src/errors.js';
import { PROMPT_VERSION, generatePlan } from '../src/generate.js';
import { FakeProvider, payload, planDraft } from './fixtures.js';

const run = (provider: FakeProvider, log: (e: Record<string, unknown>) => void = () => {}) =>
  generatePlan({
    payload: payload(),
    provider,
    startDateWindowDays: 14,
    signal: new AbortController().signal,
    log,
    logRaw: false,
  });

test('a good first answer is returned as-is, with one call to the provider', async () => {
  const provider = new FakeProvider([{ value: planDraft() }]);
  const plan = await run(provider);
  assert.equal(provider.prompts.length, 1);
  assert.equal(plan.durationWeeks, 8);
  assert.equal(plan.generatedBy.promptVersion, PROMPT_VERSION);
  assert.equal(plan.generatedBy.mode, 'max-plan-oauth');
  assert.equal(plan.schemaVersion, 1);
});

test('the payload goes into the prompt verbatim', async () => {
  const provider = new FakeProvider([{ value: planDraft() }]);
  await run(provider);
  const prompt = provider.prompts[0];
  assert.ok(prompt.includes('"Lower-back disc (L4/L5)"'));
  assert.ok(prompt.includes('"19:00–20:30"'));
  assert.ok(prompt.includes(`"requestDate": "${payload().requestDate}"`));
});

test('the prompt tells the model why phase names must be short', async () => {
  // The constraint exists for a layout reason the model cannot see, so saying only "max 14"
  // would read as arbitrary and invite it right up to the ceiling.
  const provider = new FakeProvider([{ value: planDraft() }]);
  await run(provider);
  assert.ok(provider.prompts[0].includes('One or two short words'));
});

test('the prompt states no duration band while the owner question is open', async () => {
  const provider = new FakeProvider([{ value: planDraft() }]);
  await run(provider);
  assert.ok(provider.prompts[0].includes('There is no required range.'));
});

test('a malformed answer gets exactly one corrective re-request', async () => {
  const provider = new FakeProvider([
    { value: planDraft({ sessionsPerWeek: 6 }) },
    { value: planDraft() },
  ]);
  const plan = await run(provider);
  assert.equal(provider.prompts.length, 2);
  assert.equal(plan.sessionsPerWeek, 5);
});

test('the re-request names what was wrong', async () => {
  const provider = new FakeProvider([
    { value: planDraft({ lane: 'PERFORMANCE' }) },
    { value: planDraft() },
  ]);
  await run(provider);
  const retry = provider.prompts[1];
  assert.ok(retry.includes('Your previous answer was rejected'));
  assert.ok(retry.includes('lane is "PERFORMANCE"'));
});

test('two malformed answers become format-failed, and there is no third attempt', async () => {
  const provider = new FakeProvider([
    { value: planDraft({ sessionsPerWeek: 6 }) },
    { value: planDraft({ sessionsPerWeek: 7 }) },
  ]);
  await assert.rejects(run(provider), (e: unknown) => {
    assert.ok(e instanceof EngineError);
    assert.equal(e.code, 'format-failed');
    return true;
  });
  assert.equal(provider.prompts.length, 2);
});

test('an unparseable answer goes through the re-request, not straight to an error', async () => {
  const provider = new FakeProvider([
    { value: undefined, parseError: 'the answer was not a JSON object' },
    { value: planDraft() },
  ]);
  const plan = await run(provider);
  assert.equal(provider.prompts.length, 2);
  assert.equal(plan.durationWeeks, 8);
});

test('both attempts are logged with the prompt version and the outcome', async () => {
  const events: Array<Record<string, unknown>> = [];
  const provider = new FakeProvider([
    { value: planDraft({ sessionsPerWeek: 6 }) },
    { value: planDraft() },
  ]);
  await run(provider, (e) => events.push(e));
  assert.equal(events.length, 2);
  assert.equal(events[0].ok, false);
  assert.equal(events[1].ok, true);
  assert.ok(events.every((e) => e.promptVersion === PROMPT_VERSION));
});

test('a provider error is not retried — only a malformed answer is', async () => {
  const provider = new FakeProvider([new EngineError('rate-limited', 'usage window')]);
  await assert.rejects(run(provider), (e: unknown) => (e as EngineError).code === 'rate-limited');
  assert.equal(provider.prompts.length, 1);
});

test('goals are stamped from the payload, never from the model', async () => {
  // A model whose echo passed validation still does not get to author the goals: what is
  // persisted comes from the request. Here the fake answers with the right echo, and the
  // assertion is that the plan carries the payload's own arrays.
  const provider = new FakeProvider([{ value: planDraft() }]);
  const plan = await run(provider);
  const goals = payload().goals;
  assert.deepEqual(plan.focus, goals.focusThisCycle);
  assert.deepEqual(plan.queued, goals.queuedForLater);
  assert.equal(plan.lane, goals.lane);
});

test('the plan carries no free text beyond the progression prose', async () => {
  const provider = new FakeProvider([{ value: planDraft() }]);
  const plan = await run(provider);
  assert.ok(!('headline' in plan));
  assert.ok(!('coachNote' in plan));
});
