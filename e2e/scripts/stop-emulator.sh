#!/usr/bin/env bash
# Shuts the test emulator down.
source "$(dirname "$0")/lib.sh"
adb -s "$E2E_DEVICE_SERIAL" emu kill >/dev/null 2>&1 || true
log "emulator $E2E_DEVICE_SERIAL stopped"
