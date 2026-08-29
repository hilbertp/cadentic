/**
 * A complete, valid meso-request payload and the plan a well-behaved model would answer it
 * with. Both are built rather than pasted, so a schema change that makes them invalid shows
 * up as a failing contract test instead of quietly rotting.
 */

import type { AskInput, AskResult, Provider } from '../src/provider/types.js';

export const REQUEST_DATE = '2026-09-01';

export const payload = () => ({
  schemaVersion: 2,
  requestDate: REQUEST_DATE,
  profile: { age: 27, sex: 'MALE', heightCm: 191, weightKg: 88.0 },
  goals: {
    lane: 'LONGEVITY',
    priorities: ['CARDIO', 'EXPLOSIVENESS', 'STRENGTH'],
    focusCount: 2,
    focusThisCycle: ['CARDIO', 'EXPLOSIVENESS'],
    queuedForLater: ['STRENGTH'],
    excluded: ['HYPERTROPHY'],
  },
  status: {
    experience: 'Advanced — 5–10 years',
    selfAssessment: { CARDIO: 'MID', STRENGTH: 'MID', EXPLOSIVENESS: 'LOW', HYPERTROPHY: null },
    injuries: ['Lower-back disc (L4/L5)', 'Right ankle instability'],
  },
  blockerCalendar: {
    recurring: [
      { label: 'Team practice', days: ['TUESDAY', 'THURSDAY'], timeRange: '19:00–20:30', strain: 'MEDIUM' },
    ],
    oneOffs: [{ date: '2026-09-05', label: 'Round 1', strain: 'HARD' }],
  },
});

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;

/** Five training days and two REST, every week — so the modal count is unambiguously 5. */
const week = (n: number) => ({
  week: n,
  days: DAYS.map((day, i) => {
    const rest = i === 2 || i === 6; // Wednesday and Sunday
    return {
      day,
      type: rest ? 'REST' : i % 2 === 0 ? 'STRENGTH' : 'ENDURANCE',
      intensity: rest ? null : i === 4 ? 'HARD' : 'MEDIUM',
    };
  }),
});

export const DURATION_WEEKS = 8;

const planDraftBase = () => ({
  startDate: '2026-09-07',
  // Eight weeks = 56 days, so the last day is start + 55.
  endDate: '2026-11-01',
  durationWeeks: DURATION_WEEKS,
  sessionsPerWeek: 5,
  lane: 'LONGEVITY',
  focus: ['CARDIO', 'EXPLOSIVENESS'],
  queued: ['STRENGTH'],
  phases: [
    { phaseType: 'BASE', name: 'Base', weeks: 3 },
    { phaseType: 'BUILD', name: 'Build', weeks: 3 },
    { phaseType: 'DELOAD', name: 'Deload', weeks: 1 },
    { phaseType: 'PEAK', name: 'Peak', weeks: 1 },
  ],
  weeklyStructure: Array.from({ length: DURATION_WEEKS }, (_, i) => week(i + 1)),
  progression: {
    intraWeek: 'Hardest day mid-week, easing into the weekend fixture.',
    interWeek: 'Volume climbs for three weeks, then steps back before the peak.',
  },
});

export const planDraft = (over: Record<string, unknown> = {}) => {
  const draft: any = {
    ...planDraftBase(),
    ...over,
  };
  // A DELOAD week must be genuinely lighter (validate.ts): downgrade its HARD days so the
  // canonical fixture stays a plan that passes every layer. Derived from the final phase
  // list, so a test that overrides phases keeps control of its own weeks.
  let week = 1;
  for (const phase of draft.phases ?? []) {
    if (phase.phaseType === 'DELOAD') {
      for (let i = 0; i < phase.weeks; i += 1) {
        const wk = draft.weeklyStructure?.[week - 1 + i];
        if (wk) {
          wk.days = wk.days.map((d: any) =>
            d.intensity === 'HARD' ? { ...d, intensity: 'LIGHT' } : d,
          );
        }
      }
    }
    week += phase.weeks;
  }
  return draft;
};

/** A provider that answers from a script. Nothing here ever reaches Claude. */
export class FakeProvider implements Provider {
  readonly mode = 'max-plan-oauth' as const;
  readonly prompts: string[] = [];

  constructor(private readonly answers: Array<Partial<AskResult> | Error>) {}

  async ask(input: AskInput): Promise<AskResult> {
    this.prompts.push(input.prompt);
    const next = this.answers.shift();
    if (next === undefined) throw new Error('FakeProvider ran out of scripted answers');
    if (next instanceof Error) throw next;
    return { model: 'fake-model', raw: JSON.stringify(next.value ?? null), ...next } as AskResult;
  }
}
