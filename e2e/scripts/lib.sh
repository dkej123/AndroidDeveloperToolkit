# Shared configuration for the E2E environment scripts. Source it; do not execute it.
#
# Every location can be overridden from the environment, so an existing Android SDK, Android
# Studio install or Xvfb can be reused instead of downloaded. See docs/e2e-testing.md.

set -euo pipefail

# Test and class names contain non-ASCII characters (e.g. "—"); a POSIX locale makes the JVM unable
# to read those class files ("Failed to create MD5 hash ... No such file").
export LC_ALL="${LC_ALL:-C.UTF-8}" LANG="${LANG:-C.UTF-8}"

E2E_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export E2E_REPO_ROOT

# Root for everything the scripts download or create (SDK, AVD, Studio, sandbox, logs).
export E2E_HOME="${E2E_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/adb-toolbox-e2e}"

# Pinned tool versions. Bump deliberately and re-run the whole suite.
export E2E_STUDIO_VERSION="${E2E_STUDIO_VERSION:-2026.1.4.7}"
export E2E_STUDIO_URL="${E2E_STUDIO_URL:-https://edgedl.me.gvt1.com/android/studio/ide-zips/${E2E_STUDIO_VERSION}/android-studio-quail4-linux.tar.gz}"
export E2E_ROBOT_SERVER_VERSION="${E2E_ROBOT_SERVER_VERSION:-0.11.23}"
export E2E_CMDLINE_TOOLS_URL="${E2E_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
export E2E_SYSTEM_IMAGE="${E2E_SYSTEM_IMAGE:-system-images;android-28;default;x86}"
export E2E_AVD_NAME="${E2E_AVD_NAME:-adb-toolbox-e2e}"
export E2E_SCRCPY_VERSION="${E2E_SCRCPY_VERSION:-v4.1}"

# Locations (override to reuse existing installs).
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$E2E_HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$E2E_HOME/avd}"
export E2E_STUDIO_HOME="${E2E_STUDIO_HOME:-$E2E_HOME/android-studio}"
export E2E_SANDBOX="${E2E_SANDBOX:-$E2E_HOME/studio-sandbox}"
export E2E_PROJECT_DIR="${E2E_PROJECT_DIR:-$E2E_HOME/test-project}"
export E2E_FIXTURES="${E2E_FIXTURES:-$E2E_HOME/fixtures}"
export E2E_SCRCPY_HOME="${E2E_SCRCPY_HOME:-$E2E_HOME/scrcpy-linux-x86_64-$E2E_SCRCPY_VERSION}"
export E2E_X11_ROOT="${E2E_X11_ROOT:-$E2E_HOME/x11}"
export E2E_LOGS="${E2E_LOGS:-$E2E_HOME/logs}"

export E2E_DISPLAY="${E2E_DISPLAY:-:99}"
export E2E_ROBOT_PORT="${E2E_ROBOT_PORT:-8082}"
export E2E_DEVICE_SERIAL="${E2E_DEVICE_SERIAL:-emulator-5554}"
# "off" (software CPU emulation) works everywhere but is slow; use "auto" when /dev/kvm exists.
export E2E_EMULATOR_ACCEL="${E2E_EMULATOR_ACCEL:-$([[ -e /dev/kvm && -w /dev/kvm ]] && echo auto || echo off)}"

export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$E2E_SCRCPY_HOME:$PATH"

# User-space X11 (only populated by setup-x11.sh when no system Xvfb exists).
if [[ -d "$E2E_X11_ROOT/root/usr/bin" ]]; then
    export PATH="$E2E_X11_ROOT/root/usr/bin:$PATH"
    export LD_LIBRARY_PATH="$E2E_X11_ROOT/root/usr/lib/x86_64-linux-gnu:$E2E_X11_ROOT/root/lib/x86_64-linux-gnu:$E2E_X11_ROOT/root/usr/lib/x86_64-linux-gnu/pulseaudio:${LD_LIBRARY_PATH:-}"
    export FONTCONFIG_FILE="$E2E_X11_ROOT/fonts.conf"
    export XKB_CONFIG_ROOT="$E2E_X11_ROOT/root/usr/share/X11/xkb"
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in "$HOME/.local/share/mise/installs/java/temurin-21" /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64; do
        [[ -x "$candidate/bin/java" ]] && export JAVA_HOME="$candidate" && break
    done
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$E2E_HOME" "$E2E_LOGS"

log() { printf '[e2e %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# wait_for <timeout-seconds> <description> <command...>: polls every 2 s.
wait_for() {
    local timeout="$1" what="$2"; shift 2
    local deadline=$((SECONDS + timeout))
    until "$@" >/dev/null 2>&1; do
        (( SECONDS < deadline )) || die "timed out after ${timeout}s waiting for $what"
        sleep 2
    done
}
