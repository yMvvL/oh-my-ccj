#!/usr/bin/env bash
# Runs the real CLI against a local tool-routing fake model: no API key, no network, no cost.
# The fake model turns "read <path>", "run <command>", "list [glob]" and "search <regex>" into real
# tool calls, so approval prompts, tool cards, sessions, the REPL and the web UI can all be
# exercised.
#
#   scripts/playground.sh                              # REPL, working directory = this repo
#   scripts/playground.sh --web --open                 # browser UI instead
#   scripts/playground.sh -p "read README.md"          # one-shot
#   scripts/playground.sh -p "run git status --short"  # side effect (needs --yolo when piped)
set -euo pipefail

self="$(readlink -f "${BASH_SOURCE[0]}")"
root="$(cd "$(dirname "$self")/.." && pwd)"
port="${CCJ_PLAYGROUND_PORT:-8777}"
home="$root/target/playground-home"

cd "$root"
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -DincludeScope=test
classpath="target/classes:target/test-classes:$(cat target/test-classpath.txt)"

# Both children are tracked and both are killed on the way out. Owning only the fake model would
# leave the CLI holding its port whenever this script is stopped by something other than Ctrl+C.
model=""
ccj=""
cleanup() {
  if [[ -n "$model" ]]; then kill "$model" 2>/dev/null || true; fi
  if [[ -n "$ccj" ]]; then kill "$ccj" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

java -cp "$classpath" com.ccj.agent.e2e.MockModelServer "$port" &
model=$!

for _ in $(seq 1 100); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    exec 3>&-
    break
  fi
  sleep 0.1
done

cat >&2 <<BANNER
playground model: http://127.0.0.1:$port/v1   (working directory: $root)
try: read README.md | run git status --short | list src/**/*.java | search TODO
BANNER

set +e
"$root/ccj" \
  --provider openai --base-url "http://127.0.0.1:$port/v1" \
  --api-key playground --model playground \
  --home "$home" "$@" &
ccj=$!

wait "$ccj"
status=$?
exit "$status"
