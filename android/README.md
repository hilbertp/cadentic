# Cadentic — Android (Onboarding)

Native Jetpack Compose implementation of the onboarding flow from
`../design_handoff_cadentic_onboarding/` (4 steps, ending at mesocycle approval).

## Build & run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cadentic.app/.MainActivity
```

Requires JDK 17 and the Android SDK (compileSdk 35). `local.properties` points at
the SDK; regenerate it if your SDK lives elsewhere (`sdk.dir=<path>`).

**Generation needs the backend running** (Epic 2). Start `../backend` first — see
its README — then point the app at it:

```bash
./gradlew assembleDebug -Pcadentic.engineSharedSecret=<the backend's CADENTIC_SHARED_SECRET>
```

`cadentic.engineBaseUrl` defaults to `http://10.0.2.2:8787`, which is the
emulator's alias for the host machine's loopback. On a physical device, pass the
dev machine's LAN address and start the backend with a matching `HOST`:

```bash
./gradlew assembleDebug -Pcadentic.engineBaseUrl=http://192.168.1.42:8787 -Pcadentic.engineSharedSecret=<secret>
```

Both properties also work from `~/.gradle/gradle.properties` or `local.properties`,
which is the less tedious place to keep them.

## Structure

- `app/src/main/java/com/cadentic/app/`
  - `domain/Model.kt` — the onboarding draft object (profile, priorities, lane,
    injuries, constraints, proposal, status) exactly per the handoff's State
    Management section. `MAX_FOCUS_COUNT = 2` encodes the core rule: at most two
    priorities are programmed per mesocycle, and the athlete may narrow that to a
    single focus (`focusCount` on the draft, chosen on step 2). Priorities change
    **between** cycles, never within one.
  - `domain/Validation.kt` — the baseline step's accepted ranges, in the domain so the
    UI's `stepValid()` and the artifact writer cannot drift apart.
  - `domain/artifacts/` — the durable **athlete artifacts** (PRD §5.2) and the
    meso-request payload. `Artifacts.kt` holds the schemas, `ArtifactMapping.kt`
    the seam between the UI model and them, `ArtifactRepository.kt` the storage
    interface, `MesoRequest.kt` the payload the Mesocycle Engine will send to the
    LLM plus its completeness validation.
  - `data/JsonArtifactRepository.kt` — one JSON document per artifact in app-private
    storage, written atomically.
  - `domain/Engine.kt` — local stand-in for the server: persona seed data
    (13 game days, Tue/Thu practice) and the 6-week horizon derivation. The
    local `ProposalEngine` stub that used to live here is gone — Epic 2 replaced
    it with a real call behind `domain/MesocycleEngine.kt`.
  - `domain/MesocycleEngine.kt` — the engine's domain boundary: a payload in, a
    plan or a named `EngineError` out. The same seam the stub occupied, which is
    why swapping it touched no screen.
  - `domain/PlanNarrative.kt` — the proposal screen's words, composed from the
    plan's structure. Nothing the model wrote is ever displayed (PRD §8).
  - `data/HttpMesocycleEngine.kt` — the backend call. OkHttp +
    kotlinx.serialization; no prompt and no provider credential live in the app.
  - `OnboardingViewModel.kt` — single draft state + step machine + all
    interaction rules (focus rule with snackbar, live don't-care propagation,
    cancellable generation, back navigation).
  - `ui/theme/Tokens.kt` — the handoff's design tokens (colors, Sora +
    Instrument Sans variable fonts, type scale).
  - `ui/screens/` — one file per step, plus the generating, generation-failed and
    post-approval screens (all three intentionally minimal; undesigned in the
    handoff).

## Deliberate choices

- Persona data (27 / basketball) is prefilled so the app reproduces the design
  reference; every field is editable.
- **Deviation from the handoff (product decision, 2026-08-25): location and the
  climate/heat system are removed.** Athletes manage conditions themselves — no
  location field, no auto climate constraint, no heat banner/acclimation ramp/
  session windows in the proposal.
- **Beyond the handoff:** every day cell in the 6-week horizon calendar is
  tappable — practices open the recurring-blocker editor, one-offs (game days
  included) open their detail, empty days open the add sheet prefilled with
  that date. Everything on the calendar can be edited or deleted from its own
  sheet, including dropping a game you're not playing.
- Bottom sheets open fully expanded and scroll. A partially-expanded sheet
  silently hides whatever sits below the fold — that made Delete and even the
  Add CTA unreachable.
- **Constraints carry a stable `id` and every mutation matches on it.** Two
  blockers can legitimately look identical (same day, label, and strain);
  matching on value let one edit or delete rewrite its twin.
