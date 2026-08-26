/**
 * The contract itself (Epic 2 story 0): one JSON Schema file, and the backend's own types
 * checked against it rather than beside it.
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  assertContractAgreement,
  enumValues,
  fieldErrors,
  validateMesocyclePlan,
  validateRequestPayload,
} from '../src/contract.js';
import { payload, planDraft } from './fixtures.js';

test('the backend error codes and the contract agree', () => {
  assert.doesNotThrow(assertContractAgreement);
});

test('a complete meso-request payload validates', () => {
  assert.ok(validateRequestPayload(payload()), JSON.stringify(validateRequestPayload.errors));
});

test('an unknown field in the payload is rejected by name', () => {
  const bad: any = payload();
  bad.goals.somethingElse = 1;
  assert.equal(validateRequestPayload(bad), false);
  assert.ok(fieldErrors(validateRequestPayload.errors).some((f) => f.includes('somethingElse')));
});

test('a missing requestDate is named', () => {
  const bad: any = payload();
  delete bad.requestDate;
  assert.equal(validateRequestPayload(bad), false);
  assert.ok(fieldErrors(validateRequestPayload.errors).some((f) => f.includes('requestDate')));
});

test('a skipped self-assessment is null, not absent', () => {
  const bad: any = payload();
  delete bad.status.selfAssessment.HYPERTROPHY;
  assert.equal(validateRequestPayload(bad), false);
});

test('the day-type enum has no exercise-shaped escape hatch', () => {
  // "Never exercises" is machine-enforced: there is no free-text day field to hide one in,
  // and no game or practice type the model could author.
  assert.deepEqual(enumValues('DayType'), ['STRENGTH', 'ENDURANCE', 'MOBILITY', 'RECOVERY', 'REST']);
});

test('intensity reuses the strain vocabulary', () => {
  assert.deepEqual(enumValues('Intensity'), enumValues('Strain'));
});

test('a stamped plan validates against the response schema', () => {
  const plan = {
    schemaVersion: 1,
    generatedBy: { mode: 'max-plan-oauth', model: 'claude-opus-5', promptVersion: 1 },
    ...planDraft(),
  };
  assert.ok(validateMesocyclePlan(plan), JSON.stringify(validateMesocyclePlan.errors));
});

test('a plan carrying a coach note is rejected — plan surfaces never render model prose', () => {
  const plan: any = {
    schemaVersion: 1,
    generatedBy: { mode: 'max-plan-oauth', model: 'claude-opus-5', promptVersion: 1 },
    ...planDraft(),
    coachNote: 'Twelve weeks, one engine.',
  };
  assert.equal(validateMesocyclePlan(plan), false);
});
