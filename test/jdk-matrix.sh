#!/usr/bin/env bash
# Runs the agent end-to-end tests against every installed JDK and regenerates
# the JDK matrix in README.md.
# Override the candidates with a colon-separated JDK_MATRIX_HOMES, e.g.
#   JDK_MATRIX_HOMES="$JAVA8:$JAVA11:$JBR17:$JAVA21:$JAVA25" ./test/jdk-matrix.sh
set -uo pipefail
cd "$(dirname "$0")"
RESULTS="/tmp/openidea-hotreload-matrix.tsv"
: >"$RESULTS"

homes=()
if [ -n "${JDK_MATRIX_HOMES:-}" ]; then
  IFS=':' read -r -a homes <<<"$JDK_MATRIX_HOMES"
else
  while IFS= read -r home; do
    [ -n "$home" ] && homes+=("$home")
  done < <(/usr/libexec/java_home -V 2>&1 | awk '{print $NF}' | sort -u)
  extra="/Users/a1/Library/Application Support/ai.l0ng.openidea/language-server-runtimes/temurin-25.0.5/Contents/Home"
  [ -x "$extra/bin/java" ] && homes+=("$extra")
  if [ -n "${JBR_MATRIX_DIR:-}" ]; then
    for home in "$JBR_MATRIX_DIR"/*/Contents/Home; do
      [ -x "$home/bin/java" ] && homes+=("$home")
    done
  fi
fi

if [ "${#homes[@]}" -eq 0 ]; then
  echo "SKIP: no JDKs found"
  exit 0
fi

append_result() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >>"$RESULTS"
}

jdk_label() {
  local java="$1" version major
  version="$("$java" -version 2>&1)"
  major="$(printf '%s\n' "$version" | sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p' | head -1)"
  if printf '%s\n' "$version" | grep -q '1\.8\.'; then
    major=8
  fi
  if printf '%s\n' "$version" | grep -q 'JBR'; then
    echo "JBR ${major:-?}"
  else
    echo "JDK ${major:-?}"
  fi
}

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
  label="$(jdk_label "$java")"
  echo
  echo "============================================================"
  echo "$label"
  echo "HOME: $home"
  echo "============================================================"

  e2e_out="$(JAVA="$java" JAVAC="$home/bin/javac" JAVA_HOME="$home" ./e2e.sh 2>&1)"
  e2e_rc=$?
  printf '%s\n' "$e2e_out"
  while IFS=$'\t' read -r case status reason; do
    [ "$case" = "structure" ] && continue
    append_result "$label" "$case" "$status" "$reason"
  done < <(printf '%s\n' "$e2e_out" | awk -F'|' '/^RESULT\|/ {print $2 "\t" $3 "\t" $4}')
  if ! printf '%s\n' "$e2e_out" | grep -q '^RESULT|xml|'; then
    append_result "$label" xml FAIL "e2e failed"
  fi
  if ! printf '%s\n' "$e2e_out" | grep -q '^RESULT|body|'; then
    append_result "$label" body FAIL "e2e failed"
  fi
  if [ "$e2e_rc" -ne 0 ]; then
    fail=$((fail + 1))
  else
    pass=$((pass + 1))
  fi

  structure_out="$(JAVA="$java" JAVAC="$home/bin/javac" JAVA_HOME="$home" ./structure.sh 2>&1)"
  structure_rc=$?
  printf '%s\n' "$structure_out"
  while IFS=$'\t' read -r case status reason; do
    [ "$case" = "structure" ] && continue
    append_result "$label" "$case" "$status" "$reason"
  done < <(printf '%s\n' "$structure_out" | awk -F'|' '/^RESULT\|/ {print $2 "\t" $3 "\t" $4}')
  if [ "$structure_rc" -ne 0 ]; then
    fail=$((fail + 1))
  elif printf '%s\n' "$structure_out" | grep -q '^SKIP:'; then
    skip=$((skip + 1))
  else
    pass=$((pass + 1))
  fi
done

python3 ./render-matrix.py "$RESULTS" ../README.md
render_rc=$?

echo
echo "============================================================"
echo "JDK matrix summary: pass=$pass fail=$fail skip=$skip"
echo "README matrix: $([ "$render_rc" -eq 0 ] && echo updated || echo failed)"
echo "============================================================"
[ "$fail" -eq 0 ] && [ "$render_rc" -eq 0 ]
