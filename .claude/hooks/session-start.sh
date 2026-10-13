#!/bin/bash
# Pins the JDK for Claude Code cloud sessions: the container's default java is 21, and the build needs 25
# (pom.xml's enforcer rule). Without this, ./mvnw stops at the Java version check before any test runs.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

for candidate in /usr/lib/jvm/java-25-openjdk-* /usr/lib/jvm/java-1.25.0-openjdk-*; do
  if [ -x "$candidate/bin/java" ]; then
    echo "export JAVA_HOME=\"$candidate\"" >> "$CLAUDE_ENV_FILE"
    echo "export PATH=\"$candidate/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
    exit 0
  fi
done

echo "No JDK 25 found under /usr/lib/jvm; ./mvnw verify will fail at the Java version check." >&2
exit 1
