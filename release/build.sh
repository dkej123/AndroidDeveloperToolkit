#!/usr/bin/env bash
# Builds the Marketplace ZIP for the version in gradle.properties and checks what is inside it.
#
#   release/build.sh
source "$(dirname "$0")/lib.sh"
ensure_java21
load_e2e_env

version="$(plugin_version)"
gradle build-plugin clean :intellij:buildPlugin
zip="$(plugin_zip)"
[[ -f "$zip" ]] || rdie "expected $zip"

# The descriptor inside the plugin jar must carry the release identity and version.
descriptor="$(unzip -p "$zip" "adb-toolbox/lib/intellij-$version.jar" 2>/dev/null | python3 -c '
import sys, io, zipfile
data = sys.stdin.buffer.read()
print(zipfile.ZipFile(io.BytesIO(data)).read("META-INF/plugin.xml").decode())' 2>/dev/null || true)"
[[ -n "$descriptor" ]] || rdie "could not read META-INF/plugin.xml from $zip"
grep -q "<id>com.github.dkwasniak.adbtoolbox</id>" <<<"$descriptor" || rdie "unexpected plugin id in $zip"
grep -q "<version>$version</version>" <<<"$descriptor" || rdie "plugin.xml in $zip is not version $version"
grep -q "<change-notes>" <<<"$descriptor" || rdie "plugin.xml in $zip has no change notes"
rlog "built $zip ($(du -h "$zip" | cut -f1)), plugin.xml version $version"
