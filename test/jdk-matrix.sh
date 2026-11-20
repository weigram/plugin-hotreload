#!/usr/bin/env bash
# Runs the agent end-to-end tests against every installed JDK.
# Override the candidates with a colon-separated JDK_MATRIX_HOMES, e.g.
#   JDK_MATRIX_HOMES="$JAVA8:$JAVA11:$JBR17:$JAVA21" ./test/jdk-matrix.sh
set -uo pipefail
cd "$(dirname "$0")"

homes=()
if [ -n "${JDK_MATRIX_HOMES:-}" ]; then
  IFS=':' read -r -a homes <<<"$JDK_MATRIX_HOMES"
else
  while IFS= read -r home; do
    [ -n "$home" ] && homes+=("$home")
  done < <(/usr/libexec/java_home -V 2>&1 | awk '{print $NF}' | sort -u)
fi

if [ "${#homes[@]}" -eq 0 ]; then
  echo "SKIP: no JDKs found"
  exit 0
fi

pass=0
fail=0
skip=0
for home in "${homes[@]}"; do
  java="$home/bin/java"
  if [ ! -x "$java" ]; then
    echo "SKIP: $home (no bin/java)"
    skip=$((skip + 1))
    continue
  fi
  version="$("$java" -version 2>&1 | head -1)"
  echo
  echo "============================================================"
  echo "JDK: $version"
  echo "HOME: $home"
  echo "============================================================"

  e2e_out="$(JAVA="$java" JAVAC="$home/bin/javac" JAVA_HOME="$home" ./e2e.sh 2>&1)"
  e2e_rc=$?
  printf '%s\n' "$e2e_out"
  if [ "$e2e_rc" -ne 0 ]; then
    fail=$((fail + 1))
  elif printf '%s\n' "$e2e_out" | grep -q '^SKIP:'; then
    skip=$((skip + 1))
  else
    pass=$((pass + 1))
  fi

  structure_out="$(JAVA="$java" JAVAC="$home/bin/javac" JAVA_HOME="$home" ./structure.sh 2>&1)"
  structure_rc=$?
  printf '%s\n' "$structure_out"
  if [ "$structure_rc" -ne 0 ]; then
    fail=$((fail + 1))
  elif printf '%s\n' "$structure_out" | grep -q '^SKIP:'; then
    skip=$((skip + 1))
  else
    pass=$((pass + 1))
  fi
done

echo
echo "============================================================"
echo "JDK matrix summary: pass=$pass fail=$fail skip=$skip"
echo "============================================================"
[ "$fail" -eq 0 ]
