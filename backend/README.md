# Cadentic — Mesocycle Engine (backend)

The thin backend from **Epic 2**. It takes the meso-request payload Epic 1 assembles, sends it
to Claude with a versioned standard prompt, validates what comes back, and returns a
Mesocycle Plan or one named error.

It exists for one reason: **the app must never hold a provider credential.** A plan token
inside an APK is exactly as leakable as a shipped API key (PRD §17), so the token lives here
and the app only ever talks to this endpoint.

```
Android app ──POST /v1/mesocycle-proposal──▶ this backend ──Agent SDK──▶ Claude
            ◀──── validated plan, or a typed error ────────
```

## Quick start

```bash
npm install
```

```bash
cp .env.example .env
```

Then fill in `.env` (see [Mode A](#mode-a-the-owners-claude-subscription)) and:

```bash
npm run dev
```

| Script | What it does |
|---|---|
| `npm run dev` | Runs from `src/` with `.env` loaded |
| `npm test` | Unit + endpoint tests. No network, no Claude |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run build` | Compiles to `dist/` and copies the contract beside it |
| `npm start` | Runs the built `dist/` |

## The endpoint

`POST /v1/mesocycle-proposal`

| | |
|---|---|
| **Body** | The Epic 1 meso-request payload, **verbatim** — no envelope, byte-identical to what `MesoRequestAssembler` produced |
| **`X-Cadentic-Secret`** | Required. Requests without it are refused |
| **`X-Request-Id`** | Optional. A duplicate joins the generation already running instead of starting a second one |
| **200** | A Mesocycle Plan |
| **non-2xx** | `{"error": {"code", "message", "retryAfterSeconds?", "fields?"}}` |

`GET /healthz` reports liveness and the configured mode. It says nothing about credentials.

### Errors

Every failure is one of these codes. Never a stack trace, never a provider internal.

| Code | HTTP | Means |
|---|---|---|
| `payload-invalid` | 400 | The payload failed the contract. `fields` names what |
| `unauthorized` | 401 | Missing or wrong shared secret |
| `rate-limited` | 429 | Subscription usage window hit, or Claude overloaded |
| `provider-not-available` | 501 | Configured for a mode this build cannot serve (today: Mode B) |
| `provider-unreachable` | 502 | The backend could not get a plan out of Claude |
| `format-failed` | 502 | Two attempts, neither produced a valid plan |
| `auth-failed` | 502 | The backend's own Claude credential was rejected |
| `timeout` | 504 | The generation exceeded the request budget |

`backend-unreachable` is deliberately **not** in this list. It means the app never reached the
backend, so it is minted client-side and never travels over the wire. Collapsing the two would
send people looking in the wrong place.

## The contract

`../contracts/mesocycle-api.schema.json` is the single definition of the request, the
response, the model-facing draft, and every enum. Three consumers, one file:

- this backend compiles it with Ajv at startup and validates both directions against it;
- the same subschema is handed to the model as the Agent SDK's `json_schema` output format —
  so the schema that judges the answer is the schema that asked for it;
- the Android app puts it on its unit-test classpath, where `ContractSchemaTest` asserts the
  Kotlin types still match.

Nothing is hand-mirrored. Adding a day type on one side fails a test on the other.

## Mode A: the owner's Claude subscription

A Max plan is not an API key — it is a claude.ai account. The sanctioned way to use one
programmatically is the **Claude Agent SDK**, which is Claude Code as a library. The raw
Messages API rejects subscription tokens outright, so there is no lighter shortcut.

There are two ways to give it that account, and neither is more official than the other —
they are the same subscription reached differently.

**On your own machine: nothing to do.** The Agent SDK spawns Claude Code, which resolves the
credential you already signed in with. If you can run `claude`, the backend can generate.
Set `MODE_A_PERSONAL_USE=true` and start it. This is the normal case for a dev backend, and
minting a token to sit beside a login you already have would authenticate the same account
twice.

**Where there is no interactive login** — CI, a container, a server — mint a portable one:

```bash
claude setup-token
```

It opens the same browser flow as `/login` and prints a one-year OAuth token
(`sk-ant-oat01-…`) **once**; it is saved nowhere. Put it in `.env` as
`CLAUDE_CODE_OAUTH_TOKEN`.

The trade-off is lifetime and reach. A `/login` credential expires and is renewed by signing
in again, and it lives in the OS keychain, so it works only for the same user on the same
machine. A setup token travels and lasts a year. The backend logs which one it is using at
startup.

> **Personal and development use only.** Running a subscription token through the Agent SDK
> headless is supported on that basis. Any deployment serving accounts other than the token
> owner's must run Mode B with per-user API keys. The backend refuses to start in Mode A
> without the acknowledgement flag, and logs the constraint at every startup.

**Several environment variables outrank the subscription** and would quietly bill something
else — the backend would start, requests would succeed, and the money would land somewhere
you did not intend. Mode A refuses to start if it finds any of them:

| Variable | Would bill |
|---|---|
| `CLAUDE_CODE_USE_BEDROCK` / `_VERTEX` / `_FOUNDRY` | That cloud account |
| `ANTHROPIC_AUTH_TOKEN` | Wherever the bearer token points |
| `ANTHROPIC_API_KEY` | The Claude API |
| `ANTHROPIC_PROFILE` | A Console/API organization — same browser flow, different bill |
| `ANTHROPIC_FEDERATION_RULE_ID` + `ANTHROPIC_ORGANIZATION_ID` | An API organization |

A silent wrong answer here is worse than a refusal, which is why it is a refusal.

Two options in `provider/agentSdk.ts` are load-bearing and should not be "simplified" away:

- **`settingSources: []`** — SDK isolation. Without it the SDK loads whatever
  `~/.claude/settings.json` and the working directory's `CLAUDE.md` happen to contain, and an
  unrelated file on the host machine becomes part of every athlete's prompt.
- **`allowedTools: []` with `permissionMode: 'dontAsk'`** — this is a reasoning task with no
  filesystem or network in it. Nothing is pre-approved, and a tool attempt is denied
  immediately rather than hanging on a prompt no one is there to answer.
- **`maxTurns: 4`, and specifically not 1.** With `outputFormat: json_schema` the answer
  arrives as an end-turn tool call, which spends a turn of its own — so one turn only
  succeeds when the model emits the object immediately, and fails with `error_max_turns`
  whenever it takes any intermediate step. That is a coin flip, and it lost live. A few turns
  is not a licence to wander: `allowedTools` is empty, so there is nothing to call.

## Deploying to Cloud Run

The dev loop runs this on a laptop, which means the phone only works on the same Wi-Fi. Cloud
Run fixes that: a public HTTPS endpoint the app reaches from anywhere, scaled to zero so idle
costs nothing.

```bash
./backend/deploy.sh <gcp-project-id> [region]
```

Run it **from the repo root** — the image needs `contracts/` in its build context. It is
idempotent: it creates what is missing, reuses what is there, and never overwrites or prints
an existing secret.

Prerequisites, all one-time:

- `gcloud auth login`, and a project with billing enabled (the free tier is applied as a
  spending-based discount, so a card is required even though this stays free).
- `claude setup-token`. **Here it is genuinely required** — there is no interactive login on
  a Cloud Run container for the Agent SDK to resolve, which is precisely the case the token
  exists for. The script prompts for it with hidden input and stores it in Secret Manager;
  it is never baked into the image or passed on a command line.

What the deploy settings are for:

| Flag | Why |
|---|---|
| `--timeout 600` | Above the backend's own 300s budget, so a slow generation ends as our named `timeout` rather than Cloud Run cutting the connection |
| `--max-instances 2` | The in-flight request-id join is per instance, and this caps what a runaway could ever spend |
| `--min-instances 0` | Idle costs nothing. The price is a cold start on the first request after a quiet spell |
| `--memory 1Gi` | Node plus the extracted CLI |

### Image notes

Two things in the Dockerfile are not incidental:

- **`--platform linux/amd64`.** Cloud Run does not run arm64, and building on an Apple Silicon
  machine produces arm64 by default — a mistake that only surfaces at deploy time.
- **The musl CLI build is deleted after `npm ci`.** The SDK ships the Claude Code CLI as
  per-platform optional packages and npm installs both the glibc and musl variants; on a
  Debian base the musl one can never execute. Removing it took the image from 1.6 GB to
  522 MB, which is 1 GB less to pull on every cold start.

### What this exposes

`--allow-unauthenticated` means anyone who knows the URL can reach the endpoint. The shared
secret is the only thing between a stranger and the owner's Claude subscription — and it is
compiled into the APK, readable by anyone who unzips it. For a personal test app that is an
acceptable risk; it stops being one the moment the APK is handed around. Rotate the secret
(`gcloud secrets versions add cadentic-shared-secret`) and redeploy if that ever happens.

## Mode B: user-supplied API keys

Not built (Epic 2 story 6). `AUTH_MODE=B` returns the typed `provider-not-available` error the
app already renders — never a 500, and **never a silent fall-back to the owner's
subscription**. `provider/messagesApi.ts` documents what lands there when story 6 ships.

Everything above the provider seam is provider-blind: the endpoint, the prompt, the validation
and the re-request loop are untouched by which mode is running, and the app's request is
byte-identical either way. That is what "A→B is a config swap" has to mean.

## Generation

1. **Prompt** (`prompt.ts`, `PROMPT_VERSION`) — the payload verbatim, the rules, the response
   schema. No lane-conditional prose: the template inserts the lane value and describes both
   lane values identically on every request, so a plan differs because the model reasoned
   about the lane, not because the backend fed it different instructions.
2. **Ask** — Mode A's provider, with the draft schema as native structured output.
3. **Validate** (`validate.ts`) — three layers, all machine-enforced:
   - *schema*: strict and closed. `additionalProperties: false` everywhere and a pinned day
     enum mean "never exercises" is a rejection, not a hope;
   - *structural*: phase weeks sum to the duration, weeks contiguous, span matches the week
     count, REST days carry no intensity, `sessionsPerWeek` matches the weeks;
   - *cross-request*: the start date is inside the window, and the goals the model echoed
     match the ones we sent.
4. **Re-request once**, appending every failure as a sentence the model can act on. A second
   failure is `format-failed`. Two attempts and no more — the athlete is watching a spinner.
5. **Stamp** — `schemaVersion`, `generatedBy`, and `lane`/`focus`/`queued` from the *payload*.
   The model echoes those so a contradiction is detectable; the Goals artifact stays the
   single source of truth for priorities.

Both attempts are logged with the prompt version and the outcome (and the raw response when
`LOG_RAW_RESPONSES=true`), backend-side only.

## Decisions taken here

- **TypeScript over Python** (open point 1). Both are Agent SDK languages; TypeScript wins on
  JSON Schema tooling — Ajv compiles the shared contract directly, so the file the app tests
  against is the file that validates at runtime, with no translation layer.
- **Node's built-in `http`, no framework.** One endpoint, one health check. Express would add
  a dependency tree to route two paths.
- **Cancel propagation, with an in-flight join by request id** (open point 2). The athlete
  pressing back closes the socket and the generation goes with it — no orphaned request
  burning subscription usage on a plan nobody will see. The in-flight map covers the other
  half: a duplicate request id joins the generation already running, so a double tap costs one
  generation. A generation is aborted only once every waiter has gone.
- **`claude-opus-5`, configurable** (open point 4). Planning a mesocycle around injuries,
  fixtures and a lane is a reasoning task, and the plan is what the athlete's next two months
  run on.
- **The start-date window is 14 days, tunable** (open point 6). It is a *validity* check: a
  plan outside the window is refused and re-requested, never quietly moved. The app does not
  modify the plan it shows (PRD §9 step 4, §15).
- **No duration band.** Whether to constrain the model to 4–16 weeks is an open question for
  the product owner (PRD §18). Until it is answered the prompt states no band and whatever
  duration comes back is accepted.
- **`headline` and `coachNote` are not in the contract.** Plan surfaces never render model free
  text (PRD §8), so they are composed on the client from the structure — see
  `PlanNarrative.kt` and the note in `../android/README.md`.

## Configuration

Everything is environment; see `.env.example` for the annotated set.

| Variable | Default | |
|---|---|---|
| `CADENTIC_SHARED_SECRET` | — | Required, ≥ 16 chars |
| `AUTH_MODE` | `A` | `A` or `B` |
| `CLAUDE_CODE_OAUTH_TOKEN` | — | Required in Mode A |
| `MODE_A_PERSONAL_USE` | `false` | Must be `true` to start Mode A |
| `HOST` / `PORT` | `127.0.0.1` / `8787` | Private network only |
| `MESOCYCLE_MODEL` | `claude-opus-5` | |
| `REQUEST_TIMEOUT_MS` | `300000` | Whole request, both attempts |
| `START_DATE_WINDOW_DAYS` | `14` | |
| `LOG_RAW_RESPONSES` | `false` | |

The app's client timeout sits deliberately **above** `REQUEST_TIMEOUT_MS`, so a slow
generation ends as this backend's named `timeout` rather than as a socket the app gave up on.

## Tests

```bash
npm test
```

No network and no Claude: a scripted fake provider stands in at the seam, and the endpoint
tests run over a real socket. They cover the contract, the three validation layers, the
re-request loop, error mapping, the shared secret, request-id joining, cancellation, and the
timeout budget.
