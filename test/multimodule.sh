#!/usr/bin/env bash
# Multi-module dependency test:
#   A depends on B; the application calls B from A.
#   Recompile only B -> A's next call must see B's new method body.
#   Then recompile A -> A's own method body must also update.
#
# This also checks that the agent discovers both module output directories
# from the application classpath, not just the working directory.
set -euo pipefail
cd "$(dirname "$0")"
FIXTURE="$PWD/fixture-multimodule"
AGENT="$(cd .. && pwd)/agent/hotreload-agent.jar"
LOG="$FIXTURE/target/multimodule.log"
A_SRC="$FIXTURE/a/src/main/java/demo/a/App.java"
B_SRC="$FIXTURE/b/src/main/java/demo/b/Provider.java"
A_BACKUP="$FIXTURE/target/App.java.orig"
B_BACKUP="$FIXTURE/target/Provider.java.orig"
PID=""

JAVA="${JAVA:-java}"
if [ "$JAVA" != "java" ]; then
  export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"
fi
JAVAC="${JAVAC:-javac}"
MVN="${MVN:-mvn}"

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  [ -f "$A_BACKUP" ] && cp "$A_BACKUP" "$A_SRC"
  [ -f "$B_BACKUP" ] && cp "$B_BACKUP" "$B_SRC"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  echo "--- agent log ---" >&2
  tail -40 "$LOG" >&2 || true
  exit 1
}

wait_for() {
  local pattern="$1" seconds="$2"
  for _ in $(seq 1 $((seconds * 2))); do
    if grep -Fq "$pattern" "$LOG" 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

echo "== build A + B =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile )
A_CLASSES="$FIXTURE/a/target/classes"
B_CLASSES="$FIXTURE/b/target/classes"
CP="$A_CLASSES:$B_CLASSES"
mkdir -p "$FIXTURE/target"
cp "$A_SRC" "$A_BACKUP"
cp "$B_SRC" "$B_BACKUP"

echo "== start app with agent =="
rm -f "$LOG"
"$JAVA" -javaagent:"$AGENT" -cp "$CP" demo.a.App >"$LOG" 2>&1 &
PID=$!

wait_for "watching 2 class directories" 15 || fail "agent did not watch both module output directories"
wait_for "MULTI A=A1 B=B1" 30 || fail "initial A/B state never appeared"
echo "RESULT|multimodule-initial|PASS|"

echo "== recompile B only =="
python3 - "$B_SRC" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
if '"B1"' not in text:
    raise SystemExit("B1 literal not found")
path.write_text(text.replace('"B1"', '"B2"'))
PY
( cd "$FIXTURE" && "$MVN" -B -q -pl b compile )
wait_for "MULTI A=A1 B=B2" 15 || fail "A did not observe B's redefined method body"
grep -Eq "reloaded demo\.b\.Provider|Reloading classes \[demo\.b\.Provider\]" "$LOG" \
  || fail "no Provider reload log line"
echo "RESULT|multimodule-b-change|PASS|"

echo "== recompile A =="
python3 - "$A_SRC" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
if '"A1"' not in text:
    raise SystemExit("A1 literal not found")
path.write_text(text.replace('"A1"', '"A2"'))
PY
( cd "$FIXTURE" && "$MVN" -B -q -pl a -am compile )
wait_for "MULTI A=A2 B=B2" 15 || fail "A's own class was not redefined"
grep -Eq "reloaded demo\.a\.App|Reloading classes \[demo\.a\.App\]" "$LOG" \
  || fail "no App reload log line"
echo "RESULT|multimodule-a-change|PASS|"

STARTS="$(grep -c '### STARTED' "$LOG" || true)"
[ "$STARTS" = "1" ] || fail "the process restarted ($STARTS starts)"

if [ -e "$FIXTURE/.hotreload-unsupported" ]; then
  fail "agent wrote .hotreload-unsupported"
fi

echo
echo "PASS: B changed without rebuilding A; A then changed too; one process"
grep -E "watching|MULTI|\[hotreload\].*reloaded demo\.(a|b)" "$LOG" | tail -12
