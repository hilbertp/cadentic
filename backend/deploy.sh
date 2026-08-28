#!/usr/bin/env bash
#
# Deploy the Mesocycle Engine to Cloud Run.
#
#   ./backend/deploy.sh <gcp-project-id> [region]
#
# Run it from the repo root — the image needs `contracts/` in its build context.
#
# Idempotent: safe to re-run for every deploy. It creates what is missing and reuses what is
# already there, including the secrets, which it never prints and never overwrites.

set -euo pipefail

PROJECT="${1:?usage: ./backend/deploy.sh <gcp-project-id> [region]}"
REGION="${2:-europe-west1}"
SERVICE="cadentic-engine"
REPO="cadentic"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}/${SERVICE}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "Project ${PROJECT}, region ${REGION}"
gcloud config set project "$PROJECT" >/dev/null

say "Enabling the APIs this needs (no-op once they are on)"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  --quiet

say "Artifact Registry repository"
gcloud artifacts repositories describe "$REPO" --location "$REGION" >/dev/null 2>&1 || \
  gcloud artifacts repositories create "$REPO" \
    --repository-format=docker --location="$REGION" \
    --description="Cadentic container images" --quiet

# --- Secrets -----------------------------------------------------------------
#
# Neither value is ever baked into the image or passed on a command line that would land in
# shell history. Cloud Run mounts them as environment variables at run time.

ensure_secret() {          # ensure_secret NAME PROMPT [GENERATOR]
  local name="$1" prompt="$2" generator="${3:-}"
  if gcloud secrets describe "$name" >/dev/null 2>&1; then
    echo "  ${name}: already exists, left alone"
    return
  fi
  gcloud secrets create "$name" --replication-policy=automatic --quiet
  if [ -n "$generator" ]; then
    eval "$generator" | gcloud secrets versions add "$name" --data-file=- --quiet
    echo "  ${name}: generated"
  else
    printf '\n  %s\n  ' "$prompt"
    # -s so the token never appears on screen or in scrollback.
    read -rs value
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- --quiet
    unset value
    echo "  ${name}: stored"
  fi
}

say "Secrets"
ensure_secret cadentic-shared-secret "" "openssl rand -hex 32"
ensure_secret claude-code-oauth-token \
  "Paste the token from \`claude setup-token\` (input hidden, then press Enter):"

say "Granting the runtime service account read access to them"
SA="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')-compute@developer.gserviceaccount.com"
for s in cadentic-shared-secret claude-code-oauth-token; do
  gcloud secrets add-iam-policy-binding "$s" \
    --member="serviceAccount:${SA}" --role=roles/secretmanager.secretAccessor \
    --quiet >/dev/null
done

say "Building the image (linux/amd64 — Cloud Run does not run arm64)"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet >/dev/null
docker build --platform linux/amd64 -f backend/Dockerfile -t "$IMAGE" .
docker push "$IMAGE"

say "Deploying"
# --timeout 600 sits above the backend's own 300s budget, so a slow generation ends as our
#   named `timeout` error rather than Cloud Run severing the connection first.
# --max-instances 2 keeps the in-flight request-id join meaningful (it is per instance) and
#   caps what a runaway could ever spend.
# --min-instances 0 is the whole point: idle costs nothing.
gcloud run deploy "$SERVICE" \
  --image "$IMAGE" \
  --region "$REGION" \
  --platform managed \
  --allow-unauthenticated \
  --memory 1Gi \
  --cpu 1 \
  --timeout 600 \
  --concurrency 4 \
  --min-instances 0 \
  --max-instances 2 \
  --set-env-vars "AUTH_MODE=A,MODE_A_PERSONAL_USE=true,REQUEST_TIMEOUT_MS=300000" \
  --set-secrets "CADENTIC_SHARED_SECRET=cadentic-shared-secret:latest,CLAUDE_CODE_OAUTH_TOKEN=claude-code-oauth-token:latest" \
  --quiet

URL="$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')"

say "Deployed"
echo "  $URL"
echo
echo "  Health:  curl -s $URL/healthz"
echo
echo "  Build the app against it — note this is the shared secret, printed once so you can"
echo "  paste it into the Gradle flag. Treat it as a credential:"
echo
echo "    SECRET=\$(gcloud secrets versions access latest --secret=cadentic-shared-secret)"
echo "    cd android && ./gradlew installRelease \\"
echo "      -Pcadentic.engineBaseUrl=$URL \\"
echo "      -Pcadentic.engineSharedSecret=\$SECRET"
echo
echo "  --allow-unauthenticated means the URL is reachable by anyone who knows it. The shared"
echo "  secret is the only thing standing between a stranger and your Claude subscription, and"
echo "  it is readable inside the APK — so do not hand that APK around."
