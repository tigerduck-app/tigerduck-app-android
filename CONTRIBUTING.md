# Contributing

## Branching model

- Feature / fix branches → open a PR into `dev`.
- `dev` → open a PR into `main` when cutting a release.

## Before merging

Make sure each of these holds before a PR is merged. Nothing checks them automatically; reviewers
rely on the author having done them.

**Any PR**

- Before opening it, I talked the change over with @SamWang8891 and @xinshoutw in #devs-chat on
  the TigerDuck Discord (https://discord.gg/Mxb344qKRu), and they know the plan. A pull request
  nobody heard about first may be closed.
- I have **not** edited anything inside `app-translation/`. A string change goes through a pull
  request in [app-translation](https://github.com/tigerduck-app/app-translation) and follows that
  repo's rules
  ([CONVENTIONS.md](https://github.com/tigerduck-app/app-translation/blob/main/CONVENTIONS.md)).
- If this PR needs strings from such a pull request, it pins `app-translation` at that pull
  request's head commit and links it in the PR body. CI fetches the submodule from
  tigerduck-app/app-translation, where a pull request's head can always be fetched and a commit
  only on a fork may not. A core developer merges the app-translation pull request first, moves
  the pin to its `main`, then merges this PR.
- I'm aware that if I failed to reply to the core devs' comments in 3 days, this PR may be handled by the core devs.
- Don't update dependencies and gradle / gradle plugins unless necessary. 
- You should fix any build warnings caused by your changes.
- If require a [TigerSync](https://github.com/tigerduck-app/tigerduck-backend) (the backend) change, open the PR there also and mention it in this PR.

**Into `dev`**

- I have tested on my phone (watch) / emulator that this branch does **not** break other functions
  it is not meant to touch.
- I have tested on my phone (watch) / emulator that this branch successfully fixes/adds the
  function(s) it is intended to fix/add.
- I have **not** changed the app's version (`versionCode` or `versionName`).

**Into `main`**

- Only core developers open pull requests into `main`.
- I have compiled both the **fdroid** and **play** flavors using the scripts under
  [`debug/`](debug) (`install-fdroid.sh`, `install-play.sh`, `install-play-release.sh`) and
  confirmed they all pass compilation without any warnings.
- I have tested on my phone (watch) / emulator that no existing functions are broken.
- I have tested on my phone (watch) / emulator that upgrading from the previous version does not
  break the app.
- Check if update app's any policy (privacy policy, terms of service, etc.) is required. If yes, update them in the app and website.
- Update (wiki)[https://github.com/tigerduck-app/wiki] first.

See [`debug/DEBUG.md`](debug/DEBUG.md) for details on the installation scripts and build variants.
