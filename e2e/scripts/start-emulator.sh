#!/usr/bin/env bash
# Boots the test AVD headless (unless $E2E_DEVICE_SERIAL is already online), then puts it in the
# known state the suite expects: root adbd, fixture apps installed, screen kept on, 8 MB logcat.
source "$(dirname "$0")/lib.sh"
export DISPLAY="$E2E_DISPLAY"

adb start-server >/dev/null 2>&1
booted() { [[ "$(adb -s "$E2E_DEVICE_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; }

if booted; then
    log "$E2E_DEVICE_SERIAL already booted"
else
    log "booting $E2E_AVD_NAME (accel=$E2E_EMULATOR_ACCEL; software emulation takes ~3-4 min)"
    nohup emulator -avd "$E2E_AVD_NAME" -no-window -accel "$E2E_EMULATOR_ACCEL" -no-audio -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot -cores 2 -memory 2048 >"$E2E_LOGS/emulator.log" 2>&1 &
    wait_for 900 "emulator boot" booted
fi

# The default (non-Google) image allows root adbd, which the suite uses to verify app data.
adb -s "$E2E_DEVICE_SERIAL" root >/dev/null 2>&1 || true
wait_for 60 "adbd after root" booted
adb -s "$E2E_DEVICE_SERIAL" shell svc power stayon true
adb -s "$E2E_DEVICE_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$E2E_DEVICE_SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$E2E_DEVICE_SERIAL" logcat -G 8M

installed="$(adb -s "$E2E_DEVICE_SERIAL" shell pm list packages -3 | tr -d '\r')"
for apk in "$E2E_FIXTURES"/*.apk; do
    package="$(basename "$apk" .apk)"
    grep -qx "package:$package" <<<"$installed" && continue
    log "installing fixture $package"
    adb -s "$E2E_DEVICE_SERIAL" install -r "$apk" >/dev/null
done
log "device $E2E_DEVICE_SERIAL ready"
