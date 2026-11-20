#!/usr/bin/env bash
# Regression coverage for issue #1: deferred-error rollback and per-Configuration targeting.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
ASM_JAR="agent/lib/asm-9.8.jar"
AGENT_CLASSES="build/reloader-regression-classes"
MYBATIS_JAR="${MYBATIS_JAR:-$HOME/.m2/repository/org/mybatis/mybatis/3.5.19/mybatis-3.5.19.jar}"

rm -rf "$AGENT_CLASSES"
mkdir -p "$AGENT_CLASSES"
find agent/src -name '*.java' | sort > build/reloader-regression-sources.txt
JAVAC_MAJOR="$("$JAVAC" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
if [ "${JAVAC_MAJOR:-0}" -ge 9 ]; then
  "$JAVAC" --release 8 -Xlint:all,-options -cp "$ASM_JAR" \
    -d "$AGENT_CLASSES" @build/reloader-regression-sources.txt
else
  "$JAVAC" -source 8 -target 8 -Xlint:-options -cp "$ASM_JAR" \
    -d "$AGENT_CLASSES" @build/reloader-regression-sources.txt
fi

if [ ! -f "$MYBATIS_JAR" ]; then
  mkdir -p test/fixture/target
  mvn -q -f test/fixture/pom.xml dependency:build-classpath \
    -Dmdep.outputFile="$PWD/test/fixture/target/reloader-cp.txt"
  MYBATIS_CP="$(cat test/fixture/target/reloader-cp.txt)"
else
  MYBATIS_CP="$MYBATIS_JAR"
fi

mkdir -p build/reloader-regression-test
"$JAVAC" -cp "$AGENT_CLASSES:$MYBATIS_CP" \
  -d build/reloader-regression-test test/ReloaderRegression.java
"$JAVA" -cp "build/reloader-regression-test:$AGENT_CLASSES:$MYBATIS_CP" ReloaderRegression
