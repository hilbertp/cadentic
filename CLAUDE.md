# Cadentic

Longevity-training app. An athlete is onboarded in four steps, an LLM plans a **mesocycle**
around their real schedule, and priorities lock for the duration of that cycle.

- `android/` — the Jetpack Compose app (onboarding through mesocycle approval)
- `backend/` — the Mesocycle Engine: payload in, validated plan out
- `contracts/` — the JSON Schema both sides validate against
- `epics/` — the specs; each one records the decisions taken while implementing it
- `design_handoff_cadentic_onboarding/` — the PRD and design reference

---

## How we talk to Claude — read this before touching anything LLM-related

**We use the owner's Claude subscription over OAuth. We do not use a separately billed
Anthropic API key.**

This is a deliberate product decision, not a stopgap, and it is easy to "helpfully" undo.

### What that means in practice

- The backend calls Claude through the **Claude Agent SDK** (`@anthropic-ai/claude-agent-sdk`),
  which is Claude Code as a library. Every request bills the owner's Max plan.
- **On the owner's own machine there is nothing to configure.** The Agent SDK spawns Claude
  Code, which resolves the login already on the machine. If `claude` runs, the backend
  generates. `MODE_A_PERSONAL_USE=true` is the only required setting.
- `claude setup-token` is **optional** and only for CI, containers, or a server — anywhere
  with no interactive login. It mints a one-year `sk-ant-oat01-…` token for
  `CLAUDE_CODE_OAUTH_TOKEN`. On a laptop that is already signed in, setting one just
  authenticates the same account twice.
- The **raw Messages API rejects subscription tokens** outright. There is no lighter HTTP
  shortcut. If you find yourself reaching for `fetch` against `api.anthropic.com` for this
  path, you have taken a wrong turn.

### Do not

- **Do not add `ANTHROPIC_API_KEY`** to make something work. It outranks the subscription in
  Claude Code's credential order, so everything would appear to work while quietly billing
  the API instead. The backend refuses to start if it finds one — that refusal is the
  feature, not an obstacle to route around.
- Same for `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_PROFILE`, the WIF federation pair, and
  `CLAUDE_CODE_USE_BEDROCK` / `_VERTEX` / `_FOUNDRY`. Each bills something other than the
  subscription. All five are refused at startup for the same reason.
- Do not put any Claude credential in the Android app. The app holds none, builds no prompt,
  and talks only to our backend. A token in an APK is as leakable as a shipped API key
  (PRD §17).

### The one exception, which is not built yet

**Mode B** (Epic 2 story 6) is for *end users* supplying *their own* API keys, so their usage
bills to them. It is the only official per-user path — there is no mechanism for a user to
connect their own Max subscription to a third-party app. Today `AUTH_MODE=B` returns a typed
`provider-not-available` and never silently falls back to the owner's plan.

Mode A is supported **for personal and development use only**. A deployment serving anyone
but the account owner must run Mode B.

Details and rationale: [`backend/README.md`](backend/README.md) § Mode A.

---

## Working in this repo

- **Each epic in `epics/` is the spec and the record.** Open points get *answered* in the
  document as they are decided, so the reasoning survives. Read the relevant epic before
  changing the area it covers.
- **`contracts/mesocycle-api.schema.json` is the single source of truth** for the engine's
  request, response, and every shared enum. The backend compiles it with Ajv at runtime; the
  app asserts its Kotlin types against it in `ContractSchemaTest`. Never hand-mirror an enum
  — change the schema and let the tests find the drift.
- **Plan surfaces never render model free text** (PRD §8). The engine returns structure; every
  sentence an athlete reads is composed from that structure by `PlanNarrative.kt`.
- **The mesocycle prescribes structure, never exercises** (PRD §5.1). Exercises belong to the
  daily layer, which knows about equipment and facilities.

### Running it

```bash
cd backend && npm install && npm run dev
```

```bash
cd android && ./gradlew installDebug -Pcadentic.engineSharedSecret=<the backend's CADENTIC_SHARED_SECRET>
```

The emulator reaches the host at `10.0.2.2`, which is the debug default. For a physical
device, pass `-Pcadentic.engineBaseUrl=http://<LAN IP>:8787` and start the backend with a
matching `HOST`.

### Tests

```bash
cd backend && npm test
```

```bash
cd android && ./gradlew testDebugUnitTest
```

Neither suite touches a network or an LLM. Generation is faked at the provider seam, so a
green run says nothing about whether the live Agent SDK path works — that has to be exercised
by hand, and it is where both of Epic 2's real defects were found.
