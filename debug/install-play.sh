#!/usr/bin/env bash
# Build :app:assemblePlayDebug and adb install to a chosen phone.
# Optionally also build :wear:assembleDebug and install to a chosen watch.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

cd "$ROOT_DIR"

phone="$(pick_device phone)"

watch=""
if prompt_yn "Also install wear debug to a paired watch?" n; then
  watch="$(pick_device watch)"
fi

if [[ -n "$watch" ]]; then
  echo "==> Building :app:assemblePlayDebug + :wear:assembleDebug"
  ./gradlew :app:assemblePlayDebug :wear:assembleDebug
else
  echo "==> Building :app:assemblePlayDebug"
  ./gradlew :app:assemblePlayDebug
fi

phone_apk="$(resolve_apk "$(module_outputs_dir app)/apk/play/debug/app-play-debug*.apk")"
echo "==> Installing $phone_apk → $phone"
adb_install "$phone" "org.ntust.app.tigerduck" "$phone_apk"

if [[ -n "$watch" ]]; then
  watch_apk="$(resolve_apk "$(module_outputs_dir wear)/apk/debug/wear-debug*.apk")"
  echo "==> Installing $watch_apk → $watch"
  adb_install "$watch" "org.ntust.app.tigerduck" "$watch_apk"
fi

# Phone and watch share the same applicationId (org.ntust.app.tigerduck) on play.
if [[ -n "$watch" ]]; then
  maybe_preset_clock "$phone" "org.ntust.app.tigerduck" "$watch" "org.ntust.app.tigerduck"
else
  maybe_preset_clock "$phone" "org.ntust.app.tigerduck"
fi
