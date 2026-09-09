#!/usr/bin/env bash
# Interactive helper for capturing store-listing screenshots.
#
# Nothing here drives the app's UI — you navigate on the device and press
# Enter to capture. What the script does is set up the *state* worth
# photographing, which is the part that is tedious and error-prone by hand:
# a made-up timetable, a made-up student ID, a chosen library QR payload, a
# frozen in-app clock, and a clean status bar.
#
# Everything lives under debug/screenshot/:
#   screenshot.sh           this script
#   fixture.example.json    sample fake data, copy it to fixture.json
#   out/                    captures (gitignored)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_lib.sh
source "$SCRIPT_DIR/../_lib.sh"

cd "$ROOT_DIR"

OUT_ROOT="$SCRIPT_DIR/out"
DEFAULT_FIXTURE="$SCRIPT_DIR/fixture.json"
EXAMPLE_FIXTURE="$SCRIPT_DIR/fixture.example.json"

ACTION_LOAD="org.ntust.app.tigerduck.debug.LOAD_FIXTURE"
ACTION_CLEAR="org.ntust.app.tigerduck.debug.CLEAR_FIXTURE"

# SERIAL is the phone (or whatever single device is attached); WATCH is the
# optional companion. Nearly everything worth photographing lives on both --
# the watch mirrors the phone's timetable, accent and debug clock over the
# Data Layer -- so driving them as a pair is the normal case, and switching
# devices to load the same fixture twice was just friction.
SERIAL=""
WATCH=""
PKG="org.ntust.app.tigerduck"
FLAVOR="play"
RUN_ROOT=""
RUN_DIR=""
WATCH_RUN_DIR=""
DEMO_ON=false
NETWORK_OFF=false
# Probed once per device in choose_device: the check costs two adb round trips
# and the answer cannot change while a device stays attached.
CAN_SET_TIME=false

# Every device this session drives: the phone, plus the watch when one was
# picked. Emitted one per line so callers can `for s in $(targets)`.
targets() {
  [[ -n "$SERIAL" ]] && echo "$SERIAL"
  [[ -n "$WATCH" ]] && echo "$WATCH"
  return 0
}

# Undo the two device-wide changes on the way out, however we leave. The
# fixture and the debug clock are deliberately NOT undone here: you often want
# to run the script again, or keep shooting by hand, without rebuilding the
# state you just set up. Menu entries clear both explicitly.
cleanup() {
  if [[ "$DEMO_ON" == true && -n "$SERIAL" ]]; then
    adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo \
      -e command exit >/dev/null 2>&1 || true
  fi
  if [[ "$NETWORK_OFF" == true ]]; then
    local s
    for s in $(targets); do
      adb -s "$s" shell svc wifi enable >/dev/null 2>&1 || true
      adb -s "$s" shell svc data enable >/dev/null 2>&1 || true
    done
  fi
}
trap cleanup EXIT

# --------------------------------------------------------------- device info

# Echo a filesystem-safe directory name describing a device: model, screen
# size and density, so shots from a phone and from a tablet don't collide.
device_dir_name() {
  local serial="$1" model size density
  model="$(adb -s "$serial" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')"
  # `wm size` prints "Physical size: 1080x2400", plus an "Override size:" line
  # when a forced resolution is active. Take the last — that is what gets shot.
  size="$(adb -s "$serial" shell wm size </dev/null 2>/dev/null | tr -d '\r' \
    | sed -n 's/.*size: *\([0-9]*x[0-9]*\).*/\1/p' | tail -1)"
  density="$(adb -s "$serial" shell wm density </dev/null 2>/dev/null | tr -d '\r' \
    | sed -n 's/.*density: *\([0-9]*\).*/\1/p' | tail -1)"
  echo "${model:-device}${size:+-$size}${density:+-${density}dpi}" | tr ' /' '--'
}

