# Handoff: Cadentic — Onboarding Flow (Android, native)

## Overview
Cadentic is a longevity-first structured training app (see `cadentic_prd_v_1.md`, included). This handoff covers the complete **onboarding journey** — 4 screens ending in mesocycle approval:

1. **Baseline** — identity, body data, location, per-category fitness self-assessment
2. **Priorities & guardrails** — ranked priorities with a hard focus rule, longevity-vs-performance lane, injuries
3. **Blockers & standing conditions** — recurring practices, one-off game days, auto-detected climate
4. **Mesocycle proposal** — heat-aware 12-week plan the athlete reviews and approves

Persona used in all copy: team-sport athlete (basketball), goals = elite endurance + dunk, longevity-first, based in Limassol, Cyprus, starting late August (hot, humid, not heat-adapted).

## About the Design Files
The files in this bundle are **design references created in HTML** — prototypes showing intended look and behavior, **not production code to copy directly**. The task is to **recreate these designs natively on Android** (Jetpack Compose recommended; Material 3 as the base system, restyled with the tokens below). If the target codebase already has patterns/components, use those. `Cadentic Onboarding.dc.html` is the design source; `android-frame.jsx` is preview chrome (device bezel/status bar) only — do not implement it.

## Fidelity
**High-fidelity.** Colors, typography, spacing, radii, and copy are final intent — recreate pixel-perfectly within platform conventions. The device frame assumes 412×892dp (Pixel-class); all px values below are dp at that size.

## Design Tokens

Colors (light theme only; dark mode is deliberately out of scope for now):
- Screen background: `#F5F2EB` (warm off-white)
- Card / input surface: `#FFFFFF`
- Ink (primary text): `#211E17`
- Secondary text: `#6E6858`
- Hairline border: `rgba(33,30,23,.12)` · subtle divider: `rgba(33,30,23,.07)` · faint fill: `rgba(33,30,23,.05–.07)`
- Accent (progress, CTAs, selection): `#2FBF8F` · tint fill: `rgba(47,191,143,.14)` · on-accent text: `#17352A`
- Accent deep (text-safe green, links, eyebrows): `#157D5F` (darker variant `#0F5C46`)
- Strain scale: light `#D9A62E` (text `#9A7414`), medium `#DE7B35` (text `#B05A1C`), hard/game `#C4513A` (text `#A33D28`), practice-outline `#B96A45`
- Tint fills for strain chips: base color at 14–16% alpha

Typography:
- Display / numerals / wordmark: **Sora** 500–700 (Google Fonts)
- UI / body: **Instrument Sans** 400–600 (Google Fonts)
- Wordmark: "CADENTIC", Sora 700, 12px, letter-spacing 3.5px
- H1: Sora 600, 24–26px, line-height 1.2
- Screen intro: Instrument Sans 400, 13.5–14.5px, lh 1.5, color secondary
- Section labels: Instrument Sans 600, 10.5–11px, letter-spacing 1.2–1.6px, UPPERCASE, secondary
- Field value: 15px/500 · card title: 14–15px/600 · meta: 12.5px secondary · helper: 11.5px secondary lh 1.45 · chip: 11–12px/600

Shape & spacing:
- Screen padding: 22px horizontal; content column, CTA pinned to bottom (`margin-top:auto`)
- Radii: inputs/cards 14px, large cards 16px, mini cells 11–12px, chips/CTA 999px (pill)
- Progress: 4 equal segments, 4px tall, radius 2, gap 6 — filled = accent, rest = `rgba(33,30,23,.12)`
- Primary CTA: full-width pill, 52–54px tall, accent bg, `#17352A` label, 15.5px/600
- Hit targets ≥44px everywhere

Header (every screen): wordmark left, step counter right ("N / 4", Sora 600 12px secondary), progress segments below (12px top margin).

## Screens

### Step 1 · Baseline ("First, your baseline.")
Purpose: collect static inputs (PRD §6.1) + fitness self-assessment per category.
Layout: header → H1 → intro ("Two minutes. Every number here shapes the program — nothing is cosmetic.") → field column (gap 11) → CTA "Continue".
Components:
- **Age + Sex row** (grid 1fr / 1.6fr, gap 12): Age filled input "27". Sex segmented control inside white container (padding 3, gap 3): selected pill = accent tint bg + 1.5px accent border + deep-green 600 text; unselected = plain, secondary text.
- **Height / Weight row** (2×1fr): value left ("191" / "88"), unit right-aligned ("cm" / "kg", 12.5px secondary).
- **Location**: value "Limassol, Cyprus", right-side hint "auto · edit" (12px secondary). Auto-detected, editable; drives climate (step 3) and seasonal logic.
- **Training experience**: select-style input "6 years, consistent" with chevron.
- **Current fitness — rate or skip**: white card, 4 rows divided by subtle dividers. Row = category name (12.5px/600, flex 1) + Low/Mid/High mini-pills (11px, padding 4×10; selected = accent tint + 1.5px accent border + deep green; unselected = faint fill, transparent 1.5px border to keep height) + "don't care" checkbox (16px, radius 5, 1.5px `rgba(33,30,23,.3)` border; checked = accent fill + ✓). Persona state: Cardio **Mid**, Strength **Mid**, Explosiveness **Low**, Hypertrophy **don't care** → its name goes secondary, pills at 45% opacity, checkbox filled, label turns deep green 600.
- Helper under card: “"Don't care" drops it as a goal — the plan may still touch it where it supports the rest.”

