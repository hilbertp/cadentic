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
  /**
   * The athlete's HARD commitments, for the collision check: ISO dates of HARD one-off
   * blockers, and the days of week carrying a HARD recurring blocker. A day that already
   * costs the athlete a hard effort must not also be a HARD training day — the mesocycle
   * owns where intensity lands, so this is a structural error, not a daily-layer nuance.
   */
  hardBlockerDates: string[];
  hardBlockerDays: string[];
  /**
   * The athlete's optional weekly ceiling (owner decision, 2026-08-30): the most days per
   * week that may carry effort of any kind. Commitments count too, so the full blocker set
   * rides along — a day is "occupied" when it holds a non-REST training day OR any blocker,
   * and a day holding both counts once.
   */
  maxWeeklyDays: number | null;
  allBlockerDates: string[];
  allBlockerDays: string[];
}

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const MS_PER_DAY = 86_400_000;

const parseDate = (iso: string): number => Date.parse(`${iso}T00:00:00Z`);
const daysBetween = (a: string, b: string): number => (parseDate(b) - parseDate(a)) / MS_PER_DAY;
const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((v, i) => v === b[i]);
const isoDate = (ms: number): string => new Date(ms).toISOString().slice(0, 10);
/** getUTCDay() order, for naming the offending weekday in a failure message. */
const DOW = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** Which week numbers the phase list marks as DELOAD (1-based, in phase order). */
function deloadWeekNumbers(plan: PlanDraft): number[] {
  const out: number[] = [];
  let week = 1;
  for (const phase of plan.phases) {
    if (phase.phaseType === 'DELOAD') for (let i = 0; i < phase.weeks; i += 1) out.push(week + i);
    week += phase.weeks;
  }
  return out;
}

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

  // --- The layering's structural rules (sports-science review, 2026-08-28) ---
  //
  // The mesocycle owns frequency, day types, and where intensity lands; the daily layer owns
  // dose. Three consequences of that split are checkable here, and every live plan so far
  // happened to satisfy all three unprompted — which made them exactly the kind of property
  // this codebase refuses to leave prompt-hoped.

  // The weekly grid is Monday-to-Sunday — the schema demands the seven days in that order,
  // and the app's calendar is Monday-anchored. A cycle starting mid-week would make week 1's
  // leading days precede the cycle's own start: an incoherent grid, not a style choice.
  const startDow = new Date(parseDate(plan.startDate)).getUTCDay();
  if (startDow !== 1) {
    out.push(
      `startDate ${plan.startDate} is a ${DOW[startDow]} — every week in this plan runs Monday to Sunday, so the cycle must start on a Monday`,
    );
  }

  // A DELOAD phase is a structural claim, and a deload week that trains as hard as a normal
  // one makes it a lie. Planned unloading is the one thing the freshest "I feel great" data
  // would always veto — which is why the plan, not the day, must hold this line.
  for (const weekNumber of deloadWeekNumbers(plan)) {
    const wk = plan.weeklyStructure[weekNumber - 1];
    if (!wk) continue; // a phase/structure length mismatch is already reported above
    const hardDays = wk.days.filter((d) => d.intensity === 'HARD').length;
    if (hardDays > 0) {
      out.push(
        `week ${weekNumber} is in a DELOAD phase but contains ${hardDays} HARD day(s) — a deload week must contain no HARD days`,
      );
    }
    const trainingDays = wk.days.filter((d) => d.type !== 'REST').length;
    if (trainingDays > modal) {
      out.push(
        `week ${weekNumber} is in a DELOAD phase but has ${trainingDays} training days — more than the typical week's ${modal}; a deload must not exceed the typical week`,
      );
    }
  }

  // A day that already costs the athlete a hard effort — a game, a hard one-off — must not
  // also be a HARD training day. Dates are only well-defined off a Monday start, so this
  // runs after that check holds rather than emitting misleading dates on a broken grid.
  if (startDow === 1) {
    const hardDates = new Set(request.hardBlockerDates);
    const hardDows = new Set(request.hardBlockerDays);
    plan.weeklyStructure.forEach((wk, wi) => {
      for (const d of wk.days) {
        if (d.intensity !== 'HARD') continue;
        const di = WEEK.indexOf(d.day);
        if (di < 0) continue; // an invalid day name is already reported above
        const date = isoDate(parseDate(plan.startDate) + (wi * 7 + di) * MS_PER_DAY);
        if (hardDates.has(date) || hardDows.has(d.day)) {
          out.push(
            `week ${wk.week} ${d.day} (${date}) is a HARD training day, but the athlete already has a HARD commitment that day — plan REST, RECOVERY, or light work around it`,
          );
        }
      }
    });
  }

  // The athlete's weekly ceiling (optional). The coach prescribes frequency; this caps it.
  // A week's occupied days = distinct days carrying a non-REST training day OR any blocker —
  // a day carrying both counts once, which is exactly why stacking light training onto an
  // already-committed day is the cap-respecting move. Weeks whose commitments alone exceed
  // the cap are exempt: the athlete's own calendar never makes the cap infeasible, it only
  // stops the plan from adding occupied days beyond it.
  if (startDow === 1 && request.maxWeeklyDays != null) {
    const cap = request.maxWeeklyDays;
    const blockerDates = new Set(request.allBlockerDates);
    const blockerDows = new Set(request.allBlockerDays);
    plan.weeklyStructure.forEach((wk, wi) => {
      let occupied = 0;
      let commitments = 0;
      for (let di = 0; di < WEEK.length; di += 1) {
        const date = isoDate(parseDate(plan.startDate) + (wi * 7 + di) * MS_PER_DAY);
        const committed = blockerDates.has(date) || blockerDows.has(WEEK[di]);
        const trains = wk.days.some((d) => d.day === WEEK[di] && d.type !== 'REST');
        if (committed) commitments += 1;
        if (committed || trains) occupied += 1;
      }
      if (occupied > Math.max(cap, commitments)) {
        out.push(
          `week ${wk.week} occupies ${occupied} days with training or commitments, but the athlete's ceiling is ${cap} — rest the free days, or place training on already-committed days (a day carrying both counts once)`,
        );
      }
    });
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
