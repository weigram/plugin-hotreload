#!/usr/bin/env bash
# Regression test for the 1.2.0 Mapper XML reloader bug:
#   an XML file that only defines a resultMap made the reloader delete every
#   statement in the namespace, including MyBatis-Plus' injected BaseMapper
#   methods. The next selectList call then failed with:
#     Mapped Statements collection does not contain value for ...selectList
#
# The fixture calls selectList (injected, no XML statement) and xmlValue
# (defined in XML) every 500 ms. The test:
#   1. verifies both work;
#   2. replaces the XML with a resultMap-only file and verifies selectList
#      still works while xmlValue is gone;
#   3. restores the XML with a new value and verifies both work again.
set -euo pipefail
cd "$(dirname "$0")"
FIXTURE="$PWD/fixture-mybatis-plus"
AGENT="${AGENT:-$(cd .. && pwd)/agent/hotreload-agent.jar}"
LOG="$FIXTURE/target/mybatis-plus-xml.log"
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
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  echo "--- agent log ---" >&2
  tail -60 "$LOG" >&2 || true
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

echo "== build MyBatis-Plus fixture =="
( cd "$FIXTURE" && "$MVN" -B -q -DskipTests clean compile dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt )
CLASSES="$FIXTURE/target/classes"
CP="$CLASSES:$(cat "$FIXTURE/target/cp.txt")"
XML="$CLASSES/mapper/DemoMapper.xml"
SOURCE_XML="$FIXTURE/src/main/resources/mapper/DemoMapper.xml"
cp "$SOURCE_XML" "$XML"

echo "== start app with agent =="
rm -f "$LOG"
"$JAVA" -javaagent:"$AGENT" -cp "$CP" demo.App >"$LOG" 2>&1 &
PID=$!

wait_for "STATE mp=1=alpha xml=XML1" 30 || fail "initial MyBatis-Plus state never appeared"
echo "RESULT|mybatis-plus-xml-select|PASS|"

echo "== replace XML with resultMap only =="
python3 - "$XML" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
text, count = re.subn(r'\s*<select\b[^>]*\bid="xmlValue"[^>]*>.*?</select>',
                      '', text, flags=re.S)
if count != 1:
    raise SystemExit("expected one xmlValue select, removed %d" % count)
path.write_text(text)
PY
wait_for "STATE mp=1=alpha xml=MISSING" 15 \
  || fail "injected selectList did not survive a resultMap-only XML reload"
if grep -Fq "Mapped Statements collection does not contain value" "$LOG"; then
  fail "reproduced the 1.2.0 error: Mapped Statements collection does not contain value"
fi
echo "RESULT|mybatis-plus-injected-selectList-survives-resultMap-only|PASS|"

echo "== restore XML with a new value =="
python3 - "$SOURCE_XML" "$XML" <<'PY'
import sys
from pathlib import Path

source, target = map(Path, sys.argv[1:3])
target.write_text(source.read_text().replace("XML1", "XML2"))
PY
wait_for "STATE mp=1=alpha xml=XML2" 15 || fail "XML statement did not reload to XML2"
if grep -Fq "Mapped Statements collection does not contain value" "$LOG"; then
  fail "injected selectList disappeared after the XML restore"
fi
grep -Fq "reloaded mapper XML DemoMapper.xml" "$LOG" \
  || fail "no XML reload log line"
echo "RESULT|mybatis-plus-xml-restore|PASS|"

STARTS="$(grep -c '### STARTED' "$LOG" || true)"
[ "$STARTS" = "1" ] || fail "the process restarted ($STARTS starts)"

echo
echo "PASS: MyBatis-Plus selectList survived XML replacement; XML statement reloaded"
grep -E "STATE|\[hotreload\]|### STARTED" "$LOG" | tail -12