### Step 2 · Priorities & guardrails ("What matters, in order.")
Purpose: replaces the PRD's chat interview (deliberate deviation) with structured inputs.
Layout: header (2/4) → H1 → intro ("Rank it, flag what's fragile, pick your lane. No essay needed.") → three sections → CTA "Continue".
Components:
- **Priorities — drag to order**: white card, rows = drag handle (three 12×2px bars, `rgba(33,30,23,.25)`) + number badge (20px circle, faint fill, Sora 600 11px) + title/subtitle + status chip.
  - 1 Endurance — "aerobic engine" — chip **this cycle** (accent tint / deep green)
  - 2 Explosiveness — "vertical — the dunk" — chip **this cycle**
  - **Focus line divider**: faint band (`rgba(33,30,23,.03)`) with dashed rules and centered label "FOCUS LINE — BELOW WAITS" (10px, ls 1px, 600)
  - 3 Strength — "base numbers hold" — chip **later cycles** (faint fill/secondary), whole row at 55% opacity
  - Rule (also in helper copy): only the top **two** priorities get programmed this cycle; anything dragged below the line is queued for later cycles. Categories marked "don't care" in step 1 never appear here.
- **Your lane**: two cards side by side (gap 8). Selected "Longevity first — no red-zone weeks, adaptation compounds" (accent tint + 1.5px accent border, deep-green text). Unselected "Pure performance — maximal output, accepts wear" (white, hairline). Single-select.
- **Injuries & limitations**: removable chips ("Lower-back disc (L4/L5)", "Right ankle instability", each with ×) + dashed "+ Add" chip. Helper: "Chronic, healing, or structural — the plan routes around these, permanently."

### Step 3 · Blockers & standing conditions ("What must the plan respect?")
Purpose: PRD §8 step 3, with two model decisions: **practices recur weekly; games are singular dated events** (82/yr or 13/yr depending on sport), and **climate is an auto-added standing condition**, not a user-managed blocker.
Layout: header (3/4) → H1 → intro ("Practices repeat weekly. Games land where the league puts them.") → horizon card → note → sources list → CTA "Generate my mesocycle".
Components:
- **Next-6-weeks horizon grid**: white card. Grid = 34px week-label column + 7 day columns (M T W T F S S, 10px secondary). Rows labeled by week start ("Aug 24", "Aug 31", …). Cells 13px tall, radius 4: empty = `rgba(33,30,23,.05)`; practice = 1px `#DE7B35` outline (transparent fill); game = `#C4513A` fill. Persona data shows irregularity: games Sat wk1, Fri wk2, none wk3, Sun wk4, none wk5, Fri+Sat back-to-back wk6; practices every Tue+Thu. Legend beneath: "Practice · weekly" (outline swatch), "Game · one-off" (fill swatch).
- Note (12px secondary): "Two games one week, none the next, back-to-back in October — the engine plans each week around what's really there."
- **Sources list** (the model: rows are *sources* of constraints, each managed, not raw entries):
  - "Season schedule · 13 fixtures" — "Imported · league calendar · updates itself" — action **Manage** (game dot `#C4513A`)
  - "Team practice" — "Every Tue & Thu · 19:00–20:30 · Medium" — action **Edit** (practice dot `#DE7B35`)
  - "August heat — Limassol" — "33° / dew 24° · not adapted · auto" — action **Edit**; card border tinted amber (`rgba(217,166,46,.5)`), dot `#D9A62E`. Auto-created from location; cannot be deleted, only corrected ("I'm already adapted").
  - Dashed "+ Add one-off or recurring"
- Every blocker carries a **strain attribute** (Light/Medium/Hard) feeding the weekly planning engine (PRD §8).

