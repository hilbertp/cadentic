/**
 * Response validation (Epic 2 story 3). Three layers, all of them machine-enforced:
 *
 * 1. **Schema** — strict and closed. `additionalProperties: false` everywhere and a pinned
 *    day-type enum mean "never exercises" is a rejection, not a hope: there is no field an
 *    exercise could arrive in and no day type it could hide behind.
 * 2. **Structural** — the arithmetic the schema cannot express. Phase weeks sum to the
 *    duration, weeks are contiguous, the calendar span matches the week count, REST days
 *    carry no intensity, `sessionsPerWeek` matches what the weeks actually contain.
 * 3. **Cross-request** — the plan against the request that asked for it. The start date is
 *    inside the acceptable window, and the goals the model echoed match the ones we sent.
 *
 * Every failure is a sentence the model can act on, because the re-request feeds these
 * strings straight back to it.
 */

import { fieldErrors, validatePlanDraft } from './contract.js';

export interface PlanDraft {
  startDate: string;
  endDate: string;
  durationWeeks: number;
  sessionsPerWeek: number;
  lane: string;
  focus: string[];
  queued: string[];
  phases: Array<{ phaseType: string; name: string; weeks: number }>;
  weeklyStructure: Array<{
    week: number;
    days: Array<{ day: string; type: string; intensity: string | null }>;
  }>;
  progression: { intraWeek: string; interWeek: string };
}

export interface RequestGoals {
  requestDate: string;
  lane: string;
  focusThisCycle: string[];
  queuedForLater: string[];
}

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const MS_PER_DAY = 86_400_000;

const parseDate = (iso: string): number => Date.parse(`${iso}T00:00:00Z`);
const daysBetween = (a: string, b: string): number => (parseDate(b) - parseDate(a)) / MS_PER_DAY;
const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((v, i) => v === b[i]);

/**
 * The typical week's training-day count: the count that occurs in the most weeks, ties going
 * to the higher count. Defined here and described in the prompt in the same words, so the
 * model is judged by the rule it was given.
 */
export function modalTrainingDays(plan: PlanDraft): number {
  const counts = plan.weeklyStructure.map((w) => w.days.filter((d) => d.type !== 'REST').length);
  const tally = new Map<number, number>();
  for (const c of counts) tally.set(c, (tally.get(c) ?? 0) + 1);
  let best = 0;
  let bestFreq = -1;
  for (const [count, freq] of tally) {
    if (freq > bestFreq || (freq === bestFreq && count > best)) {
      best = count;
      bestFreq = freq;
    }
  }
  return best;
}

/** Schema layer. Returns [] when the value is a well-formed draft. */
export function schemaFailures(value: unknown): string[] {
  return validatePlanDraft(value) ? [] : fieldErrors(validatePlanDraft.errors);
}

/** Structural + cross-request layers. Only run on a value that already passed the schema. */
export function planFailures(
  plan: PlanDraft,
  request: RequestGoals,
  startDateWindowDays: number,
): string[] {
  const out: string[] = [];

  // --- Structural ------------------------------------------------------

  const phaseWeeks = plan.phases.reduce((n, p) => n + p.weeks, 0);
  if (phaseWeeks !== plan.durationWeeks) {
    out.push(
      `phases: the phase weeks sum to ${phaseWeeks} but durationWeeks is ${plan.durationWeeks} — they must be equal`,
    );
  }

  if (plan.weeklyStructure.length !== plan.durationWeeks) {
    out.push(
      `weeklyStructure: has ${plan.weeklyStructure.length} weeks but durationWeeks is ${plan.durationWeeks} — there must be exactly one entry per week`,
    );
  }

  plan.weeklyStructure.forEach((w, i) => {
    if (w.week !== i + 1) {
      out.push(
        `weeklyStructure[${i}].week is ${w.week} — weeks must be numbered 1 to ${plan.durationWeeks} in order, with no gaps`,
      );
    }
    const days = w.days.map((d) => d.day);
    if (!sameList(days, WEEK)) {
      out.push(
        `weeklyStructure[${i}].days must list all seven days in Monday-to-Sunday order; got [${days.join(', ')}]`,
      );
    }
    for (const d of w.days) {
      if (d.type === 'REST' && d.intensity !== null) {
        out.push(
          `weeklyStructure[${i}] ${d.day}: a REST day must have intensity null, got "${d.intensity}"`,
        );
      }
      if (d.type !== 'REST' && d.intensity === null) {
        out.push(
          `weeklyStructure[${i}] ${d.day}: a ${d.type} day needs an intensity — only REST days may be null`,
        );
      }
    }
  });

  // The calendar span is the week count, exactly: the last day of the last week.
  const span = daysBetween(plan.startDate, plan.endDate);
  const expected = plan.durationWeeks * 7 - 1;
  if (span !== expected) {
    out.push(
      `endDate ${plan.endDate} is ${span} days after startDate ${plan.startDate}; ` +
        `${plan.durationWeeks} weeks requires exactly ${expected}`,
    );
  }

  const modal = modalTrainingDays(plan);
  if (plan.sessionsPerWeek !== modal) {
    out.push(
      `sessionsPerWeek is ${plan.sessionsPerWeek} but the typical week in weeklyStructure has ${modal} non-REST days`,
    );
  }

  // --- Cross-request ---------------------------------------------------

  // A validity check, not an edit: a start date outside the window is refused, never moved.
  // The plan the athlete approves is the plan the model wrote (PRD §9 step 4, §15).
  const lead = daysBetween(request.requestDate, plan.startDate);
  if (lead < 0) {
    out.push(`startDate ${plan.startDate} is before requestDate ${request.requestDate}`);
  } else if (lead > startDateWindowDays) {
    out.push(
      `startDate ${plan.startDate} is ${lead} days after requestDate ${request.requestDate} — it must be within ${startDateWindowDays}`,
    );
  }

  // The Goals artifact is the single source of truth for priorities. An echo that disagrees
  // means the model planned for a different athlete than the one we described, so the plan
  // is treated as malformed rather than silently corrected.
  if (plan.lane !== request.lane) {
    out.push(`lane is "${plan.lane}" but the request's goals.lane is "${request.lane}"`);
  }
  if (!sameList(plan.focus, request.focusThisCycle)) {
    out.push(
      `focus is [${plan.focus.join(', ')}] but the request's goals.focusThisCycle is [${request.focusThisCycle.join(', ')}]`,
    );
  }
  if (!sameList(plan.queued, request.queuedForLater)) {
    out.push(
      `queued is [${plan.queued.join(', ')}] but the request's goals.queuedForLater is [${request.queuedForLater.join(', ')}]`,
    );
  }

  return out;
}

/** Both layers, in order. Structural checks only run on a schema-valid draft. */
export function validateAnswer(
  value: unknown,
  request: RequestGoals,
  startDateWindowDays: number,
): { plan: PlanDraft; failures: [] } | { plan: null; failures: string[] } {
  const schema = schemaFailures(value);
  if (schema.length) return { plan: null, failures: schema };
  const plan = value as PlanDraft;
  const failures = planFailures(plan, request, startDateWindowDays);
  return failures.length ? { plan: null, failures } : { plan, failures: [] };
}
