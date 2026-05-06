#!/usr/bin/env bash
# UI MCP launcher for MiniCPBP.
# Starts shadow-cljs watch, waits for its nREPL, then connects clojure-mcp on stdio.
set -euo pipefail
cd "$(dirname "$0")"

NREPL_PORT=7890
LOG="$(mktemp -t minicpbp-ui-mcp.XXXXXX.log)"

if ! nc -z localhost "$NREPL_PORT" 2>/dev/null; then
  npx shadow-cljs watch app >"$LOG" 2>&1 &
  SHADOW_PID=$!

  cleanup() {
    kill "$SHADOW_PID" 2>/dev/null || true
    wait "$SHADOW_PID" 2>/dev/null || true
  }
  trap cleanup EXIT INT TERM

  echo "minicpbp-ui-mcp: starting shadow-cljs, waiting for nREPL on :$NREPL_PORT…" >&2
  for _ in $(seq 1 120); do
    nc -z localhost "$NREPL_PORT" 2>/dev/null && break
    sleep 0.5
  done

  if ! nc -z localhost "$NREPL_PORT" 2>/dev/null; then
    echo "minicpbp-ui-mcp: nREPL did not come up within 60s; see $LOG" >&2
    exit 1
  fi
fi

echo "minicpbp-ui-mcp: shadow-cljs nREPL on :$NREPL_PORT" >&2
clojure -T:mcp start :port "$NREPL_PORT"
