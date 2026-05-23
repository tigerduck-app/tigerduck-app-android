# Contributing

## Branching model

- Feature / fix branches → open a PR into `dev`.
- `dev` → open a PR into `main` when cutting a release.

## Pre-merge checklist (enforced)

When you open a PR into `dev` or `main`, the
[`PR Checklist Check`](.github/workflows/pr-checklist.yaml) workflow automatically posts a
checklist comment below your PR description.

- The exact checklist depends on whether the PR targets `dev` or `main`.
- You must tick **every** box in that comment before the `pr-checklist` status check turns green.
- The check re-evaluates every time the comment is edited, the PR is updated, or a new commit is
  pushed.

If the checklist comment is missing (e.g. on an older PR opened before this
workflow existed), push an empty commit or edit the PR body to retrigger the
workflow.

### Checklist contents

For reference, the contents posted by the bot are:

**Target: `dev`**

- I have tested on my phone (watch) / emulator that this branch does **not** break other functions
  it is not meant to touch.
- I have tested on my phone (watch) / emulator that this branch successfully fixes/adds the
  function(s) it is intended to fix/add.

**Target: `main`**

- I have compiled both the **fdroid** and **play** flavors using the scripts under
  [`debug/`](debug) (`install-fdroid.sh`, `install-play.sh`, `install-play-release.sh`) and
  confirmed they all pass compilation without any warnings.
- I have tested on my phone (watch) / emulator that no existing functions are broken.
- I have tested on my phone (watch) / emulator that upgrading from the previous version does not
  break the app.
- I am confident that users upgrading from much older versions (3 versions behind) are unlikely to
  experience breakage or crashes.

See [`debug/DEBUG.md`](debug/DEBUG.md) for details on the install scripts and build variants.

## One-time setup: require the check in branch protection

The workflow alone reports a status; it does not block merging. To actually
prevent merges while boxes are unchecked, add `pr-checklist` as a required
status check:

1. After this workflow has run at least once (so GitHub knows the status name exists), go to
   **Settings → Branches → Branch protection rules**.
2. Add or edit a rule for `dev`, then a rule for `main`.
3. Enable **Require status checks to pass before merging**.
4. In the search box, type `pr-checklist` and select it.
5. Save.

Repeat for both `dev` and `main`.