### Step 4 · Mesocycle proposal ("12 weeks, engine first.")
Purpose: PRD §8 step 4 — generated proposal; onboarding ends at approval. Heat-aware.
Layout: header (4/4, all segments filled) → eyebrow "PROPOSAL — REVIEW & APPROVE" (10.5px, ls 1.6, deep green) → H1 → meta line "Aug 24 → Nov 15 · 5 sessions / wk · longevity-first" → heat banner → phase timeline → acclimation legend → session windows → coach note → CTAs.
Components:
- **Heat banner**: amber tint card (`rgba(217,166,46,.13)` bg, `rgba(217,166,46,.45)` border, radius 12) with amber dot: "**Humid heat, not yet adapted** — weeks 1–3 ramp exposure; key sessions 06:30 or pool."
- **Phase timeline**: one row, 46px tall, gap 4; segment widths proportional to weeks (flex 4/5/2/1.4): Base wk1–4 (faint ink fill), Build wk5–9 (accent 18%), Peak wk10–11 (accent 38%), Deload wk12 (white + 1.5px dashed accent border). Labels inside: name 12px/600 + weeks 10.5px. Base segment carries a diagonal-stripe overlay on its first 72% (`repeating-linear-gradient(-55deg, rgba(217,166,46,.18) 0 5px, transparent 5px 10px)`) = heat-acclimation ramp. Legend beneath with striped swatch: "heat-acclimation ramp · wk 1–3".
- **Session windows · August** (3 cards): "06:30 — hard sessions", "Pool — hot-day conditioning", "10–18 — no outdoor work" (this one bordered `rgba(196,81,58,.4)`, value in `#A33D28`). Values Sora 600 15px, captions 11px secondary.
- **Coach's note** card (13px, lh 1.5; lead-in "Coach's note." in deep green 600): "August in Limassol runs 33° with a 24° dew point, and you're starting cold on heat. So weeks 1–3 build your aerobic base *and* your sweat response at once — easy outdoor minutes early, hard work in water or AC. By late September the cap lifts and the build phase inherits a body that handles the island."
- **CTAs**: primary pill "Approve mesocycle"; below, centered text button "Ask for changes" (13.5px/600, underline, offset 3px). Negotiation UX after "Ask for changes" is **not designed yet** (open in PRD).

## Interactions & Behavior
- **Navigation**: linear 4-step flow; progress segments animate fill on step entry (~250ms ease-out). Back = system back gesture.
- **Continue** buttons validate current step; disabled state not designed (assume 60% opacity accent).
- Step 1: segmented + pill selectors are single-select per group; "don't care" toggles a category row into the dimmed state (200ms opacity/color transition) and removes it from step 2's ranking list live.
- Step 2: long-press-drag to reorder priorities; rows crossing the focus line swap chips ("this cycle" ↔ "later cycles") and dim/undim. Never more than 2 above the line — dragging a 3rd above pushes the lowest below (with a brief snackbar explaining the focus rule). Lane cards single-select. Injury chips: × removes; "+ Add" opens a text sheet.
- Step 3: "Import season schedule" → file/calendar import flow (not designed); imported fixtures render in the horizon grid immediately. "+ Add one-off or recurring" opens an add sheet with date/recurrence + strain (Light/Medium/Hard). The climate row is system-managed: edit allows only correcting adaptation status.
- **Generate my mesocycle**: shows a ~20s generation state (not designed — keep minimal: wordmark + progress; copy elsewhere promises "Nothing locks until you approve it").
- Step 4: "Approve mesocycle" locks the cycle (PRD: fixed once approved) and exits onboarding. "Ask for changes" → negotiation flow, TBD.
- No dark mode for now.

## State Management
Single onboarding draft object, persisted between steps:
- `profile`: age, sex, heightCm, weightKg, location {city, country, lat/lng}, experience, fitnessSelfAssessment: {cardio|strength|explosiveness|hypertrophy: "low"|"mid"|"high"|"dont_care"}
- `priorities`: ordered array of category ids (excluding dont_care), `focusCount = 2` (indices < 2 are active this cycle)
- `lane`: "longevity" | "performance"
- `injuries`: string[]
- `constraints`: recurring[] {label, days, timeRange, strain}, fixtures[] {date, label, time, homeAway, strain} (imported or manual), conditions[] {type:"climate", auto:true, tempC, dewPointC, adapted:boolean, easesBy}
- `proposal` (server-generated): dateRange, sessionsPerWeek, phases[] {name, weeks}, acclimationWeeks, sessionWindows[], coachNote, weeklyTemplate
- `status`: draft → generating → proposed → approved
- Climate data fetched from location (monthly normals + adaptation status from user input); fixture imports update `constraints.fixtures` and re-render the horizon grid.

## Assets
No raster assets. Fonts from Google Fonts (Sora, Instrument Sans). All glyphs (drag bars, checkmark ✓, chevron ▾, ×, →) are text/CSS — use platform icons natively (Material Symbols equivalents: drag_handle, check, expand_more, close).

## Files
- `Cadentic Onboarding.dc.html` — the design document (brief + the 4-screen journey, in order). Open in a browser via the Claude Design project for a live render; otherwise read the inline-styled markup directly — every value above appears literally in it.
- `android-frame.jsx` — preview device chrome only (bezel, status bar, gesture nav). Not part of the product.
- `cadentic_prd_v_1.md` — the product requirements document this flow implements (see §6, §8; note the step-2 deviation and the added climate/games modeling decisions documented above).
