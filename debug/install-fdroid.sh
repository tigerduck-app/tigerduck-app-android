#!/usr/bin/env bash
# Build :app:assembleFdroidDebug and adb install it on the chosen phones.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

cd "$ROOT_DIR"

choose_devices phone || exit 1
phones=("${CHOSEN[@]}")

echo "==> Building :app:assembleFdroidDebug"
./gradlew :app:assembleFdroidDebug

apk="$(resolve_apk "$(module_outputs_dir app)/apk/fdroid/debug/app-fdroid-debug*.apk")"

install_each "org.ntust.app.tigerduck.fdroid" "$apk" "${phones[@]}"

installed_everywhere=1
report_failures || installed_everywhere=0
maybe_preset_clock ${CLOCK_PAIRS[@]+"${CLOCK_PAIRS[@]}"}
(( installed_everywhere )) || exit 1
