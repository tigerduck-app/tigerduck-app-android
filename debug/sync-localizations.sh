#!/usr/bin/env bash
# Regenerate Android string resources from the app-translation submodule.
#
# The wear and app modules' `values-*/strings.xml` files are checked-in
# copies of app-translation/generated/android/values-*/strings.xml. They're
# normally only refreshed when you build with `-PsyncLocalizations`, so
# the committed copies drift when the submodule pointer moves.
#
# Run this after `git submodule update --remote app-translation` (or any
# change to app-translation sources) so the resources match before you
# `git commit` and push.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> Refreshing app + wear localizations from app-translation/generated/android"
./gradlew :app:copyGeneratedAndroidLocalizations :wear:copyGeneratedAndroidLocalizations

echo ""
echo "==> Changes:"
if git diff --quiet -- app/src/main/res wear/src/main/res; then
  echo "  (none — committed copies were already in sync)"
else
  git status --short -- app/src/main/res wear/src/main/res
  echo ""
  echo "Review with: git diff -- app/src/main/res wear/src/main/res"
  echo "Stage with:  git add app/src/main/res wear/src/main/res"
fi
