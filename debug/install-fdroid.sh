#!/usr/bin/env bash
# Build :app:assembleFdroidDebug and adb install to a chosen phone.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

cd "$ROOT_DIR"

phone="$(pick_device phone)"

echo "==> Building :app:assembleFdroidDebug"
./gradlew :app:assembleFdroidDebug

apk="$(resolve_apk "$(module_outputs_dir app)/apk/fdroid/debug/app-fdroid-debug*.apk")"

echo "==> Installing $apk → $phone"
adb_install "$phone" "org.ntust.app.tigerduck.fdroid" "$apk"

maybe_preset_clock "$phone" "org.ntust.app.tigerduck.fdroid"
