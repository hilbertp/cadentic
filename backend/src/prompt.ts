/**
 * The standard prompt (Epic 2 story 1) — versioned, reproducible, and the only prose the
 * engine sends.
 *
 * Two rules shape it:
 *
 * 1. **The payload goes in verbatim.** No summarising, no re-ordering, no backend
 *    interpretation of the athlete's data. What Epic 1 assembled is what the model reads.
 * 2. **No lane-conditional prose.** The template inserts the lane value and states what both
 *    lane values mean, identically on every request. It never branches on which one arrived —
 *    the plan differs because the model reasons about the lane, not because the backend fed
 *    it different instructions.
 *
 * Bump [PROMPT_VERSION] whenever the wording changes. It is stamped into every plan's
 * `generatedBy`, so a plan on disk can always be traced back to the words that produced it.
 *
 * **Deliberately absent: a duration band.** Constraining the model to 4–16 weeks is an open
 * question for the product owner (PRD §18); until it is answered the prompt states no band
 * and whatever duration the model proposes is accepted.
 */

import { enumValues, planDraftSchema } from './contract.js';

/**
 * v2 (2026-08-28): phase-name length. v1 left `name` loose and the first live plan came back
 * with "Double-fixture unload" on a one-week phase, which overflowed its timeline segment and
 * clipped the week label under it. The schema now caps it and the prompt says why.
 *
 * v3 (2026-08-28): the structural rules the sports-science layering review made explicit.
 * The mesocycle owns frequency, day types, and where intensity lands; the daily layer owns
 * dose. Three checkable consequences — a Monday start (the weekly grid is Mon–Sun),
 * genuinely lighter DELOAD weeks, and no HARD training on a day already carrying a HARD
 * commitment — were being delivered by the model unprompted in every live plan, which made
 * them exactly what this codebase refuses to leave prompt-hoped. Now stated here and
 * enforced by the validator.
 *
 * v4 (2026-08-30): the athlete's optional weekly ceiling (`goals.maxWeeklyDays`). The coach
 * prescribes frequency; the athlete may cap the days per week that carry effort of any kind,
 * commitments included. Stated with its counting rule — a day holding both training and a
 * commitment counts once — and enforced by the validator per week.
 */
export const PROMPT_VERSION = 4;

const list = (name: string) => enumValues(name).join(' | ');

const SYSTEM_PROMPT = `You are the Cadentic Mesocycle Engine. You plan the *structure* of one training
mesocycle for one athlete, and you return it as JSON. Nothing else.

You plan at the mesocycle level only. That means: how long the cycle runs, what phases it
moves through, what each day of each week is for and how hard it is, and how load progresses
within a week and across weeks.

You never prescribe exercises, sets, reps, loads, distances, or times. A later layer does
that, with equipment and facilities you cannot see. If you name a lift, the response is
rejected.`.trim();

