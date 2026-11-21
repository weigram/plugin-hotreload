#!/usr/bin/env bash
# Build the plugin from a clean checkout:
#   1. compile the Java agent (Java 8 bytecode, no MyBatis/Spring dependency)
#   2. package it reproducibly (sorted entries, fixed timestamps)
#   3. compile the Rust component to wasm32-wasip2
#   4. copy it to dist/plugin.wasm and validate the package
set -euo pipefail
cd "$(dirname "$0")"

ASM_JAR="agent/lib/asm-9.8.jar"
HOTSWAP_AGENT_JAR="${HOTSWAP_AGENT_JAR:-agent/lib/hotswap-agent-2.0.5.jar}"
AGENT_JAR="agent/hotreload-agent.jar"
PLUGIN_CLI="${OPENIDEA_PLUGIN:-openidea-plugin}"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)"

if [ -n "${JAVA_HOME_FOR_BUILD:-}" ]; then
  JAVAC="$JAVA_HOME_FOR_BUILD/bin/javac"
else
  JAVAC="${JAVAC:-javac}"
fi

if [ ! -f "$ASM_JAR" ]; then
  echo "missing $ASM_JAR (ASM 9.8, BSD-3-Clause; see agent/lib/README.md)" >&2
  exit 1
fi
if [ ! -f "$HOTSWAP_AGENT_JAR" ]; then
  echo "missing $HOTSWAP_AGENT_JAR (HotSwapAgent 2.0.5; see agent/lib/README.md)" >&2
  exit 1
fi

echo "== 1/4 Java agent ($VERSION) =="
echo "    HotSwapAgent: $HOTSWAP_AGENT_JAR"
rm -rf build/agent-classes
mkdir -p build/agent-classes
find agent/src -name '*.java' | sort > build/agent-sources.txt

# The agent must run on Java 8 so the same jar covers every project JDK.
# `--release` exists from JDK 9; a JDK 8 build host falls back to -source/-target.
JAVAC_MAJOR="$("$JAVAC" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
if [ "${JAVAC_MAJOR:-0}" -ge 9 ]; then
  "$JAVAC" --release 8 -Xlint:all,-options -cp "$ASM_JAR:$HOTSWAP_AGENT_JAR" -d build/agent-classes @build/agent-sources.txt
else
  "$JAVAC" -source 8 -target 8 -Xlint:-options -cp "$ASM_JAR:$HOTSWAP_AGENT_JAR" -d build/agent-classes @build/agent-sources.txt
fi
python3 agent/package.py build/agent-classes "$ASM_JAR" "$HOTSWAP_AGENT_JAR" "$AGENT_JAR" "$VERSION"

echo "== 2/4 Rust component =="
export PATH="$HOME/.cargo/bin:$PATH"
cargo build --release --target wasm32-wasip2

echo "== 3/4 package =="
mkdir -p dist
cp target/wasm32-wasip2/release/hotreload.wasm dist/plugin.wasm
ls -l "$AGENT_JAR" dist/plugin.wasm

echo "== 4/4 check =="
"$PLUGIN_CLI" check

cat <<EOF

Built $VERSION:
  agent: $AGENT_JAR
  wasm : dist/plugin.wasm

Load it in the IDE with \`openidea-plugin link\` and approve java.agent in
Settings › Plugins. The IDE injects the agent into Application, Spring Boot and
JAR Application run configurations automatically.
EOF