- **`horizon()` returns the full ranked entry list per day**, so a cell's colour
  and its tap target are derived from the same value and can never disagree.
  A day holding more than one thing shows a dot and opens a day sheet listing
  all of them — nothing on a shared date is invisible or unreachable.
- The grid shows real day numbers at a 44dp touch pitch. The handoff's 13px
  cells were a display-only swatch; making every day tappable meant they had to
  become a real calendar. Past days are dimmed and only open if something is
  already booked.
- Generation is a real call to the Mesocycle Engine backend (Epic 2). Multi-minute
  waits are expected and the client timeout sits above the backend's own budget, so
  a slow generation ends as a named `timeout` rather than a dropped socket.
- "Ask for changes" surfaces an honest snackbar — the negotiation flow is an
  open question in the PRD and deliberately unbuilt.
- Light theme only, per the handoff.

## Artifacts (Epic 1)

Onboarding no longer holds the athlete's data only in memory. Every step writes
durable artifacts, and the app hydrates from them on launch — kill the process and
nothing is lost, and the future Mesocycle Engine reads artifacts, never UI state.

| Artifact | Written when | Holds |
|---|---|---|
| `athlete-profile.json` | step 1 → 2 | age, sex, height, weight (numbers) |
| `athlete-status.json` | step 1 → 2, step 2 → 3 | experience, per-category rating (`null` = skipped), injuries |
| `athlete-goals.json` | step 2 → 3 (and any forward step after an edit) | lane, ordered priorities, effective focus count, excluded categories, `lockedForCycle` |
| `blocker-calendar.json` | step 3 → generate | recurring blockers and one-offs, each with strain |
| `progression-log.json` | at approval | empty; schema fixed for the daily-tracking epic |

`MesoRequestAssembler.assemble(requestDate)` composes the first four into the
**meso-request payload** and fails with an error naming the artifact and field
rather than handing over a partial one. The progression log deliberately does not
feed it — the PRD routes that to the History Engine and Mesocycle Tracker.

### The meso-request payload

The contract the Mesocycle Engine builds its prompt from. Nested per artifact,
ids stripped, `requestDate` injected at composition time. Epic 2 sends this
verbatim as the request body — no envelope — and validates it against
`../contracts/mesocycle-api.schema.json`:

```json
{
  "schemaVersion": 2,
  "requestDate": "2026-08-26",
  "profile": { "age": 27, "sex": "MALE", "heightCm": 191, "weightKg": 88.0 },
  "goals": {
    "lane": "LONGEVITY",
    "priorities": ["CARDIO", "EXPLOSIVENESS", "STRENGTH"],
    "focusCount": 2,
    "focusThisCycle": ["CARDIO", "EXPLOSIVENESS"],
    "queuedForLater": ["STRENGTH"],
    "excluded": ["HYPERTROPHY"]
  },
  "status": {
    "experience": "Advanced — 5–10 years",
    "selfAssessment": { "CARDIO": "MID", "STRENGTH": "MID", "EXPLOSIVENESS": "LOW", "HYPERTROPHY": null },
    "injuries": ["Lower-back disc (L4/L5)", "Right ankle instability"]
  },
  "blockerCalendar": {
    "recurring": [
      { "label": "Team practice", "days": ["TUESDAY", "THURSDAY"], "timeRange": "19:00–20:30", "strain": "MEDIUM" }
    ],
    "oneOffs": [{ "date": "2026-08-29", "label": "Round 1", "strain": "HARD" }]
  }
}
```

Rules a consumer can rely on:

- **`requestDate` is always present** — the cycle is calendar-anchored and one-off
  blockers carry absolute dates, so phases and deloads cannot be laid out without
  it. Validation fails if it is missing.
- **The calendar has exactly two kinds:** `recurring` (weekly) and `oneOffs` (a
  single date). A league game is a one-off — there is no separate fixture kind and
  no schedule import behind one.
- **`focusCount` ≤ `min(2, priorities.size)`**, enforced both where the artifact is
  written and again on the payload. `focusThisCycle` and `queuedForLater` are the
  priority list split at that count.
- **A `selfAssessment` value of `null` means unknown**, not average. All four
  categories are always present as keys.
- **`excluded` categories are a goals decision**, not a status one — an excluded
  category may still carry a rating in `status.selfAssessment`.
- **`timeRange` is opaque free text.** Do not parse it.
- **An empty `blockerCalendar` is valid** — an athlete with nothing booked.
- Cycle N>1 needs no extra input: the Post-Mesocycle Review updates Status and
  Goals in place, so a later assembly is the same read of the same four artifacts.

### Decisions taken here

- **JSON documents, not Room or DataStore.** The artifacts are whole documents,
  always read and written whole, a few kB each, and destined to become request
  bodies against a server. No consumer queries them or updates them partially.
