#!/usr/bin/env bash
# Installs the Android SDK pieces the suite needs (platform/build tools, emulator, one system image),
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

log "installing platform-tools, build-tools, emulator and $E2E_SYSTEM_IMAGE"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "$E2E_SYSTEM_IMAGE" >"$E2E_LOGS/sdkmanager.log" 2>&1

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

# Purpose-built fixture for deep-link opening and runtime-permission mutations. Building it here
# keeps the repository free of opaque APK binaries while pinning every SDK input above.
deep_link_apk="$E2E_FIXTURES/dev.acme.adbtoolbox.e2efixture.apk"
if [[ ! -s "$deep_link_apk" ]]; then
    log "building deep-link fixture"
    fixture_src="$E2E_REPO_ROOT/e2e/fixtures/deeplink"
    fixture_tmp="$(mktemp -d)"
    mkdir -p "$fixture_tmp/classes" "$fixture_tmp/dex"
    "$JAVA_HOME/bin/javac" -source 8 -target 8 -classpath "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
        -d "$fixture_tmp/classes" "$fixture_src/src/dev/acme/adbtoolbox/e2efixture/MainActivity.java"
    "$ANDROID_SDK_ROOT/build-tools/35.0.0/d8" --lib "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
        --output "$fixture_tmp/dex" "$fixture_tmp/classes/dev/acme/adbtoolbox/e2efixture/MainActivity.class"
    "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt" package -f -M "$fixture_src/AndroidManifest.xml" \
        -I "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" -F "$fixture_tmp/unsigned.apk"
    "$JAVA_HOME/bin/jar" uf "$fixture_tmp/unsigned.apk" -C "$fixture_tmp/dex" classes.dex
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$fixture_tmp/debug.keystore" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=ADB Toolbox E2E,O=Android,C=US" -keyalg RSA -validity 10000 >/dev/null 2>&1
    "$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" sign --ks "$fixture_tmp/debug.keystore" \
        --ks-pass pass:android --key-pass pass:android --out "$deep_link_apk" "$fixture_tmp/unsigned.apk"
    rm -rf "$fixture_tmp"
fi
log "Android SDK ready in $ANDROID_SDK_ROOT"
