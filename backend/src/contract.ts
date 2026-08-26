/**
 * The one contract, loaded once (Epic 2 story 0).
 *
 * `contracts/mesocycle-api.schema.json` is the single JSON Schema the app and this backend
 * both validate against — no hand-mirrored enums anywhere. This module compiles the pieces
 * the backend needs and re-exports the piece the provider needs (the model-facing draft
 * schema), so the same file that rejects a bad response is the file that told the model what
 * a good one looks like.
 *
 * On the app side the same file is a unit-test resource: `ContractSchemaTest` asserts the
 * Kotlin enums and required fields still match it. Drift is a failing test, not a runtime
 * surprise in the field.
 */

import ajvModule, { type ErrorObject, type ValidateFunction } from 'ajv/dist/2020.js';
import addFormatsModule from 'ajv-formats';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ERROR_CODES } from './errors.js';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Source layout puts the contract two levels up (`backend/src` → repo root); the build copies
 * it next to the compiled output so a deployed `dist/` needs nothing outside itself.
 */
const CANDIDATES = [
  join(here, '..', '..', 'contracts', 'mesocycle-api.schema.json'),
  join(here, 'contracts', 'mesocycle-api.schema.json'),
  join(here, '..', 'contracts', 'mesocycle-api.schema.json'),
];

function loadContract(): Record<string, any> {
  for (const path of CANDIDATES) {
    try {
      return JSON.parse(readFileSync(path, 'utf8'));
    } catch (e: any) {
      if (e?.code !== 'ENOENT') throw e;
    }
  }
  throw new Error(
    `mesocycle-api.schema.json not found. Looked in:\n  ${CANDIDATES.join('\n  ')}`,
  );
}

export const contract = loadContract();

const defs: Record<string, any> = contract.$defs;

// Ajv and ajv-formats are CommonJS with `export =`; under NodeNext ESM the callable sits on
// `.default` at runtime while the types point at the namespace. Unwrapping once here keeps
// the interop in one place.
const Ajv2020 = (ajvModule as any).default ?? ajvModule;
const addFormats = (addFormatsModule as any).default ?? addFormatsModule;

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema(contract, contract.$id);

/**
 * A `$defs` entry compiled on its own. The whole `$defs` map rides along as the subschema's
 * own root so its `#/$defs/...` references still resolve.
 */
function compile(name: string): ValidateFunction {
  return ajv.compile({ ...defs[name], $defs: defs });
}

export const validateRequestPayload = compile('MesoRequestPayload');
export const validatePlanDraft = compile('PlanDraft');
export const validateMesocyclePlan = compile('MesocyclePlan');

/**
 * Handed to the Agent SDK as `outputFormat.schema`, and quoted into the prompt. Same object
 * that `validatePlanDraft` enforces — the model is shown exactly what it will be judged by.
 */
export const planDraftSchema: Record<string, unknown> = { ...defs.PlanDraft, $defs: defs };

export const enumValues = (name: string): string[] => defs[name].enum;

export const PLAN_SCHEMA_VERSION: number = defs.MesocyclePlan.properties.schemaVersion.const;
export const PAYLOAD_SCHEMA_VERSION: number =
  defs.MesoRequestPayload.properties.schemaVersion.const;

/** Ajv errors as the app-facing field strings the contract's `fields` array is typed for. */
export function fieldErrors(errors: ErrorObject[] | null | undefined): string[] {
  if (!errors?.length) return [];
  const seen = new Set<string>();
  for (const e of errors) {
    const path = e.instancePath ? e.instancePath.replace(/^\//, '').replace(/\//g, '.') : '(root)';
    // Ajv already names the property for `required`; only `additionalProperties` leaves the
    // offending key out of its message, so only that one gets it appended.
    const extra =
      e.keyword === 'additionalProperties' ? ` '${(e.params as any).additionalProperty}'` : '';
    seen.add(`${path}: ${e.message}${extra}`);
  }
  return [...seen];
}

/**
 * Startup self-check. The TypeScript unions in this backend are convenience aliases over the
 * contract, not a second source of truth — so if one drifts, the process refuses to start
 * rather than serving a code the app has never heard of.
 */
export function assertContractAgreement(): void {
  const contractCodes: string[] = defs.ErrorCode.enum;
  const ours = [...ERROR_CODES];
  const missing = contractCodes.filter((c) => !ours.includes(c as any));
  const extra = ours.filter((c) => !contractCodes.includes(c));
  if (missing.length || extra.length) {
    throw new Error(
      `errors.ts and the contract disagree on ErrorCode — ` +
        `missing here: [${missing}], not in contract: [${extra}]`,
    );
  }
}