- **The domain classes stay as they are; the artifact split happens at mapping.**
  `Profile` holds UI strings and the self-assessment the PRD files under *Status*;
  refactoring it would have churned all four screens to move one map.
- **The goals lock is enforced in the repository.** It is the single write
  mechanism, so a caller that has never heard of the lock still cannot change
  priorities inside an approved cycle. The lock lives in the artifact, so a restart
  does not reopen it.
- **The payload is nested per artifact, with blocker ids stripped.** Ids are local
  storage handles that mean nothing to a planner.
- **No separate fixture kind (schema v2).** League games were once modelled apart
  from one-offs, on the assumption of a schedule import that would own their dates
  and leave the athlete only the strain. No import exists — PRD §18 parks it — so
  the distinction bought nothing and the athlete now owns game days like any other
  blocker. v1 stores migrate on read: fixtures fold into `oneOffs`,
  `fixtureSourceLabel` is dropped.
- **Blocker ids: persisted, with the counter re-seeded on load.** The artifact
  already carries every live id, so the high-water mark needs no document of its
  own and `Long` ids stay as the UI knows them.
- **Writes are synchronous.** Each is one small file replaced atomically, and an
  approval must be durable before the athlete sees it confirmed.
- **The step index is not persisted.** A restart mid-onboarding restores the data
  and resumes at step 1 with the completed steps prefilled; nothing downstream
  needs a step pointer. An *approved* cycle does skip onboarding entirely.
- **An unreadable store is never overwritten.** An artifact from a newer build, or
  a corrupt one, blocks writes for the session and surfaces a named message —
  onboarding still runs in memory, and approval refuses rather than confirming a
  lock that was not written.

## The Mesocycle Engine (Epic 2)

Generation is no longer a local stub. Tapping **generate** assembles the
meso-request payload from artifacts, posts it to the backend, and renders the
plan Claude actually produced.

```
step 3 writes ──▶ MesoRequestAssembler ──▶ HttpMesocycleEngine ──▶ backend ──▶ Claude
                                                                     │
   ProposalScreen ◀── Proposal (derived) ◀── mesocycle-plan.json ◀────┘
```

The app holds **no** provider credential and builds **no** prompt. It posts the
Epic 1 payload verbatim to one endpoint and gets back a plan the backend has
already validated. Mode A versus Mode B is a backend config the app cannot see.

### The Mesocycle Plan artifact