/** Everything that is true of every request, in one block so the wire format is stable. */
const RULES = `
## What you are planning

- **durationWeeks** — how many weeks this cycle runs. Choose what the athlete's situation
  warrants. There is no required range.
- **startDate / endDate** — the cycle's calendar span. \`startDate\` must be on or after
  \`requestDate\`, within {{WINDOW}} days of it, **and must be a Monday** — every week in
  this plan runs Monday to Sunday, so a mid-week start would put week 1's leading days
  before the cycle exists. \`endDate\` is the last day of the last week:
  \`startDate + (durationWeeks x 7) - 1\` days, exactly.
- **phases** — the arc of the cycle. Each phase has a \`phaseType\` of ${list('PhaseType')},
  a display \`name\`, and a whole number of \`weeks\`. The phase weeks must sum to
  \`durationWeeks\`. Deload timing is expressed as a DELOAD phase; there is no separate
  deload field.
  - **A DELOAD week must be genuinely lighter**: no HARD days anywhere in it, and no more
    training days than a typical week. A "deload" that trains as hard as a normal week is
    rejected as a structural contradiction.
  - The \`name\` is drawn inside a timeline segment as wide as the phase is long, so a
    one-week phase gets a very narrow box. **One or two short words** — "Base", "Deload",
    "Power build". Not a description of what the phase does; the athlete reads that
    elsewhere. Fourteen characters is the hard ceiling, not the target.
- **weeklyStructure** — one entry per week, numbered 1 to \`durationWeeks\` with no gaps.
  Each week lists **all seven days in Monday-to-Sunday order**.
  - \`type\` is one of ${list('DayType')}.
  - \`intensity\` is one of ${list('Intensity')}, and is \`null\` on a REST day and on no
    other day type.
- **sessionsPerWeek** — the training-day count (any day whose type is not REST) of the
  athlete's typical week: the count that occurs in the most weeks. If two counts tie, use
  the higher one. Deload weeks may of course sit below it.
- **progression** — \`intraWeek\` describes how load moves across the days of a week;
  \`interWeek\` describes how it moves from week to week across the cycle. Plain prose, one
  short paragraph each. These are the only free-text fields in the response.
- **goals.maxWeeklyDays** — if non-null, the athlete's hard ceiling on how many days per
  week may carry effort of *any* kind: planned training days and commitment days from
  \`blockerCalendar\` both count, as **distinct days** — a day holding both a commitment and
  a training session counts once. You prescribe the frequency; this only caps it. When the
  calendar is crowded, place lighter sessions on already-committed days rather than spending
  a free day. A week whose commitments alone already exceed the ceiling is exempt from it —
  but add no occupied day beyond them.
- **lane, focus, queued** — echo \`goals.lane\`, \`goals.focusThisCycle\` and
  \`goals.queuedForLater\` from the payload back unchanged. They are stated so a mismatch is
  visible; the backend uses the payload's own values regardless.

## What you must not do

- **No exercises.** No lift names, no drills, no distances, no prescribed sets or reps —
  not in a day, not in the progression prose.
- **No game or practice days.** The athlete's commitments are already in
  \`blockerCalendar\`, and the app draws them onto the calendar itself. Plan *around* them:
  a hard one-off on Saturday is a reason for what you put on Friday and Sunday, never
  something you re-state as a training day.
  - **A day already carrying a HARD commitment must not be a HARD training day.** The
    athlete pays for that day once, at the game or the commitment. Give it REST, RECOVERY,
    or light work.
- **No coaching voice, headline, or summary.** The app composes what the athlete reads from
  the structure you return. Extra fields are rejected.

## Domain vocabulary

- \`goals.lane\` is one of ${list('Lane')}. LONGEVITY means: no red-zone weeks, adaptation
  compounds. PERFORMANCE means: maximal output, accepts wear.
- \`goals.focusThisCycle\` holds the at-most-two priorities programmed this cycle;
  \`queuedForLater\` waits for the next one. Priorities do not change inside a cycle.
- \`status.selfAssessment\` values are ${list('Rating')}, or \`null\` — and \`null\` means
  *unknown*, not average. Say nothing about a category the athlete skipped.
- \`blockerCalendar.recurring[].timeRange\` is opaque free text. Do not parse it; use it only
  as a signal about when a day is already occupied.
- \`status.injuries\` are the athlete's own words. Plan around them.
`.trim();

const RESPONSE_CONTRACT = `
## Response

Return **only** a JSON object matching this schema. No prose before it, no code fence, no
trailing commentary. Unknown fields are rejected.

\`\`\`json
{{SCHEMA}}
\`\`\`
`.trim();

export interface PromptInput {
  payload: unknown;
  startDateWindowDays: number;
}

export function systemPrompt(): string {
  return SYSTEM_PROMPT;
}

/** The first attempt. Payload verbatim, rules identical on every request. */
export function buildPrompt({ payload, startDateWindowDays }: PromptInput): string {
  return [
    '# Mesocycle request',
    '',
    'This is the athlete, assembled from their own durable records. Plan for this person.',
    '',
    '```json',
    JSON.stringify(payload, null, 2),
    '```',
    '',
    RULES.replace('{{WINDOW}}', String(startDateWindowDays)),
    '',
    RESPONSE_CONTRACT.replace('{{SCHEMA}}', JSON.stringify(planDraftSchema, null, 2)),
  ].join('\n');
}

/**
 * The one corrective re-request (story 3). It repeats the format instruction and names every
 * way the first answer failed — the model is told what was wrong, not merely that something
 * was.
 */
export function buildRetryPrompt(base: string, failures: string[]): string {
  return [
    base,
    '',
    '---',
    '',
    '## Your previous answer was rejected',
    '',
    'It failed these checks:',
    '',
    ...failures.map((f) => `- ${f}`),
    '',
    'Return a corrected JSON object. Same schema, same rules: only the JSON object, no prose,',
    'no code fence, no fields outside the schema.',
  ].join('\n');
}
