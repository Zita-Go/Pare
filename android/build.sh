#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  for pare_java in "$PWD"/.toolchain/jdk/openjdk@17/*/libexec/openjdk.jdk/Contents/Home; do
    [[ -x "$pare_java/bin/java" ]] && export JAVA_HOME="$pare_java" && break
  done
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.toolchain/gradle-home}"
python3 configure-local.py
if [[ $# -eq 0 ]]; then set -- assembleDebug; fi
if [[ -x .toolchain/gradle-8.13/bin/gradle ]]; then exec .toolchain/gradle-8.13/bin/gradle "$@"; fi
exec ./gradlew "$@"
