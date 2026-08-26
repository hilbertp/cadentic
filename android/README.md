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
    (13 imported fixtures, Tue/Thu practice), the 6-week horizon derivation, and
    the mesocycle proposal generator. `ProposalEngine.generate` is a pure local
    stub — it does **not** call an LLM and builds no request payload. Swap it for
    a real API call when the engine lands.
  - `OnboardingViewModel.kt` — single draft state + step machine + all
    interaction rules (focus rule with snackbar, live don't-care propagation,
    cancellable generation, back navigation).
  - `ui/theme/Tokens.kt` — the handoff's design tokens (colors, Sora +
    Instrument Sans variable fonts, type scale).
  - `ui/screens/` — one file per step, plus the generating and post-approval
    screens (both intentionally minimal; undesigned in the handoff).

## Deliberate choices

- Persona data (27 / basketball) is prefilled so the app reproduces the design
  reference; every field is editable.
- **Deviation from the handoff (product decision, 2026-08-25): location and the
  climate/heat system are removed.** Athletes manage conditions themselves — no
  location field, no auto climate constraint, no heat banner/acclimation ramp/
  session windows in the proposal.
- **Beyond the handoff:** every day cell in the 6-week horizon calendar is
  tappable — games open a per-fixture sheet (date is the league's, strain is
  the athlete's call), practices open the recurring-blocker editor, one-offs
  open their detail, empty days open the add sheet prefilled with that date.
  Everything on the calendar can be edited or deleted from its own sheet,
  including declining a league fixture ("I'm not playing this one").
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
- Generation is simulated locally (~4s; production spec says ~20s server-side).
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
| `blocker-calendar.json` | step 3 → generate | recurring blockers, fixtures, one-offs, strain, `fixtureSourceLabel` |
| `progression-log.json` | at approval | empty; schema fixed for the daily-tracking epic |

`MesoRequestAssembler.assemble(requestDate)` composes the first four into the
**meso-request payload** and fails with an error naming the artifact and field
rather than handing over a partial one. The progression log deliberately does not
feed it — the PRD routes that to the History Engine and Mesocycle Tracker.

### The meso-request payload

The contract the Mesocycle Engine will build its prompt from. Nested per artifact,
ids stripped, `requestDate` injected at composition time:

```json
{
  "schemaVersion": 1,
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
    "fixtures": [{ "date": "2026-08-29", "label": "Round 1", "strain": "HARD" }],
    "fixtureSourceLabel": "Season schedule",
    "oneOffs": []
  }
}
```

Rules a consumer can rely on:

- **`requestDate` is always present** — the cycle is calendar-anchored and fixtures
  carry absolute dates, so phases and deloads cannot be laid out without it.
  Validation fails if it is missing.
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
  storage handles that mean nothing to a planner; `fixtureSourceLabel` is kept,
  because it says how firm those dates are.
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

### Tests

```bash
./gradlew testDebugUnitTest
```

JVM unit tests, no emulator. Process restart is simulated the way it actually
happens — a fresh repository, a fresh ViewModel, and the process-wide `Ids`
counter back at zero, with only the artifact directory surviving — so the
restart, twin-blocker and lock-durability tests exercise the same code paths a
real kill does.
