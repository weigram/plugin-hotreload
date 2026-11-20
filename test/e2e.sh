#!/usr/bin/env bash
# End-to-end test for the agent without any IDE:
#   1. plain MyBatis (not Spring) app prints a value every 500 ms
#   2. change the mapper XML in target/classes -> the printed value changes
#   3. change a Java method body and recompile          -> the printed banner changes
#   4. the process was started once (no restart)
#
# Needs `mvn` (or MVN=/path/to/mvn) and a JDK 8+. The app and the compiler use
# the same JAVA/JAVAC, so the class file versions match.
set -euo pipefail
cd "$(dirname "$0")"
FIXTURE="$PWD/fixture"
AGENT="$(cd .. && pwd)/agent/hotreload-agent.jar"

JAVA="${JAVA:-java}"
if [ "$JAVA" != "java" ]; then
  export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"
fi
JAVAC="${JAVAC:-javac}"
MVN="${MVN:-mvn}"
LOG="$FIXTURE/target/agent-e2e.log"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
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
    if grep -q "$pattern" "$LOG" 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

echo "== build fixture =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt )
CLASSES="$FIXTURE/target/classes"
CP="$CLASSES:$(cat "$FIXTURE/target/cp.txt")"
# The fixture source is the single source of truth; make sure a previous run
# did not leave a newer copy in target/classes.
cp "$FIXTURE/src/main/resources/mapper/ValueMapper.xml" "$CLASSES/mapper/ValueMapper.xml"

echo "== start app with agent =="
rm -f "$LOG"
"$JAVA" -javaagent:"$AGENT" -cp "$CP" demo.App >"$LOG" 2>&1 &
PID=$!

wait_for "VALUE XML1" 30 || fail "initial value never appeared"
wait_for "watching 1 class directories" 10 || fail "agent did not find the output directory"

echo "== reload mapper XML =="
python3 - "$CLASSES/mapper/ValueMapper.xml" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_text(path.read_text().replace("XML1", "XML2"))
PY
wait_for "VALUE XML2" 10 || fail "mapper XML was not reloaded"
grep -q "reloaded mapper XML" "$LOG" || fail "no XML reload log line"

echo "== redefine a method body =="
mkdir -p "$FIXTURE/target/e2e-src"
python3 - "$FIXTURE/src/main/java/demo/App.java" "$FIXTURE/target/e2e-src/App.java" <<'PY'
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text()
Path(sys.argv[2]).write_text(source.replace('return "VALUE";', 'return "VALUE2";'))
PY
"$JAVAC" -source 8 -target 8 -cp "$CP" -d "$CLASSES" "$FIXTURE/target/e2e-src/App.java" 2>/dev/null
wait_for "VALUE2 XML2" 10 || fail "class redefinition was not applied"
grep -Eq "reloaded demo.App|Reloading classes \\[demo.App\\]" "$LOG" || fail "no class reload log line"

STARTS="$(grep -c '### STARTED' "$LOG" || true)"
[ "$STARTS" = "1" ] || fail "the process restarted ($STARTS starts)"

echo
echo "PASS: XML refresh + class redefinition, one process"
grep -E "\[hotreload\]|VALUE2 XML2" "$LOG" | tail -8
