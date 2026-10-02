#!/usr/bin/env bash
# Runs the complete verification for a release:
#   1. ./gradlew clean build koverVerify — unit/platform tests, architecture and 80% coverage gates,
#      visual regression goldens (with adb hidden, see lib.sh: gradle_without_device);
#   2. the E2E suite in a real Android Studio + emulator (e2e/scripts/run-e2e.sh), against the ZIP
#      that release/build.sh produced.
#
#   release/test.sh                # everything
#   release/test.sh --skip-e2e     # only step 1 (e.g. CI without an emulator)
source "$(dirname "$0")/lib.sh"
ensure_java21
load_e2e_env

skip_e2e=0
for arg in "$@"; do
    case "$arg" in
        --skip-e2e) skip_e2e=1 ;;
        *) rdie "unknown argument: $arg" ;;
    esac
done

gradle_without_device unit-tests clean build koverVerify
rlog "unit, architecture, coverage and visual gates passed"

if (( skip_e2e )); then
    rlog "E2E skipped (--skip-e2e)"
    exit 0
fi

# Fresh IDE with the release ZIP; the build above already produced it.
[[ -f "$(plugin_zip)" ]] || gradle build-plugin :intellij:buildPlugin
# Idle Gradle daemons push the emulator into swap and adb stops answering: stop them first.
(cd "$RELEASE_ROOT" && ./gradlew --stop >/dev/null 2>&1) || true
E2E_SKIP_BUILD=1 E2E_RESTART_STUDIO=1 "$RELEASE_ROOT/e2e/scripts/run-e2e.sh" >"$RELEASE_LOGS/e2e.log" 2>&1 || {
    tail -60 "$RELEASE_LOGS/e2e.log" >&2
    rdie "E2E suite failed, see $RELEASE_LOGS/e2e.log and e2e/build/reports/tests/e2eTest/index.html"
}
grep -q "BUILD SUCCESSFUL" "$RELEASE_LOGS/e2e.log" || rdie "E2E log has no BUILD SUCCESSFUL, see $RELEASE_LOGS/e2e.log"
rlog "E2E suite passed"