# The launcher activity to restart after loading a fixture. The watch APK
# carries the same applicationId as the play phone build, so the package alone
# does not say which one is in front of us.
main_activity() {
  if is_watch "${1:-$SERIAL}"; then
    echo "org.ntust.app.tigerduck.wear.MainActivity"
  else
    echo "org.ntust.app.tigerduck.MainActivity"
  fi
}

is_watch() {
  local serial="${1:-$SERIAL}"
  [[ "$(adb -s "$serial" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r')" == *watch* ]]
}

# Serials of everything adb currently lists as `device`.
attached_serials() {
  adb devices | while IFS= read -r line; do
    [[ "$line" =~ ^(.+)[[:space:]]+device([[:space:]].*)?$ ]] || continue
    echo "${BASH_REMATCH[1]}"
  done
}

# `adb shell` forwards stdin, and when stdin is a pipe it drains the whole of
# it -- which would swallow the menu loop's own input. Every probe here wants
# no stdin at all, so `</dev/null` is not decoration. The one call that does
# want stdin is the fixture staging pipe, which supplies it explicitly.

# A cheap, side-effect-free hint for the menu label only. `set_system_time`
# does not trust it -- it goes and finds out.
#
# Being an emulator is not the test, though it used to be the whole test. An
# AVD created with the Play Store is a production image -- `ro.build.type` is
# `user`, there is no `su` -- so `date -s` comes back "Operation not permitted"
# exactly as it does on retail hardware.
can_set_system_time() {
  adb -s "$SERIAL" shell 'su -c id' </dev/null >/dev/null 2>&1 && return 0
  case "$(adb -s "$SERIAL" shell getprop ro.build.type </dev/null 2>/dev/null | tr -d '\r')" in
    userdebug|eng) return 0 ;;
    *) return 1 ;;
  esac
}

# Whether the adb shell is running as root right now.
#
# `adb root` cannot be judged by its exit code: on a production build it prints
# "adbd cannot run as root in production builds" and still exits 0. Who the
# shell runs as afterwards is the only honest answer.
adb_shell_is_root() {
  [[ "$(adb -s "$SERIAL" shell id -u </dev/null 2>/dev/null | tr -d '\r')" == "0" ]]
}

# Restart adbd as root, and answer whether it actually took. Asking costs
# nothing on a build that refuses -- adbd is not restarted at all and the
# connection stays up. It is the success case that drops and re-opens the
# connection, hence wait-for-device.
gain_adb_root() {
  adb_shell_is_root && return 0
  adb -s "$SERIAL" root >/dev/null 2>&1 || true
  adb -s "$SERIAL" wait-for-device
  adb_shell_is_root
}