`mesocycle-plan.json` — duration, phases, per-day types and intensities, and
intra/inter-week progression. Never exercises: the mesocycle prescribes
structure, and the daily layer, which knows about equipment and facilities,
prescribes movement (PRD §5.1).

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-09-01T09:00:00Z",
  "generatedBy": { "mode": "max-plan-oauth", "model": "claude-opus-5", "promptVersion": 1 },
  "startDate": "2026-09-07", "endDate": "2026-11-01",
  "durationWeeks": 8, "sessionsPerWeek": 5,
  "lane": "LONGEVITY", "focus": ["CARDIO", "EXPLOSIVENESS"], "queued": ["STRENGTH"],
  "phases": [
    { "phaseType": "BASE", "name": "Base", "weeks": 3 },
    { "phaseType": "BUILD", "name": "Build", "weeks": 3 },
    { "phaseType": "DELOAD", "name": "Deload", "weeks": 1 },
    { "phaseType": "PEAK", "name": "Peak", "weeks": 1 }
  ],
  "weeklyStructure": [
    { "week": 1, "days": [{ "day": "MONDAY", "type": "STRENGTH", "intensity": "MEDIUM" }] }
  ],
  "progression": { "intraWeek": "…", "interWeek": "…" }
}
```

Rules a consumer can rely on:

- **`phaseType` is what you switch on.** `name` is display text the engine chose;
  a plan that calls its first phase "Foundation" still colours as a BASE segment.
- **`name` is capped at 14 characters**, and the prompt asks for one or two short
  words. Segment width is proportional to phase length, so a one-week phase is a
  narrow sliver — the first live plan came back with "Double-fixture unload" on
  one and it wrapped, pushing the week label out of the row. Even a short name
  can ellipsise there; the dashed border and the week number carry the meaning,
  and the coach's note names the deload weeks in full.
- **Deload timing is a `DELOAD` phase**, not a separate field. One representation.
- **`intensity` is `null` on a REST day and on no other day type.**
- **Every week lists all seven days, Monday→Sunday.** A day off is REST, never an
  omitted entry — so `sessionsPerWeek` is checkable against the weeks.
- **`lane`, `focus` and `queued` are backend-stamped from the payload.** The model
  echoes them so a contradiction is detectable, but the Goals artifact remains the
  single source of truth for priorities.
- **No `headline`, no `coachNote`.** Plan surfaces never render model free text
  (PRD §8). `progression` is the only model prose in the artifact, and the
  proposal screen does not show it — it is persisted for the daily layer.
- **`updatedAt` is app-side.** The backend returns a plan; the repository records
  when it wrote one.

### Approval: plan first, then the lock

On approval the write order is fixed, and the goals lock is minted from the plan
**as read back off disk**:

1. `mesocycle-plan.json`
2. `athlete-goals.json → lockedForCycle`, from the persisted plan's dates
3. `progression-log.json`, if it does not exist yet

The lock is the commit point the UI awaits. A crash between 1 and 2 leaves an
unapproved plan, never an approval with nothing behind it. *(This amends Epic 1
story 3, where the lock came from the in-memory `Proposal`.)*

**Half-state rule:** a plan with no lock is an abandoned attempt. It is not loaded
on launch, the athlete lands back in the proposal flow, and the next generate
deletes it. The reverse — a lock with no plan — can only come from a store written
before this artifact existed; the lock still means approved, and the screen falls
back to the dates it carries.

### When generation fails

`Status.FAILED` and an error slot on the draft, routed in `screenKey()` to a
minimal screen with the reason and a retry. The screen is deliberately unpolished:
like `GeneratingScreen`, it is undesigned in the handoff.

Two failures that look alike and are not:

| | Means | Fix |
|---|---|---|
| `BACKEND_UNREACHABLE` | The app never reached the backend | Connection, host, backend not running |
| `PROVIDER_UNREACHABLE` | The backend could not reach Claude | Backend-side |

Airplane mode exercises the first; blocking Claude with the backend up exercises
the second.

`back()` during GENERATING cancels the coroutine, which cancels the HTTP call,
which closes the socket — and the backend, seeing nobody left waiting, aborts the
generation. Walking away does not leave a plan being written for a screen no one
is looking at. A generation cut short by process death leaves nothing behind at
all: GENERATING is never persisted.

### Decisions taken here

- **OkHttp + kotlinx.serialization** (story 5 asks for the stack to be recorded).
  OkHttp because generation is a multi-minute call the athlete can abandon and
  `Call.cancel()` is a real cancellation; `HttpURLConnection` only offers
  `disconnect()` from another thread. kotlinx.serialization because the artifacts
  already use it, so the plan the engine returns and the plan written to disk
  decode through one serializer. Retrofit/Moshi would add a second JSON library
  and an interface layer over a single endpoint.
- **The body is read inside the cancellable suspend, not after it.** The obvious
  shape — suspend for the response, then read the body — leaves the cancellable
  region as soon as the headers land, and the body read is *blocking*: a coroutine
  cancelled there cannot reach a final state, so nothing fires to cancel the call.
  Back would look instant while the request kept running.
- **`headline` and `coachNote` are composed on the client** (open point 5). Either
  side could do it deterministically; the tie-breaker is that wording is a display
  concern — it belongs next to the screen that shows it, changes with design rather
  than with the engine, and keeps the response a pure data contract.
- **`Proposal` is derived, never stored.** `OnboardingDraft.proposal` is a computed
  view over `plan`, so the two cannot disagree.
- **The contract is a test resource, not a runtime dependency.** No JSON Schema
  validator ships in the APK — kotlinx.serialization already rejects unknown keys
  and bad enums. What it cannot catch is the two definitions drifting apart, and
  that is what `ContractSchemaTest` runs against
  `../contracts/mesocycle-api.schema.json` — the same file the backend validates
  with at runtime.
- **Cleartext is debug-only and file-scoped.** The network security config and the
  manifest entry pointing at it both live in `src/debug/`, so they are physically
  absent from a release build. There is no flag to forget.

### Tests


```bash
./gradlew testDebugUnitTest
```

JVM unit tests, no emulator. Process restart is simulated the way it actually
happens — a fresh repository, a fresh ViewModel, and the process-wide `Ids`
counter back at zero, with only the artifact directory surviving — so the
restart, twin-blocker and lock-durability tests exercise the same code paths a
real kill does.

Epic 2 adds four suites, none of which touch a network or an LLM:

| Suite | Covers |
|---|---|
| `ContractSchemaTest` | The Kotlin types against the shared JSON Schema |
| `MesocycleGenerationTest` | Generation, failure, retry, cancellation, the approval write order, the half-states |
| `HttpMesocycleEngineTest` | The wire, over a real socket (MockWebServer): headers, body, every error code, cancellation |
| `PlanNarrativeTest` | That the composed words are deterministic and track the plan |
