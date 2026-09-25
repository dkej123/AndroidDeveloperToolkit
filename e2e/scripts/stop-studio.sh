#!/usr/bin/env bash
# Stops the Android Studio instance launched by start-studio.sh (leaves the emulator running).
source "$(dirname "$0")/lib.sh"

pids="$(pgrep -f "^$E2E_STUDIO_HOME/jbr/bin/java" || true)"
[[ -z "$pids" ]] && { log "Android Studio is not running"; exit 0; }
kill $pids
for _ in $(seq 30); do pgrep -f "^$E2E_STUDIO_HOME/jbr/bin/java" >/dev/null || { log "Android Studio stopped"; exit 0; }; sleep 1; done
kill -9 $pids 2>/dev/null || true
log "Android Studio killed"
