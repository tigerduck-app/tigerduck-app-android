# TigerDuck Android — Agent Notes

Multi-module Gradle project: `:app` (phone), `:wear` (watch OS), `:shared`
(domain models reused on both). Phone has `play` and `fdroid` flavors;
`:wear` is play-only. Localization strings come from the `app-translation/`
submodule and are generated on every build (see below); none are committed.

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
- **Localization strings are generated at build time** from the `app-translation/`
  submodule's `source/*.json`, by `generateAppTranslationStrings`
  (`gradle/app-translation.gradle.kts`, applied by `:app` and `:wear`), which runs
  `tools/localization/sync_localizations.py` and the submodule's generator into
  `build/generated/appTranslation/res`, a generated resource directory. Neither
  this repo nor the submodule commits Android's `strings.xml` or
  `locales_config.xml`, so a translation change is a submodule pin bump and
  nothing else. Edit strings in the submodule's JSON. Building needs Python 3.9
  or newer (`python3`, `python` or `py` on PATH; 3.9 is macOS's own `python3`,
  and CI tests it and the newest); the task fails without it rather than
  ship an app with no strings.
- **`name-abbr/` submodule must be present** — `verifyNameAbbrSubmodule`
  fails the build if it's missing. CI checks out submodules explicitly;
  don't drop `submodules: true` from new workflows.
