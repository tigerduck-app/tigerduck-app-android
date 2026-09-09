#!/usr/bin/env bash
# Drive the debug clock override from a shell, non-interactively.
#
# Unlike _lib.sh's maybe_preset_clock — which writes shared_prefs/debug_clock.xml
# and therefore needs the app restarted to take effect — this broadcasts to
# DebugClockReceiver, which routes through DebugClockController.setOverride():
# the override applies to the running app immediately and every AlarmManager
# entry, widget and Live Update is rescheduled against the new clock.
#
# Debug builds only; the receiver does not exist in a release APK.
#
# Usage:
#   ./debug/set-clock.sh 2026-09-09 10:44        # freeze at that Taipei time
#   ./debug/set-clock.sh 2026-09-09T10:44 --tick # ...and let it advance 1:1
#   ./debug/set-clock.sh --clear                 # back to real time
#
# Options:
#   --tick             ticking mode (default: frozen)
#   --clear            remove the override
#   -s, --serial SER   target device (default: auto-pick / prompt)
#   -p, --package PKG  app id (default: org.ntust.app.tigerduck)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

PKG="org.ntust.app.tigerduck"
SERIAL=""
FROZEN="true"
CLEAR="false"
WHEN=()

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while (( $# )); do
  case "$1" in
    --tick|--ticking) FROZEN="false"; shift ;;
    --clear)          CLEAR="true";   shift ;;
    -s|--serial)      SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    -p|--package)     PKG="${2:?--package needs a value}";   shift 2 ;;
    -h|--help)        usage 0 ;;
    -*)               echo "error: unknown option '$1'" >&2; usage 1 ;;
    *)                WHEN+=("$1"); shift ;;
  esac
done

require adb
[[ -n "$SERIAL" ]] || SERIAL="$(pick_device phone)"

# `am broadcast` reports delivery, not what the receiver decided, so a typo in
# the timestamp would otherwise look like success. Read the receiver's own log
# line back instead of trusting the broadcast result.
adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

if [[ "$CLEAR" == "true" ]]; then
  echo "==> Clearing clock override on $PKG ($SERIAL)"
  adb -s "$SERIAL" shell am broadcast \
    -a org.ntust.app.tigerduck.debug.CLEAR_CLOCK -p "$PKG" >/dev/null
else
  (( ${#WHEN[@]} )) || { echo "error: pass a date/time, or --clear" >&2; usage 1; }
  # Join so both "2026-09-09 10:44" (two argv words) and "2026-09-09T10:44"
  # (one) arrive as a single extra; the receiver normalises the separator.
  AT="${WHEN[*]}"
  echo "==> Setting clock to '$AT' Taipei (frozen=$FROZEN) on $PKG ($SERIAL)"
  adb -s "$SERIAL" shell am broadcast \
    -a org.ntust.app.tigerduck.debug.SET_CLOCK \
    -p "$PKG" --es at "'$AT'" --ez frozen "$FROZEN" >/dev/null
fi

sleep 1
line="$(adb -s "$SERIAL" logcat -d -s DebugClock:V 2>/dev/null | tail -1)"
if [[ -z "$line" ]]; then
  echo "warn: no DebugClock log line. Is the app installed, debuggable, and running?" >&2
  echo "      (a stopped app receives the broadcast and starts, but give it a moment)" >&2
  exit 1
fi
echo "${line#*DebugClock: }"
