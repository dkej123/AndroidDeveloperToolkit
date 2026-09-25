#!/usr/bin/env bash
# One command from a clean machine/session to a finished E2E run (docs/e2e-testing.md).
#
#   e2e/scripts/run-e2e.sh                 # set up everything that is missing, run the suite
#   e2e/scripts/run-e2e.sh --setup-only    # only provision + start (leave it running for debugging)
#   e2e/scripts/run-e2e.sh -Pe2e.tags=smoke   # extra args go to Gradle
#
# Every step is idempotent: already-downloaded tools and an already-booted emulator/IDE are reused.
# Nothing needs root. First run downloads ~3 GB (SDK image, Android Studio) and boots the emulator.
source "$(dirname "$0")/lib.sh"

setup_only=0
gradle_args=()
for arg in "$@"; do
    case "$arg" in
        --setup-only) setup_only=1 ;;
        *) gradle_args+=("$arg") ;;
    esac
done

[[ "$(uname -s)" == Linux ]] || die "the E2E environment scripts support Linux x86_64 only"
command -v java >/dev/null || die "JDK 21 required (set JAVA_HOME)"
for tool in curl unzip python3 tar; do command -v "$tool" >/dev/null || die "missing required tool: $tool"; done

log "E2E_HOME=$E2E_HOME  accel=$E2E_EMULATOR_ACCEL"
"$E2E_REPO_ROOT/e2e/scripts/setup-x11.sh"
"$E2E_REPO_ROOT/e2e/scripts/setup-android.sh"
"$E2E_REPO_ROOT/e2e/scripts/setup-studio.sh"
"$E2E_REPO_ROOT/e2e/scripts/start-display.sh"
"$E2E_REPO_ROOT/e2e/scripts/start-emulator.sh"
if curl -fs "http://127.0.0.1:$E2E_ROBOT_PORT/" >/dev/null 2>&1 && [[ "${E2E_RESTART_STUDIO:-1}" != 1 ]]; then
    log "reusing the running Android Studio (E2E_RESTART_STUDIO=0)"
else
    "$E2E_REPO_ROOT/e2e/scripts/stop-studio.sh"
    "$E2E_REPO_ROOT/e2e/scripts/start-studio.sh"
fi

if (( setup_only )); then
    log "environment is up; run: (cd $E2E_REPO_ROOT && ./gradlew :e2e:e2eTest)"
    exit 0
fi

cd "$E2E_REPO_ROOT"
status=0
./gradlew :e2e:e2eTest --console=plain "${gradle_args[@]}" || status=$?
log "report: $E2E_REPO_ROOT/e2e/build/reports/tests/e2eTest/index.html (failure artifacts: e2e/build/e2e-report/failures)"
exit $status
