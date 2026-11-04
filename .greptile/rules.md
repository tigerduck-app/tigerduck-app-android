# Greptile Review Rules

## F-Droid metadata

- In `metadata/org.ntust.app.tigerduck.fdroid.yml`, ignore the `commit:` field when its value is the
  placeholder `<will be replaced by GitHub Action when creating release>`. Do not flag it as
  missing, invalid, or insecure.
    - **Why:** F-Droid requires the `commit:` field to be a full commit hash (plain-text tags like
      `v1.3.5` are not allowed). Because the release commit doesn't exist yet at PR time, the real
      hash is injected by a GitHub Action on the `main` branch when the release is cut. The
      placeholder is intentional.

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
  `org.ntust.app.tigerduck.shared.**`, `network.model.**` (includes
  Bulletin DTOs moved from announcements/), `data.model.**` (includes
  WhatsNewContent moved from update/), `data.cache.DataCache$*`,
  `wear.WearScheduleBridge$*`
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

## Debug network-security cleartext base-config

- In `app/src/debug/res/xml/network_security_config.xml`, do **not** flag the
  `<base-config cleartextTrafficPermitted="true" />` line. Do not suggest
  narrowing it to specific `<domain-config>` entries, do not suggest reverting
  to `cleartextTrafficPermitted="false"`, and do not flag it as a security
  regression vs the prior config that had cleartext disabled at the base.
    - **Why:** The Settings → Other settings → API endpoint override
      (`ApiEndpointDebugScreen` + `OverrideValidator`) lets developers point
      the backend client at any RFC1918 LAN address (e.g.
      `http://192.168.X.X:40000/v4`). Android's network-security-config XML
      cannot express CIDR ranges or wildcards for IP literals — every dev's
      LAN IP differs — so narrowing the base to specific `<domain-config>`
      entries would either break the feature or force every dev to hand-edit
      the XML for their own LAN. The app-level `OverrideValidator` is the
      canonical gate: loopback and private addresses may use HTTP, every
      other host must use HTTPS. The file lives under `app/src/debug/`, so release
      builds are unaffected — they use the locked-down `app/src/main/`
      variant which keeps `cleartextTrafficPermitted="false"`.

## Account-ID field IME flip

- In `app/src/main/java/org/ntust/app/tigerduck/ui/component/OutlinedAccountIdField.kt`, the
  `computeAccountInputType` function flips the IME to `TYPE_CLASS_NUMBER` as soon as `value` is
  non-empty (i.e. immediately after the leading letter is typed). Do not flag this as a bug, do not
  suggest extending the text/`VISIBLE_PASSWORD` branch to cover `length == 1 && value[0].isLetter()`
  or any other "single leading letter" case, and do not flag that backspacing all digits down to
  just the letter leaves the user on the numeric pad with no way to delete the letter from the IME.
    - **Why:** This is intentional UX. Switching to the numeric pad the instant the first char is
      entered makes the digit-entry path one tap shorter — the common case. The "can't
      backspace-delete the lone letter from the numeric IME" trade-off is accepted: users who really
      want to retype the prefix can either tap the trailing `Cancel` icon to clear the field, or use
      the standard-keyboard compatibility toggle that the field already renders. A previous change
      tried to keep the text IME while `length == 1 && value[0].isLetter()` and was reverted because
      it delayed the numeric-pad switch by one keystroke on every account-ID entry, which is the hot
      path.

## Course-override endpoint ID format — course_key, not moodle_id

- Every request that PATCHes `/sync/courses/{id}/override` names the course
  by its course key, `client:{semester}:{course_no}`: the colours and names
  through `PendingCourseOverrides` (`CourseOverrideSends` builds the key from
  the change's own term and number, or, for a row an older build kept with no
  term, from where its Moodle id leads, `legacyPlaceOf`), Use Local's stamped
  overrides (`PushCourseSyncBackend.patchStampedOverride`), and the course
  ignores and the numeric Moodle id's fill-in (`CourseIgnoreQueue`), each
  through `pushApiClient.patchCourseOverrideAnswered` or
  `patchCourseIgnoreAnswered` with a `CourseRef`. Do **not** flag passing the
  course key as the path segment, and do **not** suggest
  `Course.moodleIdNumber` or `"{semester}{courseNo}"` in its place. A change
  whose key cannot be built sends nothing (`CourseRef.named` throws
  `CourseKeyUnknownException`) and stays queued: a colour or name parks until
  a course upload lands, an ignore waits for the next sync. Do not flag that
  as a lost edit.
    - **Why:** `/v4` renamed the route to `courses/{course_key}/override`
      (`docs/api-v4.md` on tigerduck-backend, "Course override"): the key
      names the term, which a Moodle id does not, and the backend looks the
      course up by it. A `/` in the key may go raw or as `%2F`, so it is sent
      raw. The opposite rule held for `/v3`'s route, which resolved the
      segment against `UserCourse.moodle_id` only; the app no longer speaks
      `/v3`, so a suggestion to go back to the Moodle id 404s every course.

## Samsung Now Bar `automation` extra

- In `app/src/main/java/org/ntust/app/tigerduck/liveactivity/LiveActivityNotifier.kt`
  (`samsungNowBarExtras`), do **not** flag the private
  `android.ongoingActivityNoti.automation` extra as an allowlist bypass, an
  undocumented/privileged path, or Samsung policy abuse, and do **not**
  suggest removing it or gating it behind allowlist access.
    - **Why:** This is a deliberate, reviewed decision (`945d6fa3`), not an
      oversight. Samsung's One UI SystemUI only promotes a notification when
      `isPromotedState()` holds, and that predicate short-circuits its
      `AllowedOngoingActivityListManager` when `isAutomation()` is true — so
      without this extra a TigerDuck class countdown never reaches the Now
      Bar on a device where the allowlist is in use, which is the entire
      feature. The decompiled One UI 8.5 predicate is quoted in the KDoc
      directly above the method, and the behaviour was verified by hand on
      One UI 8.5 (A26, S26 Ultra) and 9.0 (A07).
    - It grants the app nothing beyond promoting **its own** notification,
      which already passes AOSP's `hasPromotableCharacteristics()` checks;
      it reads no data and touches no other app. It is inert on One UI 8.0
      and below (which promote nothing) and inert for any package in
      Samsung's `blockedRONAppList`, which is consulted first — so a Samsung
      decision to exclude TigerDuck still wins.
    - Scoped to Samsung on purpose (`deviceSkin.isSamsung`): no other OEM
      reads these keys. Only the one key is set — adding
      `android.ongoingActivityNoti.style >= 1` sets `mIsRon = false` and
      cancels the promotion, so the "more complete" extras undo it.
