/**
 * Story 3's three layers. Each test breaks exactly one invariant and asserts the failure
 * names it — because these strings are what the corrective re-request shows the model.
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { modalTrainingDays, planFailures, schemaFailures, validateAnswer } from '../src/validate.js';
import { REQUEST_DATE, planDraft } from './fixtures.js';

const request = {
  requestDate: REQUEST_DATE,
  lane: 'LONGEVITY',
  focusThisCycle: ['CARDIO', 'EXPLOSIVENESS'],
  queuedForLater: ['STRENGTH'],
};

const failuresFor = (over: Record<string, unknown>) =>
  planFailures(planDraft(over) as any, request, 14);

test('a well-formed plan passes every layer', () => {
  const { plan, failures } = validateAnswer(planDraft(), request, 14);
  assert.deepEqual(failures, []);
  assert.ok(plan);
});

// --- Schema layer ----------------------------------------------------------

test('an exercise smuggled onto a day is rejected', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[0].days[0].exercise = 'Back squat 5x5';
  const failures = schemaFailures(draft);
  assert.ok(failures.some((f) => f.includes('exercise')), failures.join('; '));
});

test('a day type outside the enum is rejected', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[0].days[0].type = 'GAME';
  assert.ok(schemaFailures(draft).length > 0);
});

// --- Structural layer ------------------------------------------------------

test('phase weeks must sum to the duration', () => {
  const failures = failuresFor({
    phases: [{ phaseType: 'BASE', name: 'Base', weeks: 3 }],
  });
  assert.ok(failures.some((f) => f.includes('sum to 3') && f.includes('durationWeeks is 8')));
});

test('the calendar span must be exactly the week count', () => {
  const failures = failuresFor({ endDate: '2026-11-02' });
  assert.ok(failures.some((f) => f.includes('endDate 2026-11-02') && f.includes('exactly 55')));
});

test('weeks must be contiguous and numbered from one', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[3].week = 9;
  assert.ok(planFailures(draft, request, 14).some((f) => f.includes('weeklyStructure[3].week is 9')));
});

test('a week must list all seven days in order', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[0].days.reverse();
  assert.ok(
    planFailures(draft, request, 14).some((f) => f.includes('Monday-to-Sunday order')),
  );
});

test('a REST day carrying an intensity is rejected', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[0].days[2].intensity = 'LIGHT';
  assert.ok(
    planFailures(draft, request, 14).some((f) => f.includes('REST day must have intensity null')),
  );
});

test('a training day without an intensity is rejected', () => {
  const draft: any = planDraft();
  draft.weeklyStructure[0].days[0].intensity = null;
  assert.ok(planFailures(draft, request, 14).some((f) => f.includes('needs an intensity')));
});

test('sessionsPerWeek must match what the weeks actually contain', () => {
  assert.ok(
    failuresFor({ sessionsPerWeek: 6 }).some((f) => f.includes('sessionsPerWeek is 6')),
  );
});

test('the modal training-day count ignores an atypical deload week', () => {
  const draft: any = planDraft();
  // Turn week 7 into a two-session deload; the typical week is still five.
  draft.weeklyStructure[6].days = draft.weeklyStructure[6].days.map((d: any, i: number) =>
    i < 5 ? { ...d, type: 'REST', intensity: null } : d,
  );
  assert.equal(modalTrainingDays(draft), 5);
  assert.deepEqual(planFailures(draft, request, 14), []);
});

// --- Cross-request layer ---------------------------------------------------

test('a start date before the request date is rejected', () => {
  const failures = failuresFor({ startDate: '2026-08-25', endDate: '2026-10-19' });
  assert.ok(failures.some((f) => f.includes('is before requestDate')));
});

test('a start date beyond the window is rejected, never moved', () => {
  const failures = failuresFor({ startDate: '2026-09-30', endDate: '2026-11-24' });
  assert.ok(failures.some((f) => f.includes('must be within 14')));
});

test('the window is tunable', () => {
  const draft = planDraft({ startDate: '2026-09-30', endDate: '2026-11-24' });
  assert.deepEqual(planFailures(draft as any, request, 45), []);
});

test('a lane that contradicts the request is malformed', () => {
  assert.ok(
    failuresFor({ lane: 'PERFORMANCE' }).some(
      (f) => f.includes('lane is "PERFORMANCE"') && f.includes('"LONGEVITY"'),
    ),
  );
});

test('a focus that contradicts the request is malformed', () => {
  assert.ok(
    failuresFor({ focus: ['STRENGTH'] }).some((f) => f.startsWith('focus is [STRENGTH]')),
  );
});

test('a queued list that contradicts the request is malformed', () => {
  assert.ok(failuresFor({ queued: [] }).some((f) => f.startsWith('queued is []')));
});
