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

# Preflight 1 — FCM config. Build still works without it: the google-services
# plugin in app/build.gradle.kts is gated on the JSON's presence, so missing
# only means push won't function at runtime.
if [[ ! -f app/google-services.json && ! -f app/src/play/google-services.json ]]; then
  echo "note: no google-services.json under app/ — google-services plugin will be skipped (FCM push won't work in this APK)."
fi

# Preflight 2 — signing env vars. The release signingConfig in
# app/build.gradle.kts only activates when KEYSTORE_PASSWORD is non-empty,
# otherwise AGP emits an unsigned APK that adb refuses to install.
generated_env=0
print_env_on_exit() {
  if (( generated_env )); then
    echo
    echo "==> Signing env vars used in this run:"
    printf '    KEYSTORE_PASSWORD=%s\n' "$KEYSTORE_PASSWORD"
    printf '    KEY_ALIAS=%s\n'         "$KEY_ALIAS"
    printf '    KEY_PASSWORD=%s\n'      "$KEY_PASSWORD"
    echo
    echo "To reuse them in future shells, run (or paste into ~/.zshrc):"
    printf '    export KEYSTORE_PASSWORD=%q\n' "$KEYSTORE_PASSWORD"
    printf '    export KEY_ALIAS=%q\n'         "$KEY_ALIAS"
    printf '    export KEY_PASSWORD=%q\n'      "$KEY_PASSWORD"
  fi
}

if [[ -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" ]]; then
  echo "note: one or more signing env vars (KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) are not set."
  if prompt_yn "Generate dev-only values and use them for this run?" y; then
    export KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-tigerduck-dev}"
    export KEY_ALIAS="${KEY_ALIAS:-tigerduck-dev}"
    export KEY_PASSWORD="${KEY_PASSWORD:-tigerduck-dev}"
    generated_env=1
    trap print_env_on_exit EXIT
  else
    echo "error: cannot sign the release APK without these vars. Aborting." >&2
    exit 1
  fi
fi

# Preflight 3 — keystore. Generated to match the env vars resolved above.
if [[ ! -f app/keystore.jks ]]; then
  echo "note: app/keystore.jks does not exist."
  if prompt_yn "Generate a local dev keystore (matching the env vars above)?" y; then
    require keytool
    keytool -genkeypair -v \
      -keystore app/keystore.jks \
      -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$KEYSTORE_PASSWORD" \
      -keypass "$KEY_PASSWORD" \
      -dname "CN=TigerDuck Dev, OU=Dev, O=NTUST, L=Taipei, S=Taipei, C=TW"
  else
    echo "error: cannot sign the release APK without a keystore. Aborting." >&2
    exit 1
  fi
fi

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
