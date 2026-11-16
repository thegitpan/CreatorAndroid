#!/usr/bin/env bash
set -euo pipefail
android_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    elif command -v /usr/libexec/java_home >/dev/null; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidate in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do
        if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then export ANDROID_HOME="$candidate"; break; fi
    done
fi
cd "$android_root"
exec ./gradlew "$@"
