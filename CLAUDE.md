# TigerDuck Android — Agent Notes

Multi-module Gradle project: `:app` (phone), `:wear` (watch OS), `:shared`
(domain models reused on both). Phone has `play` and `fdroid` flavors;
`:wear` is play-only. Localization strings come from the `app-translation/`
submodule and are generated via `tools/localization/sync_localizations.py`
(also wired into the `syncLocalizations` Gradle task).

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
  (those are regenerated and would be clobbered). Run `:app:syncLocalizations`
  to refresh.
- **`name-abbr/` submodule must be present** — `verifyNameAbbrSubmodule`
  fails the build if it's missing. CI checks out submodules explicitly;
  don't drop `submodules: true` from new workflows.
- **NTUST cert pins** have a hard-coded expiry epoch in `app/build.gradle.kts`
  (`PIN_EXPIRY_EPOCH`). `TigerDuckApp.warnIfPinsNearExpiry` logs a warning
  in the 30-day window. Rotate before lapse; post-expiry the platform
  falls back to system CA trust silently.
- **No `Co-Authored-By: Claude` trailer** on commits — per global user
  preference. Applies to every commit in this repo, every workflow.

## Greptile

`.greptile/rules.md` carries project-specific review rules — both
"do not flag" allowlist entries and "please flag" patterns. Read it
before assuming Greptile feedback is universally applicable.