# Pick the phone, then offer whatever watch is also attached.
#
# The watch is optional and asked about rather than assumed: a watch left
# plugged in from some other task should not silently start receiving
# fixtures. Declining leaves WATCH empty and every command behaves exactly as
# it did before there was a second device.
choose_devices() {
  SERIAL="$(pick_device "phone")"
  WATCH=""

  local candidates=() s
  for s in $(attached_serials); do
    [[ "$s" == "$SERIAL" ]] && continue
    is_watch "$s" && candidates+=("$s")
  done

  if [[ ${#candidates[@]} -eq 1 ]]; then
    prompt_yn "Also drive the watch ${candidates[0]}?" y && WATCH="${candidates[0]}"
  elif [[ ${#candidates[@]} -gt 1 ]]; then
    echo "Pick a watch, or blank for none:"
    local i
    for i in "${!candidates[@]}"; do
      echo "  [$i] ${candidates[$i]}"
    done
    local choice
    read -r -p "> " choice || choice=""
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice < ${#candidates[@]} )); then
      WATCH="${candidates[$choice]}"
    fi
  fi

  # One timestamp for the pair, so a phone shot and the watch shot taken
  # beside it land under the same run.
  RUN_ROOT="$OUT_ROOT/$(date +%Y%m%d-%H%M%S)"
  RUN_DIR="$RUN_ROOT/$(device_dir_name "$SERIAL")"
  WATCH_RUN_DIR=""
  [[ -n "$WATCH" ]] && WATCH_RUN_DIR="$RUN_ROOT/$(device_dir_name "$WATCH")"

  echo "==> Using $SERIAL${WATCH:+ + watch $WATCH}"
  if can_set_system_time; then CAN_SET_TIME=true; else CAN_SET_TIME=false; fi
  if [[ -n "$WATCH" ]] || is_watch; then
    echo "    The watch takes the fixture's library pass only — its timetable,"
    echo "    accent and app clock are mirrored from the phone, and neither the"
    echo "    status bar demo mode nor demo mode itself exists on Wear."
  fi
}

build_and_install() {
  local flavor
  read -r -p "Flavor [play/fdroid] (default $FLAVOR): " flavor || return 0
  flavor="${flavor:-$FLAVOR}"
  local task
  case "$flavor" in
    play)   task=":app:assemblePlayDebug";   PKG="org.ntust.app.tigerduck" ;;
    fdroid) task=":app:assembleFdroidDebug"; PKG="org.ntust.app.tigerduck.fdroid" ;;
    *) echo "error: flavor must be play or fdroid" >&2; return 1 ;;
  esac
  FLAVOR="$flavor"
  echo "==> ./gradlew $task"
  ./gradlew "$task" || { echo "error: build failed" >&2; return 1; }
  local apk
  apk="$(resolve_apk "$(module_outputs_dir app)/apk/$FLAVOR/debug/*.apk")" || return 1
  echo "==> Installing $apk → $SERIAL"
  adb_install "$SERIAL" "$PKG" "$apk" || return 1

  # The watch build carries the same applicationId as the play phone build, so
  # a plain `:wear:installDebug` with both attached puts the watch app over the
  # phone one. Install to the watch by serial, and only when one was picked.
  if [[ -n "$WATCH" ]]; then
    echo "==> ./gradlew :wear:assembleDebug"
    ./gradlew :wear:assembleDebug || { echo "error: wear build failed" >&2; return 1; }
    local wapk
    wapk="$(resolve_apk "$(module_outputs_dir wear)/apk/debug/*.apk")" || return 1
    echo "==> Installing $wapk → $WATCH"
    adb_install "$WATCH" "$PKG" "$wapk"
  fi
}

# Stage the fixture inside the app's own private files dir and tell the app to
# read it.
#
# The two obvious staging spots both fail. /data/local/tmp is shell-owned and
# SELinux denies the app domain reading it. The app's *external* files dir,
# /sdcard/Android/data/<pkg>/files, looks like the answer and was what this
# script used, but on Android 11+ that directory belongs to whoever creates
# it: the `mkdir -p` used to run as shell, so it landed as shell:ext_data_rw
# 0770 with the app's uid outside the group, and every LOAD came back "no such
# file" while adb reported a successful push.
#
# `run-as` sidesteps both. It runs as the app's own uid, so the file is the
# app's from the moment it exists, and it is always available here because the
# fixture receiver only exists in debug builds, which are debuggable by
# definition. Piping through stdin rather than passing the JSON as an argument
# keeps the fixture off the command line, where its size and its quoting would
# both eventually bite.
load_fixture() {
  local fixture="$DEFAULT_FIXTURE"
  if [[ ! -f "$fixture" ]]; then
    echo "No $DEFAULT_FIXTURE yet."
    if prompt_yn "Copy fixture.example.json to fixture.json and edit that?" y; then
      cp "$EXAMPLE_FIXTURE" "$DEFAULT_FIXTURE"
      echo "==> Created $DEFAULT_FIXTURE — edit it, then run this step again."
      return 0
    fi
    read -r -p "Path to fixture JSON: " fixture || return 1
    [[ -f "$fixture" ]] || { echo "error: no such file: $fixture" >&2; return 1; }
  fi

  # Fail here rather than let the app log a parse error you have to go find.
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$fixture" \
      || { echo "error: $fixture is not valid JSON" >&2; return 1; }
  fi

  local remote="/data/user/0/$PKG/files/fixture.json"
  # Which of the fixture's language variants to read. The names go into the
  # cache as plain strings, so this is decided now, not at render time —
  # switching the app's language later does not retranslate them. Blank means
  # "whatever the app's current language is".
  local lang
  read -r -p "Language variant — zh, en, or blank for the app's current: " lang || true
  local lang_arg=()
  [[ -n "$lang" ]] && lang_arg=(--es lang "$lang")

  # The same file goes to every selected device. Each app takes the half it
  # owns: the phone reads all of it, the watch only the library pass, because
  # its timetable is already there from the phone's Data Layer push.
  local dev
  for dev in $(targets); do
    if ! adb -s "$dev" shell "run-as $PKG sh -c 'cat > $remote'" < "$fixture"; then
      echo "error: could not write the fixture into $PKG on $dev." >&2
      echo "       run-as needs a debug build — check you installed the debug" >&2
      echo "       APK there, not a release one." >&2
      return 1
    fi

    # ${a[@]+"${a[@]}"} rather than "${a[@]}": macOS still ships bash 3.2,
    # where expanding an empty array under `set -u` aborts the script.
    adb -s "$dev" shell am broadcast -a "$ACTION_LOAD" -p "$PKG" \
      --es file "$remote" ${lang_arg[@]+"${lang_arg[@]}"} >/dev/null

    # Restart rather than tell you to go and reopen a screen. The fixture is a
    # cache write, and the running process is holding the old list in memory; a
    # demo fixture additionally only arms its network kill switch and its wizard
    # skip at process start, and restarting is also what guarantees no sync that
    # was already in flight lands on top of what we just wrote.
    adb -s "$dev" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb -s "$dev" shell am start -n "$PKG/$(main_activity "$dev")" >/dev/null 2>&1 || true

    echo "==> Sent $fixture → $dev, restarted the app"
    echo "    Watch it land:  adb -s $dev logcat -s $(if is_watch "$dev"; then echo WearFixture; else echo DebugFixture; fi)"
  done
  echo ""
  if is_watch; then
    echo "    On a watch the fixture only supplies the library pass. The"
    echo "    timetable arrives from the phone, so load it there too."
  elif grep -q '"demoMode"[[:space:]]*:[[:space:]]*true' "$fixture"; then
    echo "    demoMode is on: every server is refused, so nothing can overwrite"
    echo "    the fake data. Menu 3 turns it back off."
  else
    echo "    demoMode is off in this fixture, so a background sync will replace"
    echo "    the fake timetable with the real one within seconds. Set"
    echo "    \"demoMode\": true, or use menu 7."
  fi
}

clear_fixture() {
  local dev
  for dev in $(targets); do
    adb -s "$dev" shell am broadcast -a "$ACTION_CLEAR" -p "$PKG" >/dev/null
    adb -s "$dev" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb -s "$dev" shell am start -n "$PKG/$(main_activity "$dev")" >/dev/null 2>&1 || true
  done
  echo "==> Cleared demo mode, the student ID and the library QR override,"
  echo "    and put the real hand-added courses back."
  echo "    Fetched courses, bulletins and calendar return on the next sync —"
  echo "    pull to refresh."
}

set_app_clock() {
  # set-clock.sh already owns this: it broadcasts to DebugClockReceiver, the
  # same path the in-app Developer picker uses.
  local when
  read -r -p "App clock (YYYY-MM-DD HH:MM, blank to clear): " when || return 0
  if [[ -z "$when" ]]; then
    "$SCRIPT_DIR/../set-clock.sh" --clear -s "$SERIAL" -p "$PKG"
    echo "==> App clock override cleared — back to real time."
    return 0
  fi
  local tick=()
  prompt_yn "Let it advance 1:1 from there (otherwise frozen)?" n && tick=(--tick)
  # shellcheck disable=SC2086  # $when splits into date + time on purpose, and
  # the array guard is the bash 3.2 empty-array dance again.
  "$SCRIPT_DIR/../set-clock.sh" $when ${tick[@]+"${tick[@]}"} -s "$SERIAL" -p "$PKG"
}

# SystemUI demo mode: a fixed status bar clock, a full battery, full signal and
# no notification icons. Undone by the EXIT trap as well as by this entry, so
# killing the script does not leave a device stuck showing a fake status bar.
toggle_demo_mode() {
  if [[ "$DEMO_ON" == true ]]; then
    adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo \
      -e command exit >/dev/null 2>&1 || true
    DEMO_ON=false
    echo "==> Status bar back to normal."
    return 0
  fi
  local clock
  read -r -p "Status bar clock [HHMM] (default 0930): " clock || return 0
  clock="${clock:-0930}"
  adb -s "$SERIAL" shell settings put global sysui_demo_allowed 1 >/dev/null
  local demo=(am broadcast -a com.android.systemui.demo)
  adb -s "$SERIAL" shell "${demo[@]}" -e command enter >/dev/null
  adb -s "$SERIAL" shell "${demo[@]}" -e command clock -e hhmm "$clock" >/dev/null
  adb -s "$SERIAL" shell "${demo[@]}" -e command battery -e level 100 -e plugged false >/dev/null
  adb -s "$SERIAL" shell "${demo[@]}" -e command network -e wifi show -e level 4 >/dev/null
  adb -s "$SERIAL" shell "${demo[@]}" -e command network -e mobile show -e level 4 -e datatype none >/dev/null
  adb -s "$SERIAL" shell "${demo[@]}" -e command notifications -e visible false >/dev/null
  DEMO_ON=true
  echo "==> Status bar: $clock, full battery and signal, no notification icons."
}

# Open Settings → Date & time. The only route left on a production image, and
# it works there because we have already turned automatic time off, which is
# what greys the manual field out.
open_date_settings() {
  adb -s "$SERIAL" shell settings put global auto_time 0 >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell am start -a android.settings.DATE_SETTINGS >/dev/null 2>&1 \
    && echo "==> Opened Settings → Date & time on the device. Set it there." \
    || echo "error: could not open the date settings screen." >&2
}

set_system_time() {
  # Find out for real rather than infer. `su` first, because a rooted retail
  # phone has one and does not want adbd restarted underneath it.
  local via="" unroot_after=false
  if adb -s "$SERIAL" shell 'su -c id' </dev/null >/dev/null 2>&1; then
    via=su
  elif adb_shell_is_root; then
    via=root                 # already rooted, someone else did it; leave it that way
  elif [[ "$SERIAL" == *:* || "$SERIAL" == *_adb-tls-connect* ]]; then
    # Over wireless adb a successful `adb root` drops the connection and does
    # not always come back on its own, which would strand the whole session.
    echo "This device is on wireless adb. 'adb root' restarts adbd and the"
    echo "connection may not return without another 'adb connect'."
    if prompt_yn "Try it anyway?" n && gain_adb_root; then
      via=root
      unroot_after=true
    fi
  elif gain_adb_root; then
    via=root
    unroot_after=true
  fi

  if [[ -z "$via" ]]; then
    local build_type
    build_type="$(adb -s "$SERIAL" shell getprop ro.build.type </dev/null 2>/dev/null | tr -d '\r')"
    echo "This device won't let adb set the real system clock: ro.build.type is"
    echo "'$build_type', there is no working su, and adb root was refused."
    if [[ "$SERIAL" == emulator-* ]]; then
      echo ""
      echo "It being an emulator isn't enough — an AVD created *with* the Play"
      echo "Store is a production image. Make one from a plain 'Google APIs'"
      echo "(no Play Store) system image and adb root works there."
    fi
    echo ""
    echo "Two things you probably wanted instead:"
    echo "  • the status bar clock  → menu 5"
    echo "  • the app's idea of now → menu 4"
    echo ""
    if prompt_yn "Open Settings → Date & time on the device so you can set it by hand?" y; then
      open_date_settings
    fi
    return 0
  fi

  local when
  read -r -p "System time (YYYY-MM-DD HH:MM): " when || when=""
  if [[ -z "$when" ]]; then
    [[ "$unroot_after" == true ]] && drop_adb_root
    return 0
  fi

  # Automatic time would snap the clock straight back over NTP.
  adb -s "$SERIAL" shell settings put global auto_time 0 >/dev/null

  if [[ "$via" == su ]]; then
    adb -s "$SERIAL" shell "su -c 'date -s \"$when:00\"'" >/dev/null 2>&1 || true
  else
    adb -s "$SERIAL" shell "date -s \"$when:00\"" >/dev/null 2>&1 || true
  fi

  # Read the clock back rather than trust an exit code: toybox `date -s` prints
  # the date it was asked for before it discovers it cannot apply it, so a
  # failure reads like a success.
  local now
  now="$(adb -s "$SERIAL" shell date '+%Y-%m-%d %H:%M' </dev/null 2>/dev/null | tr -d '\r')"
  [[ "$unroot_after" == true ]] && drop_adb_root

  if [[ "$now" == "$when" ]]; then
    echo "==> System clock: $(adb -s "$SERIAL" shell date | tr -d '\r')"
    echo "    Re-enable automatic time afterwards:"
    echo "    adb -s $SERIAL shell settings put global auto_time 1"
  else
    echo "error: the clock still reads '$now', not '$when'." >&2
    if prompt_yn "Open Settings → Date & time and set it by hand instead?" y; then
      open_date_settings
    fi
  fi
}

# Put adbd back to the shell uid we found it on. Leaving it rooted would change
# what every later adb command in the session does -- pushes would land
# root-owned, for one -- and the script restores the other two device-wide
# things it touches (demo mode, radios) for the same reason.
drop_adb_root() {
  adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
  adb -s "$SERIAL" wait-for-device
}

toggle_network() {
  local dev
  if [[ "$NETWORK_OFF" == true ]]; then
    for dev in $(targets); do
      adb -s "$dev" shell svc wifi enable >/dev/null 2>&1 || true
      adb -s "$dev" shell svc data enable >/dev/null 2>&1 || true
    done
    NETWORK_OFF=false
    echo "==> Wi-Fi and mobile data back on."
    return 0
  fi
  if [[ "$SERIAL" == *_adb-tls-connect* || "$SERIAL" == *:* ]]; then
    echo "warn: this device is connected over wireless adb — cutting Wi-Fi will" >&2
    echo "      also cut the adb connection. Plug in over USB first." >&2
    prompt_yn "Do it anyway?" n || return 0
  fi
  for dev in $(targets); do
    adb -s "$dev" shell svc wifi disable >/dev/null 2>&1 || true
    adb -s "$dev" shell svc data disable >/dev/null 2>&1 || true
  done
  NETWORK_OFF=true
  echo "==> Wi-Fi and mobile data off, so no sync overwrites the fake timetable."
}

# Enter-to-capture. This is the whole point of the script; everything above
# is setup. Works the same for a phone and a watch, and is the only way to
# photograph the home screen widgets and the watch tile — those render in the
# launcher and the watch face carousel, not in the app.
capture_loop() {
  mkdir -p "$RUN_DIR"
  [[ -n "$WATCH_RUN_DIR" ]] && mkdir -p "$WATCH_RUN_DIR"
  echo ""
  echo "Capturing from $SERIAL into:"
  echo "  $RUN_DIR"
  if [[ -n "$WATCH" ]]; then
    echo "and from $WATCH into:"
    echo "  $WATCH_RUN_DIR"
    echo ""
    echo "One Enter shoots both, under the same number — pose the phone and the"
    echo "watch on the pages you want, then press it once."
  fi
  echo ""
  echo "  Enter        capture the current screen"
  echo "  <name>Enter  capture it under that name"
  echo "  q Enter      back to the menu"
  echo ""

  local n=0 name stem shot ok
  # Continue the numbering if this run already has shots in it.
  n="$(find "$RUN_DIR" -maxdepth 1 -name '*.png' | wc -l | tr -d ' ')"
  while true; do
    read -r -p "capture> " name || break
    [[ "$name" == "q" ]] && break
    n=$((n + 1))
    stem="$(printf '%02d' "$n")"
    [[ -n "$name" ]] && stem="$stem-$(echo "$name" | tr ' /' '--')"

    # A failure on either device rolls the number back, so the phone and the
    # watch never end up one shot out of step with each other.
    ok=true
    shot="$RUN_DIR/$stem.png"
    if adb -s "$SERIAL" exec-out screencap -p >"$shot" && [[ -s "$shot" ]]; then
      echo "    saved $(basename "$RUN_DIR")/$(basename "$shot")"
    else
      rm -f "$shot"; ok=false
      echo "    error: screencap failed on $SERIAL" >&2
    fi
    if [[ -n "$WATCH" ]]; then
      shot="$WATCH_RUN_DIR/$stem.png"
      if adb -s "$WATCH" exec-out screencap -p >"$shot" && [[ -s "$shot" ]]; then
        echo "    saved $(basename "$WATCH_RUN_DIR")/$(basename "$shot")"
      else
        rm -f "$shot"; ok=false
        echo "    error: screencap failed on $WATCH" >&2
      fi
    fi
    [[ "$ok" == true ]] || n=$((n - 1))
  done
}

# --------------------------------------------------------------------- menu

status_line() {
  local bits=("$PKG")
  [[ "$DEMO_ON" == true ]] && bits+=("demo-mode")
  [[ "$NETWORK_OFF" == true ]] && bits+=("offline")
  local IFS=", "
  echo "${bits[*]}"
}

require adb
choose_devices

while true; do
  echo ""
  echo "── $SERIAL${WATCH:+ + $WATCH} — $(status_line)"
  echo "  1) capture screenshots (Enter loop)"
  echo "  2) load fake data (timetable, assignments, bulletins, calendar, ID, QR)"
  echo "  3) clear fake data — demo mode off, real courses back"
  echo "  4) app clock — what the app thinks 'now' is"
  echo "  5) status bar clock — SystemUI demo mode $([[ "$DEMO_ON" == true ]] && echo '(on)')"
  echo "  6) real system time — needs su or a userdebug build$([[ "$CAN_SET_TIME" == true ]] || echo ' (not this one)')"
  echo "  7) network $([[ "$NETWORK_OFF" == true ]] && echo 'back on' || echo "off — device-wide; a demoMode fixture does this per-app")"
  echo "  8) build + install a debug APK"
  echo "  9) switch device"
  echo "  q) quit"
  read -r -p "> " choice || break
  case "$choice" in
    1) capture_loop ;;
    2) load_fixture ;;
    3) clear_fixture ;;
    4) set_app_clock ;;
    5) toggle_demo_mode ;;
    6) set_system_time ;;
    7) toggle_network ;;
    8) build_and_install ;;
    9) choose_devices ;;
    q|Q) break ;;
    *) echo "pick 1-9 or q" >&2 ;;
  esac
done

if [[ -n "$RUN_ROOT" && -d "$RUN_ROOT" ]]; then
  echo ""
  echo "==> Screenshots:"
  find "$RUN_ROOT" -name '*.png' | sort | sed 's|^|    |'
  echo ""
  echo "Promote the keepers into:"
  echo "    fastlane/metadata/android/<zh-TW|en-US>/images/<phone|sevenInch|tenInch>Screenshots/"
fi
