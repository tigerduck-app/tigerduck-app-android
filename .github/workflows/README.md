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

Posts the target-branch checklist as a PR comment and reports how many boxes
are still unticked. Re-evaluates on PR edits and on comment activity.

The `pr-checklist` commit status is **informational only** — it is always set to
`success`, so it never blocks a merge. The real gate is the team-approval
ruleset on `dev` / `main`; the status description just saves reviewers from
expanding the bot comment to see whether the author ticked anything.

### `submodules-up-to-date.yaml`

Runs on PRs to `main` and `dev`. Verifies every git submodule (e.g.
`app-translation`) is pinned to its upstream tip, so PRs cannot land with stale
submodule references.

### `version-bumped.yaml`

Runs on PRs to `main`. Verifies the version has been bumped relative to the base
branch across all three version-carrying files, so a release tag cut from `main`
always carries a fresh version:

- `app/build.gradle.kts` — phone `versionCode` / `versionName`
- `wear/build.gradle.kts` — watch `versionCode` / `versionName`
- `metadata/org.ntust.app.tigerduck.fdroid.yml` — `Builds.versionCode`,
  `CurrentVersionCode`, `Builds.versionName`, `CurrentVersion`

It also enforces cross-file consistency, which is the part that bites:

- The F-Droid metadata `versionCode` must **equal** the Gradle one, and its
  `versionName` must be exactly `<gradle versionName>-fdroid`.
- The watch `versionName` must **match the phone exactly**, but its
  `versionCode` must be exactly **phone + 10000**.

That last rule is load-bearing to know about. `:wear` ships under the phone's
`applicationId`, and Play namespaces version codes per package rather than per
artifact, so the two bundles cannot share a code: the second upload is rejected
with "Version code N has already been used". The first 2.0 upload hit that with both
modules declaring 23. The offset keeps them distinct release after release, and
keeps the watch code the higher of the two — where a device matches both
artifacts Play serves the highest code, and only the watch bundle requires
`android.hardware.type.watch`.

The offset is additive, so it separates the two ranges only while the phone code
stays below it. The check asserts that too: a phone `versionCode` that reaches
10000 fails the build with a note to pick a new allocation scheme, rather than
silently reusing a code the watch already spent.

Note that uploading a bundle consumes its version code permanently; discarding
the draft release does not hand it back. 23 belongs to the watch bundle for
good, so 2.0.1 ships as phone 25 / watch 10025.

### `whatsnew-has-version.yaml`

Runs on PRs to `main`. Verifies `app/src/main/assets/whatsnew.json` has an entry
for the `versionCode` in `app/build.gradle.kts`, so the "What's New" dialog is
never empty on a fresh release.


