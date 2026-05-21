# Greptile Review Rules

## F-Droid metadata

- In `metadata/org.ntust.app.tigerduck.fdroid.yml`, ignore the `commit:` field when its value is the placeholder `<will be replaced by GitHub Action when creating release>`. Do not flag it as missing, invalid, or insecure.
  - **Why:** F-Droid requires the `commit:` field to be a full commit hash (plain-text tags like `v1.3.5` are not allowed). Because the release commit doesn't exist yet at PR time, the real hash is injected by a GitHub Action on the `main` branch when the release is cut. The placeholder is intentional.

## Upgrade-safe persistence — please flag

**Rule:** In any commit that touches a Gson-deserialized data class
persisted across upgrades, **flag any new field that is BOTH** (a) a
non-null Kotlin reference type (`String`, `List<T>`, `Map<K,V>`, custom
class — not `Int`/`Long`/`Boolean` primitives) **AND** (b) declared with
a Kotlin default value (e.g. `val foo: String = "{}"`).

Specifically watch the following files / classes — they are persisted
to disk via `DataCache` / `SchedulePersistence` and re-read on first
launch after upgrade:

- `shared/src/main/java/org/ntust/app/tigerduck/shared/Course.kt`
- Any class kept by `app/proguard-rules.pro`:
  `org.ntust.app.tigerduck.shared.**`, `network.model.**`,
  `data.model.**`, `data.cache.DataCache$*`, `wear.WearScheduleBridge$*`,
  `announcements.{BulletinSummary, BulletinDetail, BulletinListResponse,
  OrgLabel, TagLabel, TaxonomyResponse, SubscriptionRule,
  SubscriptionsResponse, SubscriptionsPutRequest}`
- `app/src/play/java/org/ntust/app/tigerduck/wear/WearScheduleBridge.kt`
  (`CourseDto` wire format)
- `wear/src/main/java/org/ntust/app/tigerduck/wear/data/SchedulePersistence.kt`
  (`CourseWire` DTO)

**Why:** Gson 2.x has no Kotlin support. It instantiates data classes
via `sun.misc.Unsafe.allocateInstance`, which bypasses the primary
constructor. Kotlin defaults declared like `val x: String = "{}"` are
**never applied** for fields absent from the JSON — the field stays
at the JVM zero value (`null` for any reference type). Downstream
non-null parameter checks then NPE on first read after upgrade. This
has caused two production incidents:

- **v1.3.x → v1.4.0**: `Course` moved to `:shared` without an R8 keep
  rule. Fields were renamed, Gson couldn't match cached keys, every
  reference field deserialized as null, `WearScheduleBridge.CourseDto`
  constructor NPE'd from `TigerDuckApp.onCreate`'s safety-net
  `wearBridge.publish()`. Required hotfix v1.4.1 (commits `ab2f5d6`,
  `3d7b3aa`, `199a8b2`).
- **v1.4.1 → v1.4.2 (near-miss, caught pre-ship)**: added
  `Course.classroomMapJson: String = "{}"` (non-null). v1.4.1 caches
  lacked the key → same NPE shape via `toDto`. Fixed by making the
  field `String?` and coalescing at the wire-DTO call site. See
  commit `11d0d22`.

**How to apply:**

Flag the diff and require the author to choose one of:

1. **Nullable type** (`String?`, `Int?`, etc.) with all readers
   null-tolerant (try/catch, `?: default`, `?.`).
2. **Primitive** (`Int`, `Long`, `Boolean`, etc.) — JVM zero default
   is acceptable if business logic treats it as the "unset" sentinel.
3. **Non-null type PLUS a `DataMigration` step** (bump
   `CURRENT_SCHEMA` in `DataMigration.kt`, add `migrateNtoN+1()` that
   rewrites every affected cache file to include the new key).

Do **NOT** accept "the field has a Kotlin default" as a defense — the
default is silently dropped by Gson's Unsafe path. The
`SchedulePersistence.CourseWire.classroomMapJson: String? = null` field
and `Course.classroomMapJson: String? = "{}"` are the reference
templates for option 1. The `DataMigration.migrate1to2` sweep is the
template for option 3.

Also flag any move of a class into `org.ntust.app.tigerduck.shared.**`
or any other Gson-reachable package without a corresponding
`-keep class ... { *; }` entry in `app/proguard-rules.pro` — R8 will
rename the fields under `release` and re-introduce the same crash.

Confidence: high. This is a known-deadly pattern with two production
data points.

## Account-ID field IME flip

- In `app/src/main/java/org/ntust/app/tigerduck/ui/component/OutlinedAccountIdField.kt`, the `computeAccountInputType` function flips the IME to `TYPE_CLASS_NUMBER` as soon as `value` is non-empty (i.e. immediately after the leading letter is typed). Do not flag this as a bug, do not suggest extending the text/`VISIBLE_PASSWORD` branch to cover `length == 1 && value[0].isLetter()` or any other "single leading letter" case, and do not flag that backspacing all digits down to just the letter leaves the user on the numeric pad with no way to delete the letter from the IME.
  - **Why:** This is intentional UX. Switching to the numeric pad the instant the first char is entered makes the digit-entry path one tap shorter — the common case. The "can't backspace-delete the lone letter from the numeric IME" trade-off is accepted: users who really want to retype the prefix can either tap the trailing `Cancel` icon to clear the field, or use the standard-keyboard compatibility toggle that the field already renders. A previous change tried to keep the text IME while `length == 1 && value[0].isLetter()` and was reverted because it delayed the numeric-pad switch by one keystroke on every account-ID entry, which is the hot path.
