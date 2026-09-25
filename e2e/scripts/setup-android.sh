#!/usr/bin/env bash
# Installs the Android SDK pieces the suite needs (platform-tools, emulator, one system image),
# creates the test AVD, and downloads scrcpy plus the pinned fixture APKs.
source "$(dirname "$0")/lib.sh"

if [[ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "downloading Android command-line tools"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/cmdline.zip" "$E2E_CMDLINE_TOOLS_URL"
    unzip -q "$tmp/cmdline.zip" -d "$tmp"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf "$tmp"
fi

log "installing platform-tools, emulator and $E2E_SYSTEM_IMAGE"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "emulator" "$E2E_SYSTEM_IMAGE" >"$E2E_LOGS/sdkmanager.log" 2>&1

mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -d "$ANDROID_AVD_HOME/$E2E_AVD_NAME.avd" ]]; then
    log "creating AVD $E2E_AVD_NAME"
    echo no | avdmanager create avd -n "$E2E_AVD_NAME" -k "$E2E_SYSTEM_IMAGE" -d pixel_3 --force >/dev/null
    cat >>"$ANDROID_AVD_HOME/$E2E_AVD_NAME.avd/config.ini" <<EOF
hw.ramSize=2048
hw.gpu.enabled=no
hw.audioInput=no
hw.audioOutput=no
EOF
fi

if [[ ! -x "$E2E_SCRCPY_HOME/scrcpy" ]]; then
    log "downloading scrcpy $E2E_SCRCPY_VERSION"
    curl -fsSL "https://github.com/Genymobile/scrcpy/releases/download/$E2E_SCRCPY_VERSION/scrcpy-linux-x86_64-$E2E_SCRCPY_VERSION.tar.gz" | tar xz -C "$E2E_HOME"
fi

# Fixture apps: small open-source F-Droid builds, pinned by version code.
mkdir -p "$E2E_FIXTURES"
while read -r package version; do
    [[ -z "$package" || "$package" == \#* ]] && continue
    target="$E2E_FIXTURES/$package.apk"
    [[ -s "$target" ]] && continue
    log "downloading fixture $package ($version)"
    curl -fsSL -o "$target" "https://f-droid.org/repo/${package}_${version}.apk"
done <"$E2E_REPO_ROOT/e2e/fixtures/apks.txt"
log "Android SDK ready in $ANDROID_SDK_ROOT"
