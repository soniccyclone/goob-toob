#!/usr/bin/env bash
# Dev entrypoint: repo is bind-mounted over /app, so reinstall node deps
# (fast: ~/.npm cache is baked into the image), watch CLJS, run the server.
set -euo pipefail
cd /app
npm install --prefer-offline --no-audit --no-fund
npx shadow-cljs watch client &
SHADOW_PID=$!
trap 'kill $SHADOW_PID 2>/dev/null || true' EXIT
exec clojure -M:dev -m goob-toob.core