- **TLS pins live in code, not in `network_security_config.xml`**, so a
  signed document from TigerSync can change them at runtime (TLS pins spec,
  `docs/superpowers/specs/2026-10-25-tls-pins-and-links-spec-final.md`).
  `:shared`'s `BuiltInPins` is the fallback table (CA pins only, with
  `BASELINE_SERIAL`); `PinnedTrustManager` wraps the platform's trust manager,
  which still decides system trust, and applies the pins on top. Every HTTPS
  client starts from `pinnedClientBuilder(tls)`; the one exception is the pin
  document's own fetch (`PinDocumentClient`), unpinned on purpose.
  `AllHttpsClientsPinnedTest` fails on an `OkHttpClient.Builder()` anywhere
  else, on raw TLS contexts and sockets, and on School Mail sessions built
  without the pinned socket factory. `BuiltInPinsTest` fails 45 days before a
  built-in set expires, when `PIN_EXPIRY_EPOCH` (`app/build.gradle.kts`) is not
  the earliest expiry, when a pin stops matching its certificate fixture, and
  when any network config grows a `<pin-set>` again. `PinDocumentKeys` holds
  TigerSync's Ed25519 public keys; never put a test key there. The Apple
  repo's `tls-pin-rotation` skill rotates both apps' tables.
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
  the hash it reports; so does a text it finds only as SPDX's sample, whose
  copyright line names someone else (0BSD's).

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

## Shared homes

Each rule below lives in one place. A second copy fails
`tools/guardrails/check_guardrails.py` (CI job `guardrails`); its
`baseline.json` lists today's offenses and may only shrink. Run
`python3 tools/guardrails/check_guardrails.py --update-baseline` after fixing
some. The same check fails a new Kotlin file over 400 lines or function over 80.

- `TaipeiClock` (`shared/.../time/TaipeiClock.kt`): the `"Asia/Taipei"`
  zone and Taipei's day and clock. No other file writes the literal.
- `AcademicTerm` (`shared/.../time/AcademicTerm.kt`): parsing and
  ordering of term codes. No other file sorts by a semester or term string.
- `ClassTimeline` (`shared/.../timeline/`): which class is ongoing or
  next, with D2's end rule (`start <= now < end`), for every screen.
- `AppPreferences` reads `BuildConfig` for the `"fdroid"` flavor; no other file
  compares against the literal.
- `PageScaffold` (`app/.../ui/component/PageScaffold.kt`): every page's shell, with
  its defaults in `PageDefaults`. No file outside `ui/component` calls a `TopAppBar`.
- Who the account is, and whether the app is in the demo (ratchets, count lines):
  `account-counter` counts hand-kept generation and epoch numbers, and
  `demo-check` counts `isDemo`-style reads outside the demo's own homes. The
  sign-in type replaces the first, one session answer the second.
  `sign-in-read` fails any `StartsAccountWork` opt-in outside the layers that
  start account work; every other layer is handed the `SignIn`. A new home is
  a decision to name in the PR body. `check_guardrails.py --introduce KIND`
  lets a ratchet land with its counts; a function whose parameter types
  changed keeps its baseline entry.
- `AccountSessions.events`: every account's sign-ins and sign-outs, one stream in
  order, which every reactor reads, so a fast sign-out and sign-in can't be missed.
  `AccountSession.changes` is that for a page. App-level reactors subscribe with
  `CoroutineScope.follow`, never on `AccountSession.launch`, whose cancellation is
  the sign-out they follow. The `sign-out-reactor` check fails watching `authState`
  or a stream of an account's own for a sign-in or a sign-out. It takes no baseline
  entries.
- `AuthTokenManager.requireAuthHeader(SignIn)`: the only way to a backend request's
  header, so a request of account data does not compile without its sign-in. The
  `bearer-home` check fails a `"Bearer ` literal anywhere but there, the guard that
  reads it (`SessionHostGuard`) and the departing unregister's one line in
  `PushApiClient`, the only request with no sign-in. It takes no baseline entries.
- `AccountSession` and `SignIn` (`app/.../auth/session/AccountSession.kt`,
  `shared/.../account/SignIn.kt`): one session per account (school, mail, library)
  for the life of the process, and one `SignIn` per sign-in, which answers
  `isCurrent` itself. A store write or a backend request of account data takes the
  `SignIn` it was read for, so it does not compile without one, and the store drops
  a stale one. `sign-in-read` holds who may read one. `AccountSession.isDemo` is the
  one answer to whether the account is the demo, which `demo-check` holds;
  `DemoStaysOnTheDeviceTest` pins the safety nets behind it: `DemoModeInterceptor`
  on every OkHttp client but the two it names, and `MailRepository`'s check, which
  keeps IMAP and SMTP shut.
- `AccountKey` (`app/.../data/preferences/AccountKey.kt`, the school's in
  `SchoolKeys.kt`): a school preference writes only with a `SignIn`. Its stored
  property is private, so a write without one does not compile.
- `SchoolStores` (`app/.../auth/session/SchoolStores.kt`): the school's stores,
  `AccountScoped` at once and `LateAccountScoped` late, in the order its sign-out
  clears them, built only at a sign-out so a store unused this process is cleared
  too. `AccountStateInventoryTest` fails on an account store on no list, and on a
  school row whose clearer nothing the school's sign-out reaches.
- `SignOutPlans` (`app/.../auth/session/SignOutPlans.kt`): every sign-out,
  `signOut(reason)`, each account's in the order it always ran, the reason on each
  `SignedOut` it tells. It is the only caller of each account's end: the
  `sign-out-start` check fails the token manager's logout, School Mail's or the
  library's sign-out, or a drop of the school's or the library's credentials
  anywhere else. It takes no baseline entries.
- `SyncCategory` (`app/.../sync/SyncCategory.kt`): the four sync categories,
  each with today's stored string and the backend's `sync_*` name. A `when` on it has
  no `else`, so a new category does not compile until every rule answers for it.
- `SyncPolicy` (`app/.../sync/SyncPolicy.kt`): whether a category keeps,
  sends or applies, from the master switch, the flavor, the category switches and the
  re-enable hold, read once through `SyncPolicies.current`. The backend settles every
  field by its stamp (last writer wins), so an edit is kept whatever its switches say
  and sent once they are on. The `sync-switch-read` ratchet counts lines naming a switch
  or the hold anywhere but there and the files that store or send them.
- `SyncClock` (`app/.../sync/SyncClock.kt`): every edit's TigerSync stamp, in
  whole milliseconds, capped by the backend clock it last heard (`tigersync-clock`).
  `SyncClockTest` pins that spec's scenarios.
- `ApiBase` and `BackendError` (`app/.../network/backend/`): which API a
  base speaks (`BackendApi.of`), the one place `/v3` turns into `/v4`, and one
  backend whatever its version segment says; and the backend's error code, read once
  from either version. `api-version-literal` fails a `"v3"` or `"v4"` literal, or a
  `/v3` or `/v4` path segment in one, outside `ApiBase.kt`, and takes no baseline
  entries. `status-in-message` counts a status or error code searched for in a
  message.
- `Outbox` and `OutboxLane` (`app/.../sync/outbox/`): every unsent edit, one lane per
  channel with one request in flight, each edit stamped by `SyncClock` and, written by an
  older build with no time, by its old-entry time (`OldEntryTime`), and each answer's clock
  kept as its field's acknowledgment (`AckClock`). The six queues (`UnsentMarks`,
  `PendingHolidayOverrides`, `PendingCourseOverrides`, `CourseIgnoreQueue`,
  `PendingCourseAdds`, `PendingCourseDeletes`) keep their own files and register as
  channels from them (`@IntoSet`); `OutboxTest` holds the drain order. The `sync-write`
  ratchet counts calls of a backend sync write, and references to one
  (`client::deleteCourse`), outside `push/`, `sync/` and the queues.
- `Synchronizer`, with `FirstPullGate`, `SchoolUploads` and `RefreshOrder`
  (`app/.../sync/`): one full sync for Home and the background worker, its reconcilers in
  the order `Reconcilers` pins, each taking the snapshot field by field but what this phone
  keeps (`SyncPass.holds`, `PullHolds`, LWW D12); nothing derived from school data goes up
  before a process's first pull; a refresh applies the pull before the school's data, then
  pushes. The `full-sync-fetch` ratchet counts calls of and references to `fetchFullSync`
  outside `SyncApiClient` and the `Synchronizer`, and `SynchronizerOrderTest` and
  `RefreshOrderTest` hold the orders.

## Greptile

`.greptile/rules.md` carries project-specific review rules — both
"do not flag" allowlist entries and "please flag" patterns. Read it
before assuming Greptile feedback is universally applicable.
