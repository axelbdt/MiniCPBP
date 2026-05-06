#!/usr/bin/env bash
# Project-level MCP launcher for MiniCPBP.
# Backgrounds the solver+nREPL JVM, then runs clojure-mcp on stdio so Claude Code
# (or any MCP client) sees one MCP server while owning the whole process tree.
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/minicpbp-1.0.jar"
PORT_FILE=".nrepl-port"

if [ ! -f "$JAR" ]; then
  echo "minicpbp-mcp: $JAR not found. Run 'mvn package' in MiniCPBP/ first." >&2
  exit 1
fi

if ! command -v clojure >/dev/null 2>&1; then
  echo "minicpbp-mcp: 'clojure' CLI not on PATH. Enter the devenv shell." >&2
  exit 1
fi

LOG="$(mktemp -t minicpbp-mcp.XXXXXX.log)"
rm -f "$PORT_FILE"

# Start solver JVM in its own process group, stdio redirected away from this
# script's stdout (which carries MCP JSON-RPC and must stay clean).
setsid java -cp "$JAR" launch.Repl --port 0 >"$LOG" 2>&1 </dev/null &
JVM_PID=$!

cleanup() {
  kill -TERM -"$JVM_PID" 2>/dev/null || true
  wait "$JVM_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

PORT=""
for _ in $(seq 1 60); do
  if [ -s "$PORT_FILE" ]; then
    candidate="$(cat "$PORT_FILE")"
    if (echo > "/dev/tcp/127.0.0.1/$candidate") 2>/dev/null; then
      PORT="$candidate"
      break
    fi
  fi
  sleep 0.5
done

if [ -z "$PORT" ]; then
  echo "minicpbp-mcp: nREPL did not come up within 30s; see $LOG" >&2
  exit 1
fi

# Start the UI HTTP server on port 3000 via nREPL eval.
python3 - "$PORT" <<'PYEOF'
import socket, sys

port = int(sys.argv[1])
code = "(do (require '[prototype.server :as server]) (server/start!))"
cb = code.encode()
msg = b"d4:code" + str(len(cb)).encode() + b":" + cb + b"2:id1:12:op4:evale"

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(("127.0.0.1", port))
s.sendall(msg)
s.recv(4096)
s.close()
PYEOF

# Foreground (no exec) so the trap fires when clojure-mcp exits.
clojure -T:mcp start :port "$PORT"
