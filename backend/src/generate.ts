/**
 * The engine's orchestration (Epic 2 stories 1, 3 and the response half of 0).
 *
 * Prompt → provider → validate → **exactly one** corrective re-request → stamp. Two attempts
 * and no more: a model that has been shown its own validation errors once and still cannot
 * produce a conforming plan is not going to on the third try, and the athlete is watching a
 * spinner the whole time.
 */

import { PLAN_SCHEMA_VERSION, planDraftSchema } from './contract.js';
import { EngineError } from './errors.js';
import { PROMPT_VERSION, buildPrompt, buildRetryPrompt, systemPrompt } from './prompt.js';
import type { Provider } from './provider/index.js';
import { type PlanDraft, type RequestGoals, validateAnswer } from './validate.js';

export interface GenerateInput {
  payload: any;
  provider: Provider;
  startDateWindowDays: number;
  signal: AbortSignal;
  /** Backend-side only, never returned to the app. */
  log: (event: Record<string, unknown>) => void;
  logRaw: boolean;
}

export interface MesocyclePlan extends PlanDraft {
  schemaVersion: number;
  generatedBy: { mode: string; model: string; promptVersion: number };
}

export async function generatePlan(input: GenerateInput): Promise<MesocyclePlan> {
  const { payload, provider, startDateWindowDays, signal, log, logRaw } = input;

  const request: RequestGoals = {
    requestDate: payload.requestDate,
    lane: payload.goals.lane,
    focusThisCycle: payload.goals.focusThisCycle,
    queuedForLater: payload.goals.queuedForLater,
    hardBlockerDates: (payload.blockerCalendar?.oneOffs ?? [])
      .filter((o: any) => o.strain === 'HARD')
      .map((o: any) => o.date),
    hardBlockerDays: (payload.blockerCalendar?.recurring ?? [])
      .filter((r: any) => r.strain === 'HARD')
      .flatMap((r: any) => r.days),
  };

  const basePrompt = buildPrompt({ payload, startDateWindowDays });
  let prompt = basePrompt;
  let lastFailures: string[] = [];
  let model = '';

  // Two attempts: the first, then one that has been told exactly what was wrong with it.
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const answer = await provider.ask({
      systemPrompt: systemPrompt(),
      prompt,
      schema: planDraftSchema,
      signal,
    });
    model = answer.model;

    const failures = answer.parseError
      ? [answer.parseError]
      : validateAnswer(answer.value, request, startDateWindowDays).failures;

    // Both attempts are logged with the prompt version and the raw response, so a plan that
    // came out wrong can be reconstructed from the backend's own record. Backend-side only.
    log({
      event: 'generation-attempt',
      attempt,
      promptVersion: PROMPT_VERSION,
      model,
      mode: provider.mode,
      costUsd: answer.costUsd,
      ok: failures.length === 0,
      failures,
      ...(logRaw ? { raw: answer.raw } : {}),
    });

    if (failures.length === 0) {
      const plan = answer.value as PlanDraft;
      return stamp(plan, payload, provider, model);
    }

    lastFailures = failures;
    if (attempt === 1) prompt = buildRetryPrompt(basePrompt, failures);
  }

  throw new EngineError(
    'format-failed',
    'Claude could not produce a usable mesocycle after two attempts.',
    // The failures are the model's problem, not the athlete's — they go to the log above,
    // and the app gets one sentence it can actually show. `fields` is reserved for
    // payload-invalid, where the named field really is the caller's to fix.
  );
}

/**
 * The backend's own values win on `lane`, `focus` and `queued`. The model echoed them and the
 * echo was checked, but the Goals artifact is the single source of truth for priorities — so
 * what gets persisted is the request's copy, not the model's.
 */
function stamp(plan: PlanDraft, payload: any, provider: Provider, model: string): MesocyclePlan {
  return {
    schemaVersion: PLAN_SCHEMA_VERSION,
    generatedBy: { mode: provider.mode, model, promptVersion: PROMPT_VERSION },
    startDate: plan.startDate,
    endDate: plan.endDate,
    durationWeeks: plan.durationWeeks,
    sessionsPerWeek: plan.sessionsPerWeek,
    lane: payload.goals.lane,
    focus: payload.goals.focusThisCycle,
    queued: payload.goals.queuedForLater,
    phases: plan.phases,
    weeklyStructure: plan.weeklyStructure,
    progression: plan.progression,
  };
}

export { PROMPT_VERSION };
