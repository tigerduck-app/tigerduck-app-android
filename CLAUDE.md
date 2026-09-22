# TigerDuck Android — Agent Notes

Multi-module Gradle project: `:app` (phone), `:wear` (watch OS), `:shared`
(domain models reused on both). Phone has `play` and `fdroid` flavors;
`:wear` is play-only. Localization strings come from the `app-translation/`
submodule and are generated via `tools/localization/sync_localizations.py`
(the `syncLocalizations` Gradle task), then copied into `app/src/main/res` by
`copyGeneratedAndroidLocalizations`, which depends on it.

For human-facing contributor guidance see `CONTRIBUTING.md`. This file
exists to surface project-specific invariants AI agents have to know
that aren't obvious from reading the code.

## Upgrade-safe persistence (READ THIS BEFORE TOUCHING DATA MODELS)

This codebase has shipped **two upgrade-crash incidents** caused by the
same root pattern. The third one is the one you almost write. Read the
upgrade-safe-persistence skill (`.claude/skills/upgrade-safe-persistence/`)
before modifying any of:

- `shared/src/main/java/org/ntust/app/tigerduck/shared/Course.kt`
- `app/src/main/java/org/ntust/app/tigerduck/data/cache/DataCache.kt`
- `app/src/main/java/org/ntust/app/tigerduck/data/DataMigration.kt`
- `app/src/play/java/org/ntust/app/tigerduck/wear/WearScheduleBridge.kt`
- `wear/src/main/java/org/ntust/app/tigerduck/wear/data/SchedulePersistence.kt`
- `app/proguard-rules.pro` (R8 keep list)
- Anything under `network/model/**`, `data/model/**` (both are wildcard-
  kept by R8 — includes Bulletin DTOs and WhatsNewContent)

**The one-line rule:** any new field added to a Gson-deserialized data
class persisted across upgrades must be **nullable** (`String?`), a
**primitive** (`Int`/`Boolean`), or accompanied by a `DataMigration` step
that rewrites old cache files. **Non-null Kotlin types with default
values do NOT survive Gson's `Unsafe.allocateInstance` path** — the
default is silently dropped and the field is null at runtime.

**Past incidents:**

- **v1.3.x → v1.4.0**: `Course` moved to `:shared`, R8 renamed its
  fields (no keep rule), Gson read cache JSON with un-matching keys →
  all fields null → `CourseDto.<init>` NPE in `wearBridge.publish()`
  from `TigerDuckApp.onCreate`. Hotfix v1.4.1 added the R8 keep rule,
  the `"courseNo":` token sentinel in `DataCache.load`, and
  `DataMigration` step `1 → 2` to sweep obfuscated caches.
- **v1.4.1 → v1.4.2 (caught pre-ship)**: added
  `Course.classroomMapJson: String = "{}"` (non-null). v1.4.1 caches
  lack the key → Gson Unsafe path → field null → same NPE shape via
  `WearScheduleBridge.toDto`. Fixed by making the field nullable and
  coalescing at the `toDto` call site.

## Other agent-relevant invariants

- **Flavor split is load-bearing.** `play` uses Firebase + Google Play
  Services; `fdroid` cannot. Anything FCM / GMS-flavored lives under
  `app/src/play/`. Don't sprinkle Play-Services imports into `main/`.
- **Localization strings are generated** from the `app-translation/` submodule.
  Edit the JSON in the submodule, not `app/src/main/res/values*/strings.xml`
  (those are regenerated and would be clobbered). Run
  `:app:copyGeneratedAndroidLocalizations` to refresh — that is the task that
  writes `app/src/main/res`. `:app:syncLocalizations` on its own only runs the
  submodule's generator into `app-translation/generated/android`, so calling it
  alone finishes green while leaving `app/src/main/res` stale. `:wear` has its
  own, separate `:wear:copyGeneratedAndroidLocalizations` task that writes
  `wear/src/main/res` — run both whenever the `app-translation` pin moves, or
  the watch keeps shipping whatever text it last had.
- **`name-abbr/` submodule must be present** — `verifyNameAbbrSubmodule`
  fails the build if it's missing. CI checks out submodules explicitly;
  don't drop `submodules: true` from new workflows.
- **NTUST cert pins** have a hard-coded expiry epoch in `app/build.gradle.kts`
  (`PIN_EXPIRY_EPOCH`). `TigerDuckApp.warnIfPinsNearExpiry` logs a warning
  in the 30-day window. Rotate before lapse; post-expiry the platform
  falls back to system CA trust silently.
- **The Open-source licences lists are committed, not built.** Two
  generated files per flavor: `res/raw/aboutlibraries.json` (libraries and
  their licences, from the AboutLibraries plugin) and
  `res/raw/bundled_notices.json` (notices each dependency ships *inside* its
  own artifact — Apache-2.0 section 4(d) notices, the filled-in copyright
  line for licences published as the SPDX template, and the third-party
  licences Google compiles into Play Services and Firebase). Both come from
  one command, which is applied only under `-PexportLicenses`: applied
  unconditionally the plugin hooks every variant's resource generation and
  downloads licence texts on each build. After changing a dependency run
  `./gradlew -PexportLicenses :app:exportLibraryDefinitionsPlayRelease :app:exportLibraryDefinitionsFdroidRelease :wear:exportLibraryDefinitionsRelease`
  — `licenses-up-to-date.yaml` fails the PR otherwise. That third task is
  the watch's own dependencies, written into the **phone's** play resources
  as `aboutlibraries_wear.json` / `bundled_notices_wear.json`: `:wear`
  declares `standalone = false`, so it never reaches a user without the
  phone app, and the phone's page shows its licences under a Wear OS
  heading. The watch's notices are stored by the same content hashes as the
  phone's and leave out every text the phone's file already carries, so the
  two are read together at run time — `res/raw` is flavor-specific, so
  `WearLicenses` names the resources per flavor and is null on fdroid. Licence texts the
  plugin cannot find itself go in `app/aboutlibraries/licenses/`, keyed by
  the hash it reports.

  `app/src/main/res/raw/extra_licenses.json` is the hand-maintained
  companion: third-party material that ships with no POM to describe it
  (the `name-abbr` submodule's MIT data, Mozilla's Public Suffix List
  riding inside OkHttp, a Material Icons glyph redrawn as a drawable) plus
  copyright lines the published metadata omits. Add to it whenever
  something third-party starts shipping that Gradle never resolves.

- **Assets the licences page reads are copied in by `copyLicenseAssets`**,
  each under its own name — `tigerduck-license.txt`, `name-abbr-license.txt`
  — together with name-abbr's two JSONs. Do not map `name-abbr/` in as a
  whole asset directory: that ships its README and scraper script to every
  user, and lands its `LICENSE` on `assets/LICENSE`, the same path the app's
  own licence used to be copied to, leaving the merge order to decide which
  licence the page showed.
- **No `Co-Authored-By: Claude` trailer** on commits — per global user
  preference. Applies to every commit in this repo, every workflow.

## Greptile

`.greptile/rules.md` carries project-specific review rules — both
"do not flag" allowlist entries and "please flag" patterns. Read it
before assuming Greptile feedback is universally applicable.
