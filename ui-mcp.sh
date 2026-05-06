#!/usr/bin/env bash
# UI MCP launcher for MiniCPBP.
# Starts the HTTP server (solver JVM) and shadow-cljs watch, then connects
# clojure-mcp on stdio.
set -euo pipefail
cd "$(dirname "$0")"

NREPL_PORT=7890
JAR="target/minicpbp-1.0.jar"
LOG="$(mktemp -t minicpbp-ui-mcp.XXXXXX.log)"
HTTP_LOG="$(mktemp -t minicpbp-http.XXXXXX.log)"

SHADOW_PID=""
HTTP_PID=""

cleanup() {
  [ -n "$SHADOW_PID" ] && { kill "$SHADOW_PID" 2>/dev/null; wait "$SHADOW_PID" 2>/dev/null; } || true
  [ -n "$HTTP_PID" ]   && { kill -TERM -"$HTTP_PID" 2>/dev/null; wait "$HTTP_PID" 2>/dev/null; } || true
}
trap cleanup EXIT INT TERM

# Start HTTP server via solver JVM if port 3000 is not already up.
if ! nc -z localhost 3000 2>/dev/null; then
  if [ ! -f "$JAR" ]; then
    echo "minicpbp-ui-mcp: $JAR not found — run 'mvn package' for the HTTP server." >&2
  else
    setsid java -cp "$JAR" launch.Repl --port 0 >"$HTTP_LOG" 2>&1 </dev/null &
    HTTP_PID=$!
    echo "minicpbp-ui-mcp: starting HTTP server…" >&2
    for _ in $(seq 1 60); do
      nc -z localhost 3000 2>/dev/null && break
      sleep 0.5
    done
    if nc -z localhost 3000 2>/dev/null; then
      echo "minicpbp-ui-mcp: HTTP server ready → http://localhost:3000" >&2
    else
      echo "minicpbp-ui-mcp: HTTP server did not start in 30s; see $HTTP_LOG" >&2
    fi
  fi
else
  echo "minicpbp-ui-mcp: HTTP server already running on :3000" >&2
fi

# Start shadow-cljs if not already running.
if ! nc -z localhost "$NREPL_PORT" 2>/dev/null; then
  npx shadow-cljs watch app >"$LOG" 2>&1 &
  SHADOW_PID=$!

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
