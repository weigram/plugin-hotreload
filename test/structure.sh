#!/usr/bin/env bash
# Structural-change matrix for the agent. Each group runs in a fresh JVM so a
# failed redefinition cannot poison the next case:
#   group 1: method body -> add method/field/annotation -> add/remove interface
#   group 2: replace interface (expected unsupported on JBR 17)
#   group 3: replace superclass (expected unsupported on JBR 17)
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
BASELINE="STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=false"

JAVA="${JAVA:-java}"
if [ "$JAVA" != "java" ]; then
  export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"
fi
JAVAC="${JAVAC:-${JAVA%/java}/javac}"
MVN="${MVN:-mvn}"
PID=""
LOG_MARK=0

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
  tail -50 "$LOG" >&2 || true
  exit 1
}

wait_for() {
  local pattern="$1" seconds="$2"
  for _ in $(seq 1 $((seconds * 2))); do
    if tail -n +$((LOG_MARK + 1)) "$LOG" 2>/dev/null | grep -Fq "$pattern"; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

apply_variant() {
  local name="$1"
  LOG_MARK="$(wc -l < "$LOG" 2>/dev/null || echo 0)"
  mkdir -p "$SRC"
  cp "$FIXTURE/variants/$name.java" "$SRC/Target.java"
  "$JAVAC" -source 8 -target 8 -cp "$CLASSES" -d "$CLASSES" "$SRC/Target.java" 2>/dev/null
}

compile_variant() {
  local name="$1"
  mkdir -p "$SRC"
  cp "$FIXTURE/variants/$name.java" "$SRC/Target.java"
  "$JAVAC" -source 8 -target 8 -cp "$CLASSES" -d "$CLASSES" "$SRC/Target.java" 2>/dev/null
}

reset_classes() {
  compile_variant reverted
}

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

expect_unsupported() {
  local name="$1" pattern="$2"
  if wait_for "$pattern" 6; then
    echo "PASS: $name"
  elif tail -n +$((LOG_MARK + 1)) "$LOG" 2>/dev/null | grep -Fq "attempted to change superclass or interfaces"; then
    echo "EXPECTED-UNSUPPORTED: $name (JBR refuses hierarchy replacement)"
  else
    echo "FAIL: $name (neither applied nor reported as unsupported)" >&2
    failures=$((failures + 1))
  fi
}

start_app() {
  reset_classes
  rm -f "$LOG"
  LOG_MARK=0
  "$JAVA" -XX:+AllowEnhancedClassRedefinition -javaagent:"$AGENT" -cp "$CLASSES" demo.App >"$LOG" 2>&1 &
  PID=$!
  wait_for "### STARTED" 20 || fail "app did not start"
  wait_for "$BASELINE" 20 || fail "initial state did not appear"
  LOG_MARK="$(wc -l < "$LOG")"
}

stop_app() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  PID=""
}

failures=0
echo "== build fixture =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile )

echo "== group 1: supported changes =="
start_app
apply_variant body
check "method body" "STATE value=V2 extra=NO field=NO marker=NO marker2=NO deprecated=false"
apply_variant extra
check "add method + field + annotation" "STATE value=A extra=YES field=YES marker=NO marker2=NO deprecated=true"
apply_variant interface
check "add interface, remove method/field/annotation" "STATE value=A extra=NO field=NO marker=YES marker2=NO deprecated=false"
apply_variant reverted
expect_unsupported "remove interface" "$BASELINE"
stop_app

echo "== group 2: replace interface =="
start_app
apply_variant interface
check "setup interface" "STATE value=A extra=NO field=NO marker=YES marker2=NO deprecated=false"
apply_variant interface2
expect_unsupported "replace interface" "STATE value=A extra=NO field=NO marker=NO marker2=YES deprecated=false"
stop_app

echo "== group 3: replace superclass =="
start_app
apply_variant parent
expect_unsupported "replace superclass" "STATE value=B extra=NO field=NO marker=NO marker2=NO deprecated=false"
stop_app

if [ "$failures" -ne 0 ]; then
  echo
  echo "RESULT: $failures case(s) failed"
  exit 1
fi
echo
echo "PASS: structural matrix (each group used one JVM; no restart inside a group)"
