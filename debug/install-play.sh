#!/usr/bin/env bash
# Build :app:assemblePlayDebug and adb install it on the chosen phones.
# Optionally also build :wear:assembleDebug and install it on the chosen watches.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

cd "$ROOT_DIR"

choose_devices phone || exit 1
phones=("${CHOSEN[@]}")

watches=()
if prompt_yn "Also install wear debug on paired watches?" n; then
  choose_devices watch || exit 1
  watches=("${CHOSEN[@]}")
fi

if (( ${#watches[@]} )); then
  echo "==> Building :app:assemblePlayDebug + :wear:assembleDebug"
  ./gradlew :app:assemblePlayDebug :wear:assembleDebug
else
  echo "==> Building :app:assemblePlayDebug"
  ./gradlew :app:assemblePlayDebug
fi

phone_apk="$(resolve_apk "$(module_outputs_dir app)/apk/play/debug/app-play-debug*.apk")"
install_each "org.ntust.app.tigerduck" "$phone_apk" "${phones[@]}"

if (( ${#watches[@]} )); then
  watch_apk="$(resolve_apk "$(module_outputs_dir wear)/apk/debug/wear-debug*.apk")"
  # Phone and watch share the same applicationId (org.ntust.app.tigerduck) on play.
  install_each "org.ntust.app.tigerduck" "$watch_apk" "${watches[@]}"
fi

installed_everywhere=1
report_failures || installed_everywhere=0
maybe_preset_clock ${CLOCK_PAIRS[@]+"${CLOCK_PAIRS[@]}"}
(( installed_everywhere )) || exit 1
