#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for step_java in "$PWD"/../android/.toolchain/jdk/openjdk@17/*/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$step_java/bin/java" ]]; then export JAVA_HOME="$step_java"; break; fi
  done
fi
python3 configure.py
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/../android/.toolchain/gradle-home}"
if [[ $# -eq 0 ]]; then set -- :core:test :app:test :app:packageDistributionForCurrentOS; fi
if [[ -x ../android/.toolchain/gradle-8.13/bin/gradle ]]; then exec ../android/.toolchain/gradle-8.13/bin/gradle "$@"; fi
exec ../android/gradlew -p "$PWD" "$@"
