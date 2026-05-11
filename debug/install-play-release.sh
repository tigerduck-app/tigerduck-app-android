#!/usr/bin/env bash
# Build the Play release APK (:app:assemblePlayRelease) and adb install to a
# chosen phone. Optionally also build :wear:assembleRelease and install to a
# chosen watch. Useful for testing release-mode behavior (R8/ProGuard, signed
# config, etc.) without going through Play Store / Internal Testing.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

cd "$ROOT_DIR"

phone="$(pick_device phone)"

watch=""
if prompt_yn "Also install wear release to a paired watch?" n; then
  watch="$(pick_device watch)"
fi

if [[ -n "$watch" ]]; then
  echo "==> Building :app:assemblePlayRelease + :wear:assembleRelease"
  ./gradlew :app:assemblePlayRelease :wear:assembleRelease
else
  echo "==> Building :app:assemblePlayRelease"
  ./gradlew :app:assemblePlayRelease
fi

phone_apk="$(resolve_apk "$(module_outputs_dir app)/apk/play/release/app-play-release*.apk")"
echo "==> Installing $phone_apk → $phone"
adb_install "$phone" "org.ntust.app.tigerduck" "$phone_apk"

if [[ -n "$watch" ]]; then
  watch_apk="$(resolve_apk "$(module_outputs_dir wear)/apk/release/wear-release*.apk")"
  echo "==> Installing $watch_apk → $watch"
  adb_install "$watch" "org.ntust.app.tigerduck" "$watch_apk"
fi
