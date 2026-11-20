#!/usr/bin/env bash
# Per-operation hot-reload capability probe. Each case starts a fresh JVM so a
# failed structural redefinition cannot poison the next one.
#
#   JAVA=/path/to/jdk/bin/java test/capability.sh
#
# Output: RESULT|<case>|PASS| or RESULT|<case>|FAIL|<reason>
set -uo pipefail
cd "$(dirname "$0")"
FIXTURE="$PWD/fixture-structure"
AGENT="$(cd .. && pwd)/agent/hotreload-agent.jar"
CLASSES="$FIXTURE/target/classes"
LOG="$FIXTURE/target/capability.log"
SRC="$FIXTURE/target/capability-src/demo"
JAVA="${JAVA:-java}"
if [ -z "${JAVAC:-}" ]; then
  case "$JAVA" in
    */java) JAVAC="${JAVA%/java}/javac" ;;
    *) JAVAC=javac ;;
  esac
fi
MVN="${MVN:-mvn}"
PID=""
LOG_MARK=0
PASS=0
FAIL=0

VERSION="$("$JAVA" -version 2>&1)"
if printf '%s\n' "$VERSION" | grep -q 'Dynamic Code Evolution'; then
  MODE=DCEVM
  FLAGS=(-XX:+AllowEnhancedClassRedefinition)
elif printf '%s\n' "$VERSION" | grep -q 'JBR'; then
  MODE=JBR
  FLAGS=(-XX:+AllowEnhancedClassRedefinition)
elif "$JAVA" -XX:+AllowEnhancedClassRedefinition -version >/dev/null 2>&1; then
  MODE=JBR
  FLAGS=(-XX:+AllowEnhancedClassRedefinition)
else
  MODE=STANDARD
  FLAGS=()
fi
echo "MODE=$MODE JAVA=$JAVA"

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

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

compile_variant() {
  mkdir -p "$SRC"
  cp "$FIXTURE/variants/$1.java" "$SRC/Target.java"
  "$JAVAC" -source 8 -target 8 -cp "$CLASSES" -d "$CLASSES" "$SRC/Target.java" 2>/dev/null
}

stop_app() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  PID=""
}

last_state() {
  grep '^STATE' "$LOG" 2>/dev/null | tail -1 || true
}

run_case() {
  local name="$1" initial="$2" target="$3" expected="$4" initial_expected="$5"
  stop_app
  compile_variant "$initial"
  rm -f "$LOG"
  LOG_MARK=0
  if [ "${#FLAGS[@]}" -eq 0 ]; then
    "$JAVA" -javaagent:"$AGENT" -cp "$CLASSES" demo.App >"$LOG" 2>&1 &
  else
    "$JAVA" "${FLAGS[@]}" -javaagent:"$AGENT" -cp "$CLASSES" demo.App >"$LOG" 2>&1 &
  fi
  PID=$!
  if ! wait_for "$initial_expected" 15; then
    echo "RESULT|$name|FAIL|initial state did not load"
    FAIL=$((FAIL + 1))
    stop_app
    return
  fi
  LOG_MARK="$(wc -l < "$LOG")"
  sleep 1
  compile_variant "$target"
  if wait_for "$expected" 6; then
    echo "RESULT|$name|PASS|"
    PASS=$((PASS + 1))
  else
    echo "RESULT|$name|FAIL|$(last_state)"
    FAIL=$((FAIL + 1))
  fi
  stop_app
}

BASELINE='STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=false'
BODY='STATE value=V2 extra=NO field=NO marker=NO marker2=NO deprecated=false'
METHOD='STATE value=A extra=YES field=NO marker=NO marker2=NO deprecated=false'
FIELD='STATE value=A extra=NO field=YES marker=NO marker2=NO deprecated=false'
ANNOTATION='STATE value=A extra=NO field=NO marker=NO marker2=NO deprecated=true'
INTERFACE='STATE value=A extra=NO field=NO marker=YES marker2=NO deprecated=false'
INTERFACE2='STATE value=A extra=NO field=NO marker=NO marker2=YES deprecated=false'
PARENT='STATE value=B extra=NO field=NO marker=NO marker2=NO deprecated=false'
SIGNATURE='STATE value=ERR:NoSuchMethodException extra=NO field=NO marker=NO marker2=NO deprecated=false'

echo "== build fixture =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile )

run_case body reverted body "$BODY" "$BASELINE"
run_case add_method reverted methodOnly "$METHOD" "$BASELINE"
run_case remove_method methodOnly reverted "$BASELINE" "$METHOD"
run_case add_field reverted fieldOnly "$FIELD" "$BASELINE"
run_case remove_field fieldOnly reverted "$BASELINE" "$FIELD"
run_case add_annotation reverted annotation "$ANNOTATION" "$BASELINE"
run_case remove_annotation annotation reverted "$BASELINE" "$ANNOTATION"
run_case add_interface reverted interface "$INTERFACE" "$BASELINE"
run_case remove_interface interface reverted "$BASELINE" "$INTERFACE"
run_case replace_interface interface interface2 "$INTERFACE2" "$INTERFACE"
run_case replace_superclass reverted parent "$PARENT" "$BASELINE"
run_case change_signature reverted signature "$SIGNATURE" "$BASELINE"

echo "SUMMARY mode=$MODE pass=$PASS fail=$FAIL"
