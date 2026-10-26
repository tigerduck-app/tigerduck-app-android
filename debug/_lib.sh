#!/usr/bin/env bash
# Shared helpers for debug/install-*.sh. Source, don't execute.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Local non-CI builds redirect output to
# ~/.tigerduck-build/checkouts/<checkout>/<module>/, <checkout> being this
# checkout's path below $HOME, or its whole path less the leading / outside
# it — see build.gradle.kts. CI / F-Droid keep the standard <module>/build/.
CHECKOUT_DIR="$(cd "$ROOT_DIR" && pwd -P)"
HOME_DIR="$(cd "$HOME" && pwd -P)"
case "$CHECKOUT_DIR" in
  "$HOME_DIR"/*) CHECKOUT_KEY="${CHECKOUT_DIR#"$HOME_DIR"/}" ;;
  *) CHECKOUT_KEY="${CHECKOUT_DIR#/}" ;;
esac
LOCAL_BUILD_ROOT="$HOME/.tigerduck-build/checkouts/$CHECKOUT_KEY"

# Filled by choose_devices and install_each.
CHOSEN=()
MENU_PICKS=""
CLOCK_PAIRS=()
FAILED_ON=()

# Echo the absolute path to a module's outputs/ dir, accounting for the local
# redirect. Usage: module_outputs_dir app  → /…/app/outputs (or app/build/outputs)
module_outputs_dir() {
  local module="$1"
  if [[ -d "$LOCAL_BUILD_ROOT/$module/outputs" ]]; then
    echo "$LOCAL_BUILD_ROOT/$module/outputs"
  else
    echo "$ROOT_DIR/$module/build/outputs"
  fi
}

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: '$1' not found on PATH" >&2
    exit 1
  }
}

# Run "$@", killing it after <secs> seconds. Returns the command's own exit
# status, or 124 if it had to be killed for overrunning. Prefers GNU
# `timeout` / `gtimeout`; falls back to a background-process watchdog because
# macOS ships no `timeout`.
run_with_timeout() {
  local secs="$1"; shift
  local tool
  if tool="$(command -v timeout || command -v gtimeout || true)" && [[ -n "$tool" ]]; then
    "$tool" "$secs" "$@"
    return $?
  fi
  # The watchdog records that it fired (non-empty marker) before killing the
  # command. A timeout is then detected from that fact — not inferred from the
  # watchdog no longer being alive, which cannot tell "I killed it" apart from
  # "I expired just as the command finished on its own".
  local marker
  marker="$(mktemp "${TMPDIR:-/tmp}/rwt.XXXXXX")"
  "$@" &
  local cmd_pid=$!
  ( sleep "$secs"; printf fired >"$marker"; kill -TERM "$cmd_pid" 2>/dev/null ) &
  local watch_pid=$!
  local rc=0
  wait "$cmd_pid" 2>/dev/null || rc=$?
  # Stop the watchdog the instant the command is done: if it was still
  # sleeping it never fires and the marker stays empty; if it already fired
  # the marker is non-empty and this kill is a harmless no-op.
  kill -TERM "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
  [[ -s "$marker" ]] && rc=124
  rm -f "$marker"
  return "$rc"
}

# Fill DEVICE_SERIALS and DEVICE_LABELS with the connected adb devices. With
# kind "phone" or "watch" it keeps the devices whose ro.build.characteristics
# say so, and any that didn't answer, since those could be either; with any
# other kind it keeps them all.
_list_devices() {
  local kind="$1"
  DEVICE_SERIALS=()
  DEVICE_LABELS=()

  require adb

  local line
  # Read via fd 3 so the inner `adb shell` calls don't drain the device list
  # (adb shell inherits stdin and would consume the remaining lines, leaving
  # us with only the first device — and a silent auto-pick).
  while IFS= read -r line <&3; do
    # The state column ("device") is always the last whitespace-separated
    # field; the serial is everything before it. A greedy (.+) is required
    # because mDNS-discovered wireless devices report a serial that itself
    # contains a space — e.g. "adb-SERIAL (2)._adb-tls-connect._tcp", where
    # " (2)" is Bonjour's duplicate-name disambiguator. A [^space]+ token
    # would split on that space and fail to match, silently dropping the
    # device.
    [[ "$line" =~ ^(.+)[[:space:]]+device([[:space:]].*)?$ ]] || continue
    local serial="${BASH_REMATCH[1]}"
    # Probe model + form factor in one timed `adb shell` round-trip. A stale
    # transport (e.g. a dropped wireless connection still listed as `device`)
    # makes `adb shell` hang forever, which would freeze the whole picker — so
    # cap it and tag the device [unresponsive] instead of blocking.
    local chars="" model="" tag="" probe
    if probe="$(run_with_timeout 5 adb -s "$serial" shell \
        'getprop ro.build.characteristics; getprop ro.product.model' \
        </dev/null 2>/dev/null)"; then
      probe="${probe//$'\r'/}"
      { IFS= read -r chars; IFS= read -r model; } <<< "$probe" || true
    else
      tag=" [unresponsive]"
    fi
    case "$kind" in
      phone) [[ "$chars" == *watch* ]] && continue ;;
      watch) [[ -n "$chars" && "$chars" != *watch* ]] && continue ;;
    esac
    [[ "$chars" == *watch* ]] && tag="$tag [watch]"
    [[ "$serial" == emulator-* ]] && tag="$tag [emulator]"
    DEVICE_SERIALS+=("$serial")
    DEVICE_LABELS+=("$serial  ${model:-?}$tag")
  done 3< <(adb devices)
}

# Print a serial for an interactive device pick. Lists every connected adb
# device (phones, watches, emulators) and lets the user choose. If exactly
# one is connected, returns it without prompting. Optional first arg is a
# label used in prompts/errors ("phone", "watch", etc.) — display only,
# does not filter.
pick_device() {
  local label="${1:-device}"

  _list_devices all
  local serials=("${DEVICE_SERIALS[@]+"${DEVICE_SERIALS[@]}"}")
  local labels=("${DEVICE_LABELS[@]+"${DEVICE_LABELS[@]}"}")

  if [[ ${#DEVICE_SERIALS[@]} -eq 0 ]]; then
    echo "error: no connected adb $label found. Run 'adb devices' to check." >&2
    return 1
  fi
  if [[ ${#serials[@]} -eq 1 ]]; then
    echo "${serials[0]}"
    return 0
  fi

  echo "Pick $label:" >&2
  local i
  for i in "${!labels[@]}"; do
    echo "  [$i] ${labels[$i]}" >&2
  done
  local choice
  while true; do
    read -r -p "> " choice
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 0 && choice < ${#serials[@]} )); then
      echo "${serials[$choice]}"
      return 0
    fi
    echo "invalid choice; enter a number 0..$((${#serials[@]} - 1))" >&2
  done
}

# Let the user pick one or more of the connected devices of <kind> ("phone" or
# "watch"), and leave their serials in CHOSEN. One matching device is chosen
# without asking. Otherwise a menu on the terminal: ↑/↓ or k/j move, space
# ticks, a ticks all (or clears them), Enter takes the ticked ones — or the
# highlighted one when none is ticked — and q cancels. Without a terminal it
# asks for numbers instead. Returns 1 when nothing was chosen.
choose_devices() {
  local kind="$1"
  CHOSEN=()

  _list_devices "$kind"
  local n=${#DEVICE_SERIALS[@]}
  if (( n == 0 )); then
    echo "error: no connected adb $kind found. Run 'adb devices' to check." >&2
    return 1
  fi
  if (( n == 1 )); then
    CHOSEN=("${DEVICE_SERIALS[0]}")
    echo "==> Only one $kind connected: ${DEVICE_LABELS[0]}" >&2
    return 0
  fi

  local picks
  if { : >/dev/tty; } 2>/dev/null; then
    # In this shell, not a $(...): a Ctrl-C trap in a subshell can't put
    # echo back on bash 3.2.
    _tick_menu "Install on which ${kind}s?" "${DEVICE_LABELS[@]}" </dev/tty 2>/dev/tty || return 1
    picks="$MENU_PICKS"
  else
    picks="$(_number_menu "Install on which ${kind}s?" "${DEVICE_LABELS[@]}")" || return 1
  fi
  local i
  for i in $picks; do
    CHOSEN+=("${DEVICE_SERIALS[$i]}")
  done
  (( ${#CHOSEN[@]} > 0 ))
}

# The menu choose_devices draws: <title>, then one row per item. Reads keys
# from stdin and draws on stderr, both the terminal; leaves the picked
# indexes in MENU_PICKS, space-separated. Returns 1 when cancelled.
_tick_menu() {
  local title="$1"; shift
  local items=("$@")
  local n=${#items[@]} cur=0 i key rest
  local ticked=()
  for ((i = 0; i < n; i++)); do ticked[i]=0; done

  # Echo is left on between keys: `read -s` turns it off for each key, and
  # an interrupted read puts back what it found, so "off" set here would
  # outlive a Ctrl-C. The trap restores the settings from before the menu,
  # along with the cursor, and ends the script as a Ctrl-C would.
  local saved_stty
  saved_stty="$(stty -g 2>/dev/null)" || saved_stty=""
  trap '[[ -n "$saved_stty" ]] && stty "$saved_stty" 2>/dev/null; printf "\e[?25h" >&2; exit 130' INT TERM
  printf '\e[?25l' >&2

  local hint="(space: tick · a: all · Enter: install · q: cancel)"
  local drawn=0 cols row
  # Each row is cut to the terminal's width: the redraw climbs one line per
  # row, and a row that wrapped would leave its tail behind.
  _draw() {
    cols="$(stty size 2>/dev/null | awk '{print $2}')"
    [[ "$cols" =~ ^[0-9]+$ ]] && (( cols > 10 )) || cols=80
    (( drawn )) && printf '\e[%dA' $((n + 1)) >&2
    row="$title  $hint"
    (( ${#row} < cols )) || row="$title"
    printf '\r\e[2K%s\n' "${row:0:cols-1}" >&2
    for ((i = 0; i < n; i++)); do
      local box="[ ]"
      (( ticked[i] )) && box="[x]"
      if (( i == cur )); then
        row="> $box ${items[i]}"
        printf '\r\e[2K\e[7m%s\e[0m\n' "${row:0:cols-1}" >&2
      else
        row="  $box ${items[i]}"
        printf '\r\e[2K%s\n' "${row:0:cols-1}" >&2
      fi
    done
    drawn=1
  }

  local done=0 cancelled=0
  while (( ! done )); do
    _draw
    IFS= read -rsn1 key || { cancelled=1; break; }
    case "$key" in
      $'\e')
        rest=""
        IFS= read -rsn2 -t 1 rest || true
        case "$rest" in
          '[A'|'OA') cur=$(( (cur + n - 1) % n )) ;;
          '[B'|'OB') cur=$(( (cur + 1) % n )) ;;
          '') cancelled=1; done=1 ;;
        esac
        ;;
      k) cur=$(( (cur + n - 1) % n )) ;;
      j) cur=$(( (cur + 1) % n )) ;;
      ' ') ticked[cur]=$(( ! ticked[cur] )) ;;
      a|A)
        local all=1
        for ((i = 0; i < n; i++)); do (( ticked[i] )) || all=0; done
        for ((i = 0; i < n; i++)); do ticked[i]=$(( ! all )); done
        ;;
      q|Q) cancelled=1; done=1 ;;
      '') done=1 ;;
    esac
  done

  printf '\e[?25h' >&2
  trap - INT TERM

  if (( cancelled )); then
    echo "Cancelled." >&2
    return 1
  fi
  local picks=()
  for ((i = 0; i < n; i++)); do
    if (( ticked[i] )); then picks+=("$i"); fi
  done
  (( ${#picks[@]} )) || picks=("$cur")
  MENU_PICKS="${picks[*]}"
}

# choose_devices' fallback with no terminal: numbers from stdin, several at
# once ("0 2"), or "a" for all. Prints the picked indexes, space-separated.
_number_menu() {
  local title="$1"; shift
  local items=("$@") n=$# i choice
  echo "$title" >&2
  for ((i = 0; i < n; i++)); do echo "  [$i] ${items[i]}" >&2; done
  while true; do
    read -r -p "Numbers, space-separated, or a for all > " choice || return 1
    choice="${choice//,/ }"
    if [[ "$choice" =~ ^[[:space:]]*[aA][[:space:]]*$ ]]; then
      seq -s ' ' 0 $((n - 1))
      return 0
    fi
    local ok=1 c
    [[ -n "${choice// /}" ]] || ok=0
    for c in $choice; do
      [[ "$c" =~ ^[0-9]+$ ]] && (( c < n )) || ok=0
    done
    if (( ok )); then
      echo "$choice"
      return 0
    fi
    echo "invalid choice; enter numbers 0..$((n - 1))" >&2
  done
}

# adb_install the APK on each serial in turn, going on past a failure. Each
# serial installed on is added to CLOCK_PAIRS with <pkg>, for
# maybe_preset_clock; each that failed, to FAILED_ON.
install_each() {
  local pkg="$1" apk="$2"; shift 2
  local serial
  for serial in "$@"; do
    echo "==> Installing $apk → $serial"
    if adb_install "$serial" "$pkg" "$apk"; then
      CLOCK_PAIRS+=("$serial" "$pkg")
    else
      FAILED_ON+=("$serial")
    fi
  done
}

# Name the devices an install_each install failed on, if any, and return 1
# when there were some. Called before the clock question, so a failure there
# can't hide which devices are missing the build.
report_failures() {
  (( ${#FAILED_ON[@]} )) || return 0
  echo "error: install failed on:" >&2
  printf '  %s\n' "${FAILED_ON[@]}" >&2
  return 1
}

# Interactive: ask whether to pre-set the debug clock override and push the
# resulting prefs to one or more device/package pairs. Args are pairs:
#   maybe_preset_clock <serial1> <pkg1> [<serial2> <pkg2> ...]
# If the user declines, returns 0 with no side effects.
maybe_preset_clock() {
  if (( $# == 0 )); then return 0; fi

  if ! prompt_yn "Pre-set the debug clock override now? (otherwise: open Settings → Developer in the app)" n; then
    return 0
  fi

  local default_date default_time
  default_date="$(date +%Y-%m-%d)"
  default_time="$(date +%H:%M)"

  local d t
  read -r -p "Date (YYYY-MM-DD) [$default_date]: " d || true
  d="${d:-$default_date}"
  read -r -p "Time (HH:MM)     [$default_time]: " t || true
  t="${t:-$default_time}"

  local frozen="true"
  if ! prompt_yn "Frozen mode? (no = ticking — clock advances from this instant)" y; then
    frozen="false"
  fi

  require python3
  local millis saved
  millis="$(python3 -c "
import datetime, sys
from zoneinfo import ZoneInfo
y,m,d = '$d'.split('-')
hh,mm = '$t'.split(':')
dt = datetime.datetime(int(y), int(m), int(d), int(hh), int(mm), tzinfo=ZoneInfo('Asia/Taipei'))
print(int(dt.timestamp() * 1000))
")" || { echo "error: failed to parse date/time" >&2; return 1; }
  saved="$(python3 -c 'import time; print(int(time.time()*1000))')"

  local tmp
  # Pass a full template path rather than `mktemp -t PREFIX`: GNU and BSD
  # (macOS) mktemp interpret `-t` differently, but both accept a template
  # operand ending in XXXXXX.
  tmp="$(mktemp "${TMPDIR:-/tmp}/debug_clock.XXXXXX")"
  cat >"$tmp" <<XML
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <long name="instant_millis" value="$millis" />
    <boolean name="frozen" value="$frozen" />
    <long name="saved_at_real_millis" value="$saved" />
</map>
XML

  while (( $# >= 2 )); do
    local serial="$1" pkg="$2"; shift 2
    echo "==> Writing debug_clock.xml to $pkg on $serial"
    adb -s "$serial" shell "am force-stop $pkg" >/dev/null
    if ! adb -s "$serial" shell "run-as $pkg mkdir -p shared_prefs" 2>/dev/null; then
      echo "warn: run-as $pkg failed on $serial — package not debuggable or not installed" >&2
      continue
    fi
    # Push to /data/local/tmp first, then run-as cat into the app's prefs dir.
    # Direct stdin → run-as has quoting issues across adb shell; the staged
    # copy avoids them.
    adb -s "$serial" push "$tmp" /data/local/tmp/debug_clock.xml >/dev/null
    adb -s "$serial" shell "run-as $pkg sh -c 'cat /data/local/tmp/debug_clock.xml > shared_prefs/debug_clock.xml'"
    adb -s "$serial" shell "rm /data/local/tmp/debug_clock.xml" >/dev/null
  done

  rm -f "$tmp"

  echo "==> Override pre-set: $d $t Taipei (frozen=$frozen). Launch the app to see it active."
}

# adb install with retry-after-uninstall on signature mismatch.
# Usage: adb_install <serial> <pkg> <apk>
# On INSTALL_FAILED_UPDATE_INCOMPATIBLE (different signing key — common when
# switching between debug/release or play/fdroid builds on the same device),
# prompt the user to uninstall the package and try again.
adb_install() {
  local serial="$1" pkg="$2" apk="$3"
  local out rc
  set +e
  out="$(adb -s "$serial" install -r -d "$apk" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$out"
  if (( rc == 0 )) && [[ "$out" != *"Failure"* ]]; then
    return 0
  fi
  if [[ "$out" == *"signatures do not match"* || "$out" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    if prompt_yn "Signature mismatch on $serial. Uninstall $pkg and reinstall? (user data will be wiped)" n; then
      adb -s "$serial" uninstall "$pkg"
      adb -s "$serial" install -r -d "$apk"
      return $?
    fi
  fi
  return "$(( rc == 0 ? 1 : rc ))"
}

prompt_yn() {
  local q="$1" default="${2:-n}" reply p
  case "$default" in
    y|Y) p="$q [Y/n] " ;;
    *)   p="$q [y/N] " ;;
  esac
  read -r -p "$p" reply || true
  reply="${reply:-$default}"
  [[ "$reply" =~ ^[Yy] ]]
}

# Resolve a single APK path from a glob pattern. Errors if zero match. If
# multiple match, prefers names containing "debugsigned" (a signed release
# variant adb can install) over "unsigned" (cannot be installed); errors if
# the tie-break still leaves more than one.
resolve_apk() {
  local pattern="$1"
  local matches=( $pattern )
  if [[ ${#matches[@]} -eq 0 || ! -f "${matches[0]}" ]]; then
    echo "error: no APK matched $pattern" >&2
    return 1
  fi
  if [[ ${#matches[@]} -gt 1 ]]; then
    local signed=()
    local m
    for m in "${matches[@]}"; do
      [[ "$m" == *unsigned* ]] && continue
      signed+=("$m")
    done
    if [[ ${#signed[@]} -eq 1 ]]; then
      echo "${signed[0]}"
      return 0
    fi
    echo "error: multiple APKs matched $pattern after dropping unsigned:" >&2
    printf '  %s\n' "${matches[@]}" >&2
    return 1
  fi
  echo "${matches[0]}"
}
