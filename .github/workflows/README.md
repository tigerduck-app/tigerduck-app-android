# GitHub Actions Workflows

## Release workflows

### `release-manual.yaml` — Release (Manual) — **active**

Manually-dispatched release. Tags `main` (if the tag does not yet exist), builds
signed `play` and `fdroid` AABs/APKs, pins the F-Droid metadata commit hash, and
publishes a GitHub Release with the artifacts attached. Does **not** upload to
Google Play.

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

### `release-manual-playstore-internal.yaml` — Release (Manual + Play Store (Internal)) — **suspended**

Manual dispatch by tag. Same as the production variant but uploads to the Play
Store **internal** track with `status: completed` (no staged rollout). Accepts
prerelease tags (e.g. `v1.2.3-beta.1`).

Currently suspended.

## PR check workflows

### `submodules-up-to-date.yaml`

Runs on PRs to `main` and `dev`. Verifies every git submodule (e.g.
`app-translation`) is pinned to its upstream tip, so PRs cannot land with stale
submodule references.

### `version-bumped.yaml`

Runs on PRs to `main`. Verifies `versionCode` and `versionName` have been bumped
relative to the base branch, so a release tag cut from `main` always carries a
fresh version.
