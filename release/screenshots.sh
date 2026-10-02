#!/usr/bin/env bash
# Takes the Marketplace screenshot set from a real Android Studio + emulator with the release ZIP
# installed (MarketplaceScreenshotsE2ETest): the whole IDE window with code in the editor and the
# tool window on every view and App details tab, Islands Dark, 1920×1080.
#
#   release/screenshots.sh         # -> marketplace/screenshots/<version>/*.png
source "$(dirname "$0")/lib.sh"
ensure_java21
load_e2e_env

version="$(plugin_version)"
out="$RELEASE_ROOT/marketplace/screenshots/$version"
# Capture into a scratch directory: a failed run must not wipe the last good set.
work="$RELEASE_LOGS/screenshots-$version"
rm -rf "$work" && mkdir -p "$work"

[[ -f "$(plugin_zip)" ]] || gradle build-plugin :intellij:buildPlugin
# A restarted IDE has a fresh config (no leftovers from earlier E2E runs on screen).
E2E_SKIP_BUILD=1 E2E_RESTART_STUDIO=1 "$RELEASE_ROOT/e2e/scripts/run-e2e.sh" --setup-only >"$RELEASE_LOGS/screenshots-setup.log" 2>&1 \
    || { tail -40 "$RELEASE_LOGS/screenshots-setup.log" >&2; rdie "E2E environment did not start"; }
gradle screenshots :e2e:e2eTest -Pe2e.tags=marketplace "-Pe2e.screenshotDir=$work"

count="$(find "$work" -name '*.png' | wc -l)"
(( count >= 9 )) || rdie "expected 9 screenshots in $work, found $count"
rm -rf "$out" && mkdir -p "$(dirname "$out")" && mv "$work" "$out"
rlog "$count screenshots in $out — upload them on the Marketplace page (docs/marketplace.md)"
