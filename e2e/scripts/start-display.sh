#!/usr/bin/env bash
# Starts Xvfb on $E2E_DISPLAY (1920x1080) plus a minimal window manager, unless one is running.
source "$(dirname "$0")/lib.sh"
export DISPLAY="$E2E_DISPLAY"

if xdpyinfo >/dev/null 2>&1; then
    log "display $DISPLAY already running"
    exit 0
fi

xkb_args=()
if [[ -d "$E2E_X11_ROOT/root" ]]; then
    ln -sfn "$E2E_X11_ROOT/root/usr/bin" /tmp/xkb   # see setup-x11.sh
    xkb_args=(-xkbdir "$E2E_X11_ROOT/root/usr/share/X11/xkb")
fi
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix 2>/dev/null || true

nohup Xvfb "$DISPLAY" -screen 0 1920x1080x24 -nolisten tcp "${xkb_args[@]}" >"$E2E_LOGS/xvfb.log" 2>&1 &
wait_for 20 "Xvfb on $DISPLAY" xdpyinfo
if command -v openbox >/dev/null; then
    nohup openbox >"$E2E_LOGS/openbox.log" 2>&1 &
fi
log "display $DISPLAY ready"
