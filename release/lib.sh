#!/usr/bin/env bash
# Shared helpers for the release scripts (release/README.md). Sourced, never run directly.
set -euo pipefail

RELEASE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_LOGS="$RELEASE_ROOT/release/.logs"   # outside build/: `gradle clean` must not delete them
mkdir -p "$RELEASE_LOGS"

rlog() { printf '\033[1;34m[release]\033[0m %s\n' "$*" >&2; }
rdie() { printf '\033[1;31m[release] error:\033[0m %s\n' "$*" >&2; exit 1; }

plugin_version() { awk -F '=' '/^pluginVersion/ { gsub(/[[:space:]]/, "", $2); print $2 }' "$RELEASE_ROOT/gradle.properties"; }
plugin_zip() { echo "$RELEASE_ROOT/intellij/build/distributions/adb-toolbox-$(plugin_version).zip"; }

# A JDK 21 for Gradle: JAVA_HOME when it is 21, else the first JDK 21 found in the usual places.
ensure_java21() {
    local candidate
    for candidate in "${JAVA_HOME:-}" "$HOME"/.local/share/mise/installs/java/temurin-21* /usr/lib/jvm/*21* /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
        [[ -n "$candidate" && -x "$candidate/bin/java" ]] || continue
        if "$candidate/bin/java" -version 2>&1 | grep -q 'version "21'; then
            export JAVA_HOME="$candidate" PATH="$candidate/bin:$PATH"
            return
        fi
    done
    rdie "JDK 21 not found; set JAVA_HOME to a JDK 21"
}

# Loads the E2E environment (X11 libraries and fonts the IDE tests need, SDK locations).
load_e2e_env() {
    [[ -f /work/.e2e-env.sh ]] && source /work/.e2e-env.sh
    source "$RELEASE_ROOT/e2e/scripts/lib.sh"
    export LC_ALL=C.UTF-8
}

# Runs Gradle with output in $RELEASE_LOGS/<name>.log and fails unless the log says
# BUILD SUCCESSFUL (a piped exit code alone is not trusted).
gradle() {
    local name="$1"; shift
    local logfile="$RELEASE_LOGS/$name.log"
    rlog "gradle $* (log: $logfile)"
    (cd "$RELEASE_ROOT" && ./gradlew "$@" --console=plain) >"$logfile" 2>&1 || true
    grep -q "BUILD SUCCESSFUL" "$logfile" || { tail -40 "$logfile" >&2; rdie "gradle $name failed, see $logfile"; }
}

# Same as `gradle`, but adb/scrcpy are hidden: unit tests must not see a running emulator
# (MirroringToggleActionTest selects it and its "no device" expectations fail).
gradle_without_device() {
    local name="$1"; shift
    (
        PATH="$(echo "$PATH" | tr ':' '\n' | grep -v -e platform-tools -e android-sdk -e scrcpy | paste -sd:)"
        unset ANDROID_HOME ANDROID_SDK_ROOT
        export PATH
        gradle "$name" "$@"
    )
}

confirm() {
    [[ "${RELEASE_YES:-0}" == 1 ]] && return 0
    local answer
    read -r -p "$1 [y/N] " answer
    [[ "$answer" == y || "$answer" == Y ]]
}
