#!/usr/bin/env bash
# PoC: capture exactly what TalkBack says on one screen, without swiping by hand.
# Wraps github.com/asanre/a11y-speech-logger (MIT), pinned below. See tasks/068-talkback-speech-poc.md.
#
#   poc/talkback-speech/run-walk.sh setup   [-s SERIAL]          clone + build + install the logger APK
#   poc/talkback-speech/run-walk.sh on      [-s SERIAL]          save device state, logger engine + service + TalkBack on
#   poc/talkback-speech/run-walk.sh walk    [-s SERIAL] NAME     walk the screen open on the device
#   poc/talkback-speech/run-walk.sh restore [-s SERIAL]          put engine and accessibility services back
#
# Work files go to $POC_HOME (default ~/.cache/devicecockpit-talkback-poc), never into the repo.
set -euo pipefail

UPSTREAM=https://github.com/asanre/a11y-speech-logger
PINNED=6ac7f9b9f73dc355b909fdec79cf5c0c334e3d2a
PKG=io.github.asanre.a11ylogger
LOGGER=$PKG/$PKG.FocusLoggerService
POC_HOME=${POC_HOME:-$HOME/.cache/devicecockpit-talkback-poc}
SRC=$POC_HOME/a11y-speech-logger
OUT=${OUT:-$POC_HOME/audits}

cmd=${1:-}; shift || true
SERIAL=${ANDROID_SERIAL:-}
if [[ ${1:-} == -s ]]; then SERIAL=$2; shift 2; fi
[[ -n $SERIAL ]] || SERIAL=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
[[ -n $SERIAL ]] || { echo "error: no device; connect one or pass -s SERIAL" >&2; exit 1; }
STATE=$POC_HOME/state-${SERIAL//[:\/]/_}.env
sh() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

case $cmd in
setup)
  mkdir -p "$POC_HOME"
  [[ -d $SRC/.git ]] || git clone -q "$UPSTREAM" "$SRC"
  git -C "$SRC" fetch -q origin && git -C "$SRC" checkout -q "$PINNED"
  if [[ ! -f $SRC/local.properties && -n ${ANDROID_HOME:-} ]]; then
    echo "sdk.dir=$ANDROID_HOME" > "$SRC/local.properties"
  fi
  # Needs JDK 17+ and an SDK with platform android-37.0 (compileSdk 37). First build takes a while.
  (cd "$SRC" && ./gradlew :app:assembleDebug -q)
  adb -s "$SERIAL" install -r "$SRC/app/build/outputs/apk/debug/app-debug.apk"
  sh command -v hid >/dev/null || echo "warning: no 'hid' in the device shell: --auto walks won't work" >&2
  ;;
on)
  mkdir -p "$POC_HOME"
  if [[ ! -f $STATE ]]; then  # keep the first snapshot: running 'on' twice must not save our own values
    { echo "TTS=$(sh settings get secure tts_default_synth)"
      echo "SERVICES=$(sh settings get secure enabled_accessibility_services)"
      echo "A11Y=$(sh settings get secure accessibility_enabled)"; } > "$STATE"
    echo "saved device state to $STATE"
  fi
  # Order matters: TalkBack binds its TTS engine when it starts, so the engine goes first.
  python3 "$SRC/tools/audit.py" talkback off --serial "$SERIAL" >/dev/null
  sh settings put secure tts_default_synth "$PKG"
  services=$(sh settings get secure enabled_accessibility_services)
  services=$(tr ':' '\n' <<<"$services" | grep -v -e '^null$' -e '^$' -e "^$LOGGER$" | paste -sd: -)
  sh settings put secure enabled_accessibility_services "${services:+$services:}$LOGGER"
  python3 "$SRC/tools/audit.py" talkback on --serial "$SERIAL"
  sleep 5
  sh dumpsys accessibility | grep -oE 'label=[^,]+' | sed 's/^/bound: /'
  echo "engine: $(sh settings get secure tts_default_synth)"
  ;;
walk)
  name=${1:?usage: run-walk.sh walk [-s SERIAL] NAME}
  python3 "$SRC/tools/audit.py" capture "$name" --auto --steps "${STEPS:-60}" --serial "$SERIAL" --out "$OUT"
  echo "speech only:"; grep -h '^\[speech\]' "$OUT"/*/"$name"/session.txt || true
  ;;
restore)
  [[ -f $STATE ]] || { echo "error: no saved state in $STATE" >&2; exit 1; }
  # shellcheck disable=SC1090
  source "$STATE"
  put() { if [[ $2 == null || -z $2 ]]; then sh settings delete secure "$1" >/dev/null; else sh settings put secure "$1" "$2"; fi; }
  put enabled_accessibility_services "$SERVICES"
  put accessibility_enabled "$A11Y"
  put tts_default_synth "$TTS"
  rm "$STATE"
  echo "restored: engine=$(sh settings get secure tts_default_synth) services=$(sh settings get secure enabled_accessibility_services)"
  ;;
*)
  sed -n '2,10p' "$0"; exit 1 ;;
esac
