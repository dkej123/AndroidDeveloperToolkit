#!/usr/bin/env bash
# Downloads the pinned Android Studio build and JetBrains' Remote-Robot server plugin, which the
# tests use to find and drive Swing components by XPath over HTTP.
source "$(dirname "$0")/lib.sh"

if [[ ! -x "$E2E_STUDIO_HOME/bin/studio.sh" ]]; then
    log "downloading Android Studio $E2E_STUDIO_VERSION (~1.4 GB)"
    tmp="$(mktemp -d "$E2E_HOME/studio-dl.XXXX")"
    curl -fsSL -o "$tmp/studio.tar.gz" "$E2E_STUDIO_URL"
    tar xzf "$tmp/studio.tar.gz" -C "$tmp"
    rm -rf "$E2E_STUDIO_HOME"
    mv "$tmp/android-studio" "$E2E_STUDIO_HOME"
    rm -rf "$tmp"
fi

robot_dir="$E2E_HOME/robot-server-plugin-$E2E_ROBOT_SERVER_VERSION"
if [[ ! -d "$robot_dir/robot-server-plugin" ]]; then
    log "downloading robot-server-plugin $E2E_ROBOT_SERVER_VERSION"
    mkdir -p "$robot_dir"
    curl -fsSL -o "$robot_dir/plugin.zip" \
        "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/com/intellij/remoterobot/robot-server-plugin/$E2E_ROBOT_SERVER_VERSION/robot-server-plugin-$E2E_ROBOT_SERVER_VERSION.zip"
    unzip -q "$robot_dir/plugin.zip" -d "$robot_dir"
fi
log "Android Studio ready in $E2E_STUDIO_HOME"
