---
name: upgrade-safe-persistence
description: Use when modifying any Gson-deserialized data class persisted across upgrades — specifically shared/Course.kt, data/cache/DataCache.kt, data/DataMigration.kt, wear/WearScheduleBridge.kt, wear/data/SchedulePersistence.kt, network/model/**, data/model/**, or app/proguard-rules.pro. Also use before reviewing PRs that add fields to any class in app/proguard-rules.pro's keep list. Enforces the upgrade-crash-prevention checklist so adding a new persisted field doesn't NPE existing users on first launch after upgrade (the v1.3.x→v1.4.0 and v1.4.1→v1.4.2 incident pattern).
---

# Upgrade-Safe Persistence Checklist

This codebase ships to thousands of NTUST students on the Play Store and
F-Droid. Two consecutive minor releases crashed at first-launch after
upgrade because of the **same root pattern** — adding a new non-null
Kotlin field to a class that's deserialized from disk by Gson. Before
touching the persistence layer, walk this checklist.

## The crash mechanism (memorize this)

1. Gson 2.x has no Kotlin support. For data classes (which lack a
   no-arg constructor) it instantiates via `sun.misc.Unsafe.allocateInstance`.
2. `Unsafe.allocateInstance` **bypasses every constructor**. That means
   Kotlin defaults declared as `val x: String = "{}"` are **never applied**.
3. JSON keys missing from the file → corresponding fields stay at the
   JVM zero value: `null` for any reference type, `0` for `Int`,
   `false` for `Boolean`.
4. Kotlin's `Intrinsics.checkNotNullParameter` fires in any downstream
   constructor (or `!!` operator, or platform-type-bridged Java call)
   that receives that null. **NPE crashes the app.**
5. If the NPE happens inside `appScope.launch { ... }` from
   `TigerDuckApp.onCreate`, the default uncaught-exception handler
   takes down the process before the user sees the first frame.

## When to invoke this skill

You're about to edit ANY of:

- `shared/src/main/java/org/ntust/app/tigerduck/shared/Course.kt`
- `app/src/main/java/org/ntust/app/tigerduck/data/cache/DataCache.kt`
- `app/src/main/java/org/ntust/app/tigerduck/data/DataMigration.kt`
- `app/src/main/java/org/ntust/app/tigerduck/data/preferences/*.kt`
- `app/src/play/java/org/ntust/app/tigerduck/wear/WearScheduleBridge.kt`
- `wear/src/main/java/org/ntust/app/tigerduck/wear/data/SchedulePersistence.kt`
- Any class listed in `app/proguard-rules.pro` keep rules:
    - `network.model.**`
    - `data.model.**`
    - `data.cache.DataCache$*`
    - `wear.WearScheduleBridge$*`
    - `org.ntust.app.tigerduck.shared.**`
- `app/proguard-rules.pro` itself

## Checklist — run before saving the edit

### 1. Are you adding a field to a Gson-serialized data class?

If yes, the field MUST be one of:

- ✅ **Nullable Kotlin type** (`String?`, `Int?`, `Map<...>?`, custom-class?)
  with all readers tolerating null (try/catch, `?: default`, `?.`)
- ✅ **Primitive type** (`Int`, `Long`, `Float`, `Boolean`, `Double`) —
  JVM defaults of 0/false are acceptable if the business logic treats
  them as the "unset" sentinel
- ✅ **Non-null type PLUS a `DataMigration` step** (bump
  `CURRENT_SCHEMA`, add a `migrateNtoN+1()` that rewrites every
  affected cache file on disk to include the new key with its default)

❌ **Forbidden:** non-null Kotlin reference type (`String`, `List<T>`,
`Map<K,V>`, custom-class) with a Kotlin default value. Gson will
silently drop the default and leave the field null.

### 2. If you added a nullable field, are all readers null-safe?

Check every read site:

```bash
grep -rn "\.<newFieldName>" app/src wear/src shared/src
```

Each access must either:

- Be inside a try/catch that catches `Exception`
- Use `?.` / `?: default` / `if (x != null)`
- NOT pass the value to a non-null parameter without coalescing

The `Course.classroomMap` getter is a good template — try/catch around
the Gson parse, `?: emptyMap()` for the null branch, `@Transient @Volatile`
cache that tolerates null on cold load.

### 3. Did you add a new persisted JSON file?

If yes:

- New file = no upgrade conflict (the OS file doesn't exist on the old
  install). ✅ Generally safe.
- But: add it to `DataCache.clearAllUserData` if it's user-scoped, so
  account switches and logout actually wipe it. The recent
  `moodle_course_ids.json` (commit `ac11f3d`) is the template.

### 4. Did you change the SHAPE of an existing persisted JSON (rename a field, change a type from `String` to `List<String>`, etc.)?

This is a hard migration. You **must** add a `DataMigration` step. The
existing `migrate1to2` (sweep R8-obfuscated v1.4.0 caches) is the
template. Use content-based detection (a sentinel substring in the
raw JSON), not file-presence checks, so direct upgrades from very old
versions still work.

### 5. Did you add or move a class under `org.ntust.app.tigerduck.shared.**` or any of the keep-list packages?

R8 keep rules in `app/proguard-rules.pro` are load-bearing. If the
class is reachable through Gson serialization, it MUST be in the keep
list — otherwise R8 renames the fields, Gson can't match them on read,
and you get the v1.3.x → v1.4.0 incident again. **A green build is not
proof** — the failure only triggers on release-flavor obfuscated builds
running against caches written by a prior version.

### 6. Are you relying on `DataMigration` to make a read safe?

Since `6ea41f62`, `DataMigration.run()` is the first call in
`TigerDuckApp.onCreate`, ahead of the `DataCache` listener, the
safety-net `wearBridge.publish()`, `BootReceiver` and
`BackgroundSyncWorker`, so on the phone every pending step has run
before anything reads the cache. That still does not make migration
enough on its own:

- A step only fixes what it was written to fix. A new field with no
  step behind it reaches every reader as null.
- `:wear` has no `DataMigration`. `SchedulePersistence` reads whatever
  an older watch build wrote, and Data Layer payloads can come from a
  phone that has not upgraded yet.

Defend at the read site (token sentinel, try/catch, nullable fields)
— do NOT rely on DataMigration alone.

### 7. Run the manual upgrade test before opening the PR

Per `CONTRIBUTING.md`'s `main`-target checklist:

> I have tested on my phone (watch) / emulator that upgrading from the
> previous version does not break the app.

For the persistence layer this means actually:

```bash
# Install previous release (whatever's on Play / a previous tag)
./gradlew :app:installPlayRelease   # from the prior tag
# Open the app, log in, let it sync a real timetable to cache
# Then install your branch's build OVER it:
./debug/install-play-release.sh     # current branch
# Open. The first launch must not crash; first frame must render with
# either the cached schedule or a recovered/empty state.
```

A clean install + sync does NOT exercise the upgrade path. Cache files
have to be on disk from the prior version before your build runs.

## Anti-patterns

- ❌ Adding `val foo: String = ""` to `Course` because "it has a default,
  it'll be safe" — Gson Unsafe path drops the default.
- ❌ Relying on `DataMigration` alone for read-path safety — it covers
  only the shapes a step was written for, and never the watch.
- ❌ Removing the `requireContent = COURSE_NO_TOKEN` sentinel in
  `DataCache.loadCourses` — it's the load-bearing v1.4.0 cache
  rejection check.
- ❌ Adding a class to `:shared` without verifying the
  `-keep class org.ntust.app.tigerduck.shared.** { *; }` rule still
  covers it (it does today, but don't `-keep` more narrowly).
- ❌ Putting the new field at the END of the `Course` constructor and
  assuming Gson serializes order matters — it doesn't, but order DOES
  affect the `copy()` call sites if you don't use named args.

## Past incidents — read the commits for context

- `ab2f5d6` `fix(release): keep org.ntust.app.tigerduck.shared.** under R8`
- `3d7b3aa` `fix(release): defend against v1.4.0 obfuscated cache on upgrade`
- `199a8b2` `fix(release): harden v1.4.0 cache sentinel and wear parse`
- `11d0d22` `chore(release): bump version to 1.4.2` (the
  classroomMapJson near-miss this skill is meant to prevent)
