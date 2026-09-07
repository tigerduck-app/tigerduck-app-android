# GitHub Actions Workflows

## Release workflows

### `release-manual.yaml` — Release (Manual) — **active**

Manually-dispatched release. Tags `main` (if the tag does not yet exist), builds
signed `play` and `fdroid` phone AABs/APKs plus the signed `:wear` AAB/APK, pins
the F-Droid metadata commit hash, and publishes a GitHub Release with the
artifacts attached. Does **not** upload to Google Play.

Six artifacts: `TigerDuck.{apk,aab}` (play), `TigerDuck-fdroid.{apk,aab}`, and
`TigerDuck-Wear.{apk,aab}`. The watch is play-only — `:wear` has no product
flavors because it depends on `play-services-wearable`, so there is no F-Droid
watch build to ship. It is signed with the same keystore as the phone (same
`applicationId`), which the workflow decodes into `wear/keystore.jks` as well as
`app/keystore.jks`.

Inputs:

- `tag` — e.g. `v1.2.3`. Created on `main` if it does not already exist; if it
  exists, the existing tag is checked out and artifacts are attached to the
  existing release.

This is the workflow currently used to cut releases.

### `release.yaml` — Release (Auto) — **suspended**

Triggered automatically on `v*` tag push. Builds signed artifacts, creates a
GitHub Release with auto-generated notes, and uploads the play-flavor AAB to the
Play Store production track at 10% staged rollout.

Currently suspended — do not rely on it. Use `release-manual.yaml` instead.

### `release-manual-playstore.yaml` — Release (Manual + Play Store) — **suspended**

Manual dispatch by tag. Same as `release.yaml` but triggered manually: validates
`vX.Y.Z` tag format, checks out the tag, builds, creates the GitHub Release, and
pushes to the Play Store production track at 10% staged rollout.

Currently suspended.

### `release-manual-playstore-internal.yaml` — Release (Manual + Play Store (Internal)) — *
*suspended**

Manual dispatch by tag. Same as the production variant but uploads to the Play
Store **internal** track with `status: completed` (no staged rollout). Accepts
prerelease tags (e.g. `v1.2.3-beta.1`).

Currently suspended.

## PR check workflows

### `ci.yaml`

Runs on PRs to `main` and `dev`. Compiles both phone flavors plus `:wear`,
builds the `:wear` release AAB/APK unsigned, and runs every JVM unit test. Lean
on purpose: no emulator, no lint baseline.

The wear release build is there because `:wear` is minified with its own
`proguard-rules.pro` and nothing else in the PR gate runs R8 over it — without
this step a missing keep rule would surface for the first time on release day.
Unsigned because fork PRs cannot read the `KEYSTORE_*` secrets.

### `pr-checklist.yaml`

Posts the target-branch checklist as a PR comment and holds a commit status
until every box is ticked. Re-evaluates on PR edits and on comment activity.

### `submodules-up-to-date.yaml`

Runs on PRs to `main` and `dev`. Verifies every git submodule (e.g.
`app-translation`) is pinned to its upstream tip, so PRs cannot land with stale
submodule references.

### `version-bumped.yaml`

Runs on PRs to `main`. Verifies `versionCode` and `versionName` have been bumped
relative to the base branch, so a release tag cut from `main` always carries a
fresh version.

### `whatsnew-has-version.yaml`

Runs on PRs to `main`. Verifies `app/src/main/assets/whatsnew.json` has an entry
for the `versionCode` in `app/build.gradle.kts`, so the "What's New" dialog is
never empty on a fresh release.


