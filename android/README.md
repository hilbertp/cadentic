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
