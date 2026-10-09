# GitHub Actions Workflows

## Release workflows

### `release.yaml` — Release (Auto)

Runs on every commit to `main`, or from the Actions tab. It releases the version
in `app/build.gradle.kts`, tagged `v<versionName>`. On a commit, if that tag
already exists the run stops after its first job, so only a commit that bumps
the version releases anything. The first job also refuses a new version whose
phone `versionCode` is not above the previous tag's, or whose watch
`versionCode` is not the phone's plus 10000: Play keeps every bundle it is sent,
and an unbumped code would publish the old binary under the new name.

A new version builds the same six artifacts as `release-manual.yaml`, then:

1. Uploads the phone and watch bundles to Google Play and sends them for review,
   all in one edit (`tools/play/publish.py`). The phone goes to `internal` and
   `production`, the watch to `wear:internal` and `wear:production`: Play has
   required Wear OS releases on their own form-factor tracks since 2023.
   Both reach everyone at once: every tester on internal, every user on
   production, unless a smaller production rollout is picked.
2. Tags the build commit (GPG-signed), pins the F-Droid metadata commit hash,
   and publishes the GitHub Release "TigerDuck Android vX.Y.Z" with every
   artifact attached.

Play comes before the tag so that a failed upload leaves nothing tagged and the
workflow can simply be run again. If a later step fails, use "Re-run failed
jobs": the upload skips any bundle Play already has, the tag step keeps a tag
already on the same commit, and the pin and the GitHub Release tolerate their
own earlier success. If Play refuses to send the release for review on its own
(it does after a rejection, or with other changes pending), the edit is still
saved and the run warns you to click "Send changes for review" in Play Console.

A commit releases to all four tracks, production to every user. From the
Actions tab you can tick which of phone/watch × internal/production to upload
and pick the rollout: 100% by default, or a staged 10, 20 or 50%. On a version
that is already tagged, a click sends it to the ticked tracks only, built from
the tag, and does not tag or publish again; that is how a release goes from
internal testing to production, or from a staged rollout to a wider one (which
then starts at the percentage picked, not where it was). Everything is built either way, because the GitHub Release
carries all six files and Play may not have a bundle yet.

Runs queue one at a time, and each decides what to release only once the run
before it has finished, so a commit landing mid-release finds the tag in place.
The F-Droid metadata commit a release pushes does not start a run at all. GitHub
keeps one pending run per queue, though: a click made while a release is running
is dropped if another commit lands on `main` before it starts. Click again once
the release is done.

On a tagged version the build comes from the tag, but `tools/play/publish.py`
comes from the commit the run started on, so tags made before the script
existed can still be sent to Play.

Release notes come from the `whatsnew.json` entry for the phone `versionCode`
(the in-app "What's New" text), as `zh-TW` and `en-US`. To word them
differently for a release, write
`fastlane/metadata/android/{zh-TW,en-US}/changelogs/<versionCode>.txt`; that
file wins, and F-Droid reads its changelogs from the same path. Play caps each
language at 500 characters. For a new version the first job checks that before
anything is built; a tagged version's notes are checked before the upload.
Preview them with `python3 tools/play/publish.py notes --phone-code N`.

Needs the `PLAY_SERVICE_ACCOUNT_JSON` secret, a service account with release
permissions on the app in Play Console.

### `release-manual.yaml` — Release (Manual)

Manually dispatched, no inputs. Takes the commit `main` points at when the run
starts, reads `versionName` from its `app/build.gradle.kts`, and releases it as
`v<versionName>`: tags that commit if the tag does not yet exist, builds signed
`play` and `fdroid` phone AABs/APKs plus the signed `:wear` AAB/APK, pins the
F-Droid metadata commit hash, and publishes the GitHub Release "TigerDuck
Android vX.Y.Z" with the artifacts attached. If the tag already exists, it
checks the tag out and attaches the artifacts to the existing release. Does
**not** upload to Google Play.

Both release workflows pin the F-Droid metadata only while the metadata on
`main` still describes the version being released. If a newer version bump
reached `main` during the build, the pin is left for that version's release.

Six artifacts: `TigerDuck.{apk,aab}` (play), `TigerDuck-fdroid.{apk,aab}`, and
`TigerDuck-Wear.{apk,aab}`. The watch is play-only — `:wear` has no product
flavors because it depends on `play-services-wearable`, so there is no F-Droid
watch build to ship. It is signed with the same keystore as the phone (same
`applicationId`), which the workflow decodes into `wear/keystore.jks` as well as
`app/keystore.jks`.

## PR check workflows

### `ci.yaml`

Runs on PRs to `main` and `dev`. Compiles both phone flavors plus `:wear`,
builds the `:wear` release AAB/APK unsigned, and runs every JVM unit test. Lean
on purpose: no emulator, no lint baseline.

The wear release build is there because `:wear` is minified with its own
`proguard-rules.pro` and nothing else in the PR gate runs R8 over it — without
this step a missing keep rule would surface for the first time on release day.
Unsigned because fork PRs cannot read the `KEYSTORE_*` secrets.

It also runs `tools/play/test_publish.py`, which tests the Play upload script
against a fake Play: which bundles are uploaded, what each track is sent, the
staged rollout, and the commit and its fallback. That script otherwise first
runs once the PR is already on `main`.

### `submodules-up-to-date.yaml`

Runs on PRs to `main` and `dev`. Verifies every git submodule (e.g.
`app-translation`) is pinned to its upstream tip, so PRs cannot land with stale
submodule references.

### `licenses-up-to-date.yaml`

Runs on PRs to `main` and `dev`. Regenerates the Open-source licences data from
each release variant's dependency graph and fails if it differs from what is
committed, so a dependency change cannot ship with a stale list. Two files per
flavor, plus the watch's pair on play, all written by the one export command:

- `res/raw/aboutlibraries.json` — the libraries and the licences they are
  published under, read from their POMs.
- `res/raw/bundled_notices.json` — what each artifact carries *inside* itself,
  which a POM-driven list cannot see: Apache-2.0 section 4(d) notices, the
  real copyright line for licences whose published text is the SPDX template
  (`<year> <copyright holders>`), and the licences of the third-party code
  compiled into Play Services and Firebase.
- `aboutlibraries_wear.json` and `bundled_notices_wear.json`, play only — the
  watch app's own dependencies. `:wear` declares `standalone = false`, so it
  never reaches a user without the phone app, and the phone's page lists them
  under a Wear OS heading rather than the watch carrying a page of its own.

Both are committed rather than generated during the build so that no build
reaches the network. `app/src/main/res/raw/extra_licenses.json` is
hand-maintained and not checked here — it covers material with no dependency
graph to compare against.

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
good, so 2.0.1 ships as phone 24 / watch 10024.

### `whatsnew-has-version.yaml`

Runs on PRs to `main`. Verifies `app/src/main/assets/whatsnew.json` has an entry
for the `versionCode` in `app/build.gradle.kts`, so the "What's New" sheet always
ends on a summary page on a fresh release.


