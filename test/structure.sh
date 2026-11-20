#!/usr/bin/env bash
# Structural-change matrix for the agent:
#   method body -> add method/field/annotation -> add interface -> change superclass -> revert.
# Every mutation is compiled into target/classes while the same app process runs.
#
# Requires JetBrains Runtime (DCEVM enhanced redefinition):
#   JAVA=/path/to/jbr/bin/java test/structure.sh
set -euo pipefail
cd "$(dirname "$0")"
FIXTURE="$PWD/fixture-structure"
AGENT="$(cd .. && pwd)/agent/hotreload-agent.jar"
CLASSES="$FIXTURE/target/classes"
LOG="$FIXTURE/target/structure-e2e.log"
SRC="$FIXTURE/target/structure-src/demo"

JAVA="${JAVA:-java}"
if [ "$JAVA" = "java" ]; then
  JAVAC="${JAVAC:-javac}"
else
  JAVAC="${JAVAC:-${JAVA%/java}/javac}"
fi
MVN="${MVN:-mvn}"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if ! "$JAVA" -XX:+AllowEnhancedClassRedefinition -version >/dev/null 2>&1; then
  echo "SKIP: $JAVA does not accept -XX:+AllowEnhancedClassRedefinition (use JBR)"
  exit 0
fi

fail() {
  echo "FAIL: $*" >&2
  echo "--- last states ---" >&2
  grep '^STATE' "$LOG" 2>/dev/null | tail -5 >&2 || true
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

apply_variant() {
  local name="$1"
  mkdir -p "$SRC"
  cp "$FIXTURE/variants/$name.java" "$SRC/Target.java"
  "$JAVAC" -source 8 -target 8 -cp "$CLASSES" -d "$CLASSES" "$SRC/Target.java" 2>/dev/null
}

failures=0
check() {
  local name="$1" pattern="$2"
  if wait_for "$pattern" 15; then
    echo "PASS: $name"
  else
    echo "FAIL: $name (wanted: $pattern)" >&2
    grep '^STATE' "$LOG" 2>/dev/null | tail -1 >&2 || true
    failures=$((failures + 1))
  fi
}

echo "== build fixture =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile )

echo "== start app (JBR enhanced redefinition) =="
rm -f "$LOG"
"$JAVA" -XX:+AllowEnhancedClassRedefinition -javaagent:"$AGENT" -cp "$CLASSES" demo.App >"$LOG" 2>&1 &
PID=$!

wait_for "### STARTED" 20 || fail "app did not start"
wait_for "STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=false" 20 || fail "initial state did not appear"

expect_unsupported() {
  local name="$1" pattern="$2"
  if wait_for "$pattern" 6; then
    echo "PASS: $name"
  elif grep -Fq "attempted to change superclass or interfaces" "$LOG"; then
    echo "EXPECTED-UNSUPPORTED: $name (JBR refuses hierarchy replacement)"
  else
    echo "FAIL: $name (neither applied nor reported as unsupported)" >&2
    failures=$((failures + 1))
  fi
}

echo "== matrix =="
apply_variant body
check "method body" "STATE value=V2 extra=NO field=NO marker=NO marker2=NO deprecated=false"

apply_variant extra
check "add method + field + annotation" "STATE value=A extra=YES field=YES marker=NO marker2=NO deprecated=true"

apply_variant interface
check "add interface" "STATE value=A extra=NO field=NO marker=YES marker2=NO deprecated=false"

apply_variant interface2
expect_unsupported "change interface" "STATE value=A extra=NO field=NO marker=NO marker2=YES deprecated=false"

apply_variant reverted
check "remove interface" "STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=false"

apply_variant parent
expect_unsupported "change superclass" "STATE value=B extra=NO field=NO marker=NO marker2=NO deprecated=false"

apply_variant reverted
check "revert all structural changes" "STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=false"

STARTS="$(grep -c '### STARTED' "$LOG" || true)"
[ "$STARTS" = "1" ] || fail "the process restarted ($STARTS starts)"

if [ "$failures" -ne 0 ]; then
  echo
  echo "RESULT: $failures case(s) failed (process was not restarted)"
  echo "--- agent log ---"
  tail -60 "$LOG"
  exit 1
fi

echo
echo "PASS: structural matrix, one process, no restart"
grep -E '^STATE|RELOAD|reloaded mapper|### STARTED' "$LOG" | tail -20
