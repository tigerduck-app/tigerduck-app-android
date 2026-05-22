#!/usr/bin/env bash
# Shared helpers for debug/install-*.sh. Source, don't execute.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Local non-CI builds redirect output to ~/.tigerduck-build/<module>/ — see
# build.gradle.kts. CI / F-Droid keep the standard <module>/build/.
LOCAL_BUILD_ROOT="$HOME/.tigerduck-build"

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
  "$@" &
  local cmd_pid=$!
  ( sleep "$secs"; kill -TERM "$cmd_pid" 2>/dev/null ) &
  local watch_pid=$!
  local rc=0
  wait "$cmd_pid" 2>/dev/null || rc=$?
  if kill -0 "$watch_pid" 2>/dev/null; then
    # Command finished on its own — cancel the watchdog.
    kill -TERM "$watch_pid" 2>/dev/null || true
    wait "$watch_pid" 2>/dev/null || true
  else
    # Watchdog already exited → it fired and killed the command.
    rc=124
  fi
  return "$rc"
}

# Print a serial for an interactive device pick. Lists every connected adb
# device (phones, watches, emulators) and lets the user choose. If exactly
# one is connected, returns it without prompting. Optional first arg is a
# label used in prompts/errors ("phone", "watch", etc.) — display only,
# does not filter.
pick_device() {
  local label="${1:-device}"

  require adb

  local serials=() labels=()
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
    [[ "$chars" == *watch* ]] && tag="$tag [watch]"
    [[ "$serial" == emulator-* ]] && tag="$tag [emulator]"
    serials+=("$serial")
    labels+=("$serial  ${model:-?}$tag")
  done 3< <(adb devices)

  if [[ ${#serials[@]} -eq 0 ]]; then
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
