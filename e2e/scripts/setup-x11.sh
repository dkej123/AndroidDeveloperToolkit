#!/usr/bin/env bash
# Provides a headless X server (Xvfb), xdotool/xwd and the X client libraries Android Studio and
# the emulator need. Uses the system packages when present; otherwise (no root, e.g. a disposable
# container) extracts Ubuntu 24.04 .debs into $E2E_X11_ROOT without installing anything.
source "$(dirname "$0")/lib.sh"

if command -v Xvfb >/dev/null && command -v xdotool >/dev/null && [[ ! -d "$E2E_X11_ROOT/root" ]]; then
    log "system Xvfb/xdotool found; nothing to do"
    exit 0
fi

if [[ -x "$E2E_X11_ROOT/root/usr/bin/Xvfb" ]]; then
    log "user-space X11 already present in $E2E_X11_ROOT"
else
    grep -q 'VERSION_ID="24.04"' /etc/os-release || die "user-space X11 install is only tested on Ubuntu 24.04; install xvfb xdotool x11-apps instead"
    mkdir -p "$E2E_X11_ROOT"
    log "resolving and downloading X11 packages into $E2E_X11_ROOT"
    (cd "$E2E_X11_ROOT" && python3 "$E2E_REPO_ROOT/e2e/scripts/userspace-debs.py" \
        xvfb xdotool x11-apps x11-utils xauth openbox \
        libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libgl1 libasound2t64 libpulse0 libnss3 \
        libxcomposite1 libxcursor1 libxdamage1 libxkbcommon0 libxkbcommon-x11-0 libxcb-cursor0 \
        libxcb-icccm4 libxcb-image0 libxcb-keysyms1 libxcb-randr0 libxcb-render-util0 \
        libxcb-shape0 libxcb-xinerama0 libxcb-xkb1 >/dev/null)
    mkdir -p "$E2E_X11_ROOT/root"
    for deb in "$E2E_X11_ROOT"/debs/*.deb; do dpkg-deb -x "$deb" "$E2E_X11_ROOT/root"; done

    # Xvfb runs the keymap compiler from its compiled-in /usr/bin. Re-point that one string to a
    # same-length path (/tmp/xkb, a symlink created by start-display.sh) instead of needing root.
    python3 - "$E2E_X11_ROOT/root/usr/bin/Xvfb" <<'PY'
import sys
path = sys.argv[1]
data = open(path, "rb").read()
old, new = b"\x00/usr/bin\x00", b"\x00/tmp/xkb\x00"
if data.count(old) != 1:
    sys.exit("unexpected Xvfb binary layout: %d occurrences" % data.count(old))
open(path, "wb").write(data.replace(old, new))
PY
fi

cat > "$E2E_X11_ROOT/fonts.conf" <<EOF
<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>$E2E_X11_ROOT/root/usr/share/fonts</dir><dir>/usr/share/fonts</dir><cachedir>$E2E_X11_ROOT/fccache</cachedir></fontconfig>
EOF
log "user-space X11 ready"
