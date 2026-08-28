# The Mesocycle Engine, containerised for Cloud Run.
#
# **Build from the repo root**, not from `backend/`:
#
#     docker build -f backend/Dockerfile -t cadentic-engine .
#
# The contract lives in `contracts/` and is shared with the Android app, so the context has
# to span both. `.dockerignore` at the root keeps that from dragging in the Android build.
#
# Debian slim, not Alpine. The Agent SDK ships the Claude Code CLI as a prebuilt binary and
# extracts it at runtime; it is linked against glibc, and musl would fail at exec time rather
# than at build time — the worst place to find out.

FROM node:22-slim

# ca-certificates for TLS to Claude. Nothing else: `allowedTools` is empty, so the agent has
# no shell to run and no repository to read — adding git or ripgrep would grow the image to
# serve tools this backend deliberately denies.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Dependencies first, so a source-only change reuses this layer.
COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev \
 # The SDK ships the CLI as per-platform optional packages and npm installs both the glibc
 # and the musl build — roughly 230 MB of the image is a binary this glibc base can never
 # execute. Dropping it is the difference between a ~1.6 GB and a ~1.3 GB pull on cold start.
 && rm -rf node_modules/@anthropic-ai/claude-agent-sdk-*-musl

COPY backend/tsconfig.json ./
COPY backend/src ./src
COPY contracts ./contracts

# tsc is a dev dependency, which `npm ci --omit=dev` skipped — fetch it for the build only
# and drop it again, rather than shipping the compiler in the runtime image.
RUN npm install --no-save typescript@$(node -p "require('./package.json').devDependencies.typescript") \
 && npx tsc -p tsconfig.json \
 && cp -r contracts dist/contracts \
 && npm remove --no-save typescript \
 && rm -rf src tsconfig.json

# The SDK extracts its bundled CLI at runtime and needs a writable HOME to do it. The `node`
# user's home is not writable in this image, so give it one that is.
ENV HOME=/tmp
ENV NODE_ENV=production

# Cloud Run routes to the container over its own proxy, so binding all interfaces here is
# the correct thing rather than the dangerous thing — the container has no other network
# exposure. Cloud Run injects PORT; 8787 is only the local-docker default.
ENV HOST=0.0.0.0
ENV PORT=8787
EXPOSE 8787

USER node

CMD ["node", "dist/index.js"]
