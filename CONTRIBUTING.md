# Contributing

## Branching model

- Feature / fix branches → open a PR into `dev`.
- `dev` → open a PR into `main` when cutting a release.

## Before merging

Make sure each of these holds before a PR is merged. Nothing checks them automatically; reviewers
rely on the author having done them.

**Into `dev`**

- I have tested on my phone (watch) / emulator that this branch does **not** break other functions
  it is not meant to touch.
- I have tested on my phone (watch) / emulator that this branch successfully fixes/adds the
  function(s) it is intended to fix/add.

**Into `main`**

- I have compiled both the **fdroid** and **play** flavors using the scripts under
  [`debug/`](debug) (`install-fdroid.sh`, `install-play.sh`, `install-play-release.sh`) and
  confirmed they all pass compilation without any warnings.
- I have tested on my phone (watch) / emulator that no existing functions are broken.
- I have tested on my phone (watch) / emulator that upgrading from the previous version does not
  break the app.
- I am confident that users upgrading from much older versions (3 versions behind) are unlikely to
  experience breakage or crashes.

See [`debug/DEBUG.md`](debug/DEBUG.md) for details on the install scripts and build variants.
