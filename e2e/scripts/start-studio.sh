#!/usr/bin/env bash
# Builds the plugin ZIP, installs it (plus the Remote-Robot server) into a fresh Android Studio
# config/plugins sandbox, launches Studio on $E2E_DISPLAY with a small test project and waits until
# the robot endpoint answers and the project frame is open.
#
#   E2E_SKIP_BUILD=1   reuse intellij/build/distributions/*.zip as-is
source "$(dirname "$0")/lib.sh"
export DISPLAY="$E2E_DISPLAY"

if curl -fs "http://127.0.0.1:$E2E_ROBOT_PORT/" >/dev/null 2>&1; then
    die "something already listens on robot port $E2E_ROBOT_PORT; run stop-studio.sh first"
fi

if [[ "${E2E_SKIP_BUILD:-0}" != 1 ]]; then
    log "building plugin ZIP"
    (cd "$E2E_REPO_ROOT" && ./gradlew :intellij:buildPlugin -q --console=plain >"$E2E_LOGS/build-plugin.log" 2>&1) \
        || die "plugin build failed, see $E2E_LOGS/build-plugin.log"
fi
plugin_zip="$(ls -t "$E2E_REPO_ROOT"/intellij/build/distributions/*.zip | head -1)"

# Fresh config and plugins every run (deterministic settings); system caches are kept for speed.
rm -rf "$E2E_SANDBOX/config" "$E2E_SANDBOX/plugins" "$E2E_SANDBOX/log"
mkdir -p "$E2E_SANDBOX"/{config/options,system,plugins,log}
unzip -q "$plugin_zip" -d "$E2E_SANDBOX/plugins"
cp -r "$E2E_HOME/robot-server-plugin-$E2E_ROBOT_SERVER_VERSION/robot-server-plugin" "$E2E_SANDBOX/plugins/"

cat >"$E2E_SANDBOX/idea.properties" <<EOF
idea.config.path=$E2E_SANDBOX/config
idea.system.path=$E2E_SANDBOX/system
idea.plugins.path=$E2E_SANDBOX/plugins
idea.log.path=$E2E_SANDBOX/log
disable.android.first.run=true
EOF
cat >"$E2E_SANDBOX/config/options/other.xml" <<EOF
<application><component name="PropertyService"><![CDATA[{"keyToString": {"android.sdk.path": "$ANDROID_SDK_ROOT"}}]]></component></application>
EOF
cp "$E2E_STUDIO_HOME/bin/studio64.vmoptions" "$E2E_SANDBOX/studio.vmoptions"
cat >>"$E2E_SANDBOX/studio.vmoptions" <<EOF
-Drobot-server.port=$E2E_ROBOT_PORT
-Drobot-server.host.public=false
-Didea.trust.all.projects=true
-Djb.consents.confirmation.enabled=false
-Djb.privacy.policy.text=<!--999.999-->
-Dide.show.tips.on.startup.default.value=false
-Dide.native.launcher=true
-Dnosplash=true
EOF

# Google's own usage-statistics prompt is keyed off this file; answer "no" up front.
mkdir -p "$HOME/.android"
if [[ ! -f "$HOME/.android/analytics.settings" ]]; then
    echo '{"userId":"00000000-0000-0000-0000-000000000000","hasOptedIn":false,"debugDisablePublishing":true,"saltValue":0,"saltSkew":0,"lastOptinPromptVersion":"2099.1"}' \
        >"$HOME/.android/analytics.settings"
fi

mkdir -p "$E2E_PROJECT_DIR/src"
[[ -f "$E2E_PROJECT_DIR/src/Main.kt" ]] || echo 'class Main' >"$E2E_PROJECT_DIR/src/Main.kt"

log "launching Android Studio from $E2E_STUDIO_HOME"
STUDIO_PROPERTIES="$E2E_SANDBOX/idea.properties" STUDIO_VM_OPTIONS="$E2E_SANDBOX/studio.vmoptions" \
    nohup "$E2E_STUDIO_HOME/bin/studio.sh" "$E2E_PROJECT_DIR" >"$E2E_LOGS/studio-stdout.log" 2>&1 &
echo $! >"$E2E_LOGS/studio.pid"

wait_for 300 "robot-server on port $E2E_ROBOT_PORT" curl -fs "http://127.0.0.1:$E2E_ROBOT_PORT/"
project_frame_open() { curl -fs "http://127.0.0.1:$E2E_ROBOT_PORT/hierarchy" | grep -q 'class="IdeFrameImpl"'; }
wait_for 900 "the project frame" project_frame_open

# Same geometry every run (the fresh config would otherwise open a 1400x1000 window).
curl -fs -X POST -H 'Content-Type: application/json' "http://127.0.0.1:$E2E_ROBOT_PORT/js/execute" \
    -d '{"runInEdt":true,"script":"var f = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]); f.setBounds(0, 0, 1920, 1080); f.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH);"}' \
    >/dev/null || log "could not resize the IDE frame (continuing)"
log "Android Studio is up (robot: http://127.0.0.1:$E2E_ROBOT_PORT, logs: $E2E_SANDBOX/log)"
