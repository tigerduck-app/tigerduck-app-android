# Debugging & build variants

## Quick install (scripts in this dir)

| Script                            | What it does                                                                                                                                                                                                                    |
|-----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./debug/install-fdroid.sh`       | Build + install `:app:fdroidDebug` to a chosen phone.                                                                                                                                                                           |
| `./debug/install-play.sh`         | Build + install `:app:playDebug` to a chosen phone; asks if you want `:wear:debug` on a paired watch too.                                                                                                                       |
| `./debug/install-play-release.sh` | Build + install `:app:playRelease` (and optionally `:wear:release`) APK(s) via `adb install`. Use when you need to test release-mode behavior (R8/ProGuard, signing) without going through Internal Testing.                    |
| `./debug/sync-localizations.sh`   | Regenerate `app/` and `wear/` `values-*/strings.xml` from the localization submodule. Run after `git submodule update --remote localization` so committed resources match the new submodule pointer before you build or commit. |

The `install-*` scripts:

- Run from the project root.
- Auto-pick the device when only one matching phone/watch is connected; otherwise prompt with
  `0/1/2…`.
- Filter by `ro.build.characteristics` so wear-only and phone-only steps don't accidentally
  cross-target.
- Use `adb install -r -d` so they don't trip on existing installs or downgrades during fast
  iteration.

`_lib.sh` is the shared helper (sourced by the others). Don't run it directly.

## Build variants

The app ships in two **distribution flavors** crossed with the standard
**debug / release** build types, so there are four variants:

| Variant         | Distribution channel          | FCM push | Cleartext to dev backend                                  | Use when                                                                 |
|-----------------|-------------------------------|----------|-----------------------------------------------------------|--------------------------------------------------------------------------|
| `playDebug`     | Sideload + dev                | Yes      | Yes (one LAN IP allowlisted — see *Cleartext HTTP* below) | Day-to-day local dev with the laptop backend. Default in Android Studio. |
| `playRelease`   | Google Play Store             | Yes      | No                                                        | Producing the Play Store APK / bundle.                                   |
| `fdroidDebug`   | Sideload of the F-Droid build | No       | Yes                                                       | Smoke-testing the FOSS variant locally.                                  |
| `fdroidRelease` | F-Droid (anti-features-clean) | No       | No                                                        | The artifact F-Droid's buildserver actually produces.                    |

`fdroid*` builds get an `applicationIdSuffix` of `.fdroid`, so they install
side-by-side with the play build. They contain **zero Firebase / Google Play
Services classes** (verified via `aapt2 dump xmltree`). Bulletins still work
on F-Droid via manual refresh / pull-to-refresh; there is just no real-time
push.

Direct gradle commands (when the scripts above don't fit your flow):

```bash
./gradlew :app:assemblePlayDebug          # APK only
./gradlew :app:installPlayDebug           # build + push to *the* connected device

./gradlew :app:assembleFdroidDebug
./gradlew :app:installFdroidDebug

./gradlew :app:assemblePlayRelease        # signed only when KEYSTORE_PASSWORD is set
./gradlew :app:assembleFdroidRelease

./gradlew :wear:assembleDebug
./gradlew :wear:installDebug
```

`./gradlew install*` only works cleanly with one connected device. If you
have a phone + watch attached, prefer the scripts above (they pick by serial)
or pass `-Pandroid.injected.deviceSerial=<serial>`.

## Debug clock override

`playDebug` and `fdroidDebug` builds expose a **Settings → Developer** entry
that lets you make the entire app behave as if the clock were any chosen
date and time. This is what you use to test ongoing-class UI, "next class"
states, the live activity, widgets, AlarmManager-scheduled notifications,
and the watch's NowNext / tile / complication without manipulating the
device clock.

Key behaviors:

- **Frozen** mode: every read of `AppClock.nowMillis()` returns the chosen
  instant. The clock does not advance.
- **Ticking** mode: the chosen instant becomes "now" and advances 1:1 with
  real time. Useful for watching ongoing → ended transitions live.
- **Persistence**: the override survives app restarts (stored in
  `debug_clock` SharedPreferences, separate from `AppPreferences`).
- **Watch sync**: on `playDebug`, the override pushes to a paired watch via
  the Wearable Data Layer at path `/tigerduck/debug-clock`; the watch
  reads it on cold start too.
- **Notification firing**: AlarmManager triggers go through
  `AppClock.realTimeFor(...)`, which translates fake-clock targets to real
  wall-clock targets. So if you set fake time 30 s before a class start,
  the class-preparing notification fires in 30 real seconds, with content
  describing the fake slot.
- **Release builds**: the entry point and the route are gated on
  `BuildConfig.DEBUG`, so neither exists in `playRelease` / `fdroidRelease`.

Auth, network caches, login expiry, and library token expiry intentionally
do **not** use `AppClock` — they continue to read real time, so you can't
log yourself out by setting fake time to 2099.

Spec and plan: `docs/superpowers/specs/2026-05-09-debug-clock-override-design.md`
and `docs/superpowers/plans/2026-05-09-debug-clock-override.md` (both in
`docs/superpowers/`, gitignored).

## Wireless ADB recipe

```bash
# Pair once (Android 11+):
#   Settings → Developer options → Wireless debugging → Pair device with pairing code
adb pair  <phone-ip>:<pair-port>    <code>
adb connect <phone-ip>:<connect-port>

adb devices                                        # confirm phone listed

./debug/install-play.sh                            # build + push the APK

adb logcat -c && adb logcat \
  TigerDuck-Push:V Push.Register:V \
  TigerDuck-Bulletin:V FirebaseMessaging:I *:S
```

For a watch, repeat the pair/connect over ADB-over-Bluetooth or its own
Wi-Fi pair flow, then re-run `./debug/install-play.sh` and answer "Y" to
the wear prompt.

## Local push backend

The backend repo (`tigerduck-app/backend`) lives outside this tree. Clone it
next to this repo and adjust the paths below if yours differs.

```bash
# Postgres (one-time):
docker run -d --name tigerduck-dev-pg \
  -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=tigerduck -e POSTGRES_USER=tigerduck \
  -p 5433:5432 postgres:16

# Server:
cd ~/tigerduck-app/backend
nohup uv run uvicorn server.main:app --host 0.0.0.0 --port 8000 \
  > /tmp/tigerduck-dev.log 2>&1 &

# Ready check (server takes ~60 s on first boot to give up on the LLM probe):
until curl -s -o /dev/null -w "%{http_code}" \
  http://127.0.0.1:8000/v1/bulletins/taxonomy | grep -q 200; do sleep 2; done

# Stop:
lsof -ti:8000 | xargs kill
docker stop tigerduck-dev-pg
```

The Android side reads the dev backend URL + shared secret from
`local.properties` (root of this repo, gitignored). Keys: `pushBaseUrl` and
`pushSharedSecret`. Both are baked into `BuildConfig` for `debug` builds; the
`release` block uses `pushBaseUrlRelease` and the `PUSH_SHARED_SECRET` env var.

## Cleartext HTTP

Production network security pins the NTUST hosts and forbids cleartext. The
debug variant overrides that with `app/src/debug/res/xml/network_security_config.xml`,
which whitelists exactly one private LAN address for the dev push backend.
Find the `<domain>…</domain>` line under the dev-laptop `<domain-config>` and
replace it with your own laptop's LAN IP (e.g. `192.168.1.x`); update
`pushBaseUrl` in `local.properties` to match. Both must point at the same host
or the phone will get `CLEARTEXT_NOT_PERMITTED`.

## Push smoke test

With the `playDebug` build installed (`./debug/install-play.sh`), signed in,
and Wi-Fi sharing the laptop's network:

1. Confirm registration: backend log should show `POST /v1/devices/register
   200`. To inspect the row:

   ```bash
   docker exec -i tigerduck-dev-pg psql -U tigerduck -d tigerduck \
     -c "SELECT user_id, platform, length(pts_token_hex), created_at \
         FROM device_registrations ORDER BY created_at DESC LIMIT 5;"
   ```

2. Inject a fake bulletin so the dispatcher has something to send:

   ```bash
   docker exec -i tigerduck-dev-pg psql -U tigerduck -d tigerduck <<'SQL'
   INSERT INTO bulletins (external_id, title, title_clean, source_url,
                          posted_at, canonical_org)
   VALUES ('manual-' || extract(epoch from now())::bigint,
           '測試公告', '測試公告',
           'https://example.com/test', NOW(), 'oaa');
   SQL
   ```

3. The bulletin dispatcher runs every minute; backend log will show an `fcm.send`
   followed by a 200 from Google. The phone should display a notification on
   the `bulletins` channel; tapping it opens
   `tigerduck://announcement/<id>` and lands on the detail screen.

## Common pitfalls

- **Script can't find APK after build** → the AGP output filename includes a
  version suffix. The scripts use a glob (`*.apk`) so this works; if you see
  "multiple APKs matched", you have stale outputs from an older build. Run
  `./gradlew :app:clean` (or `:wear:clean`) and re-run the script.

- **`adb devices` lists a watch as `unauthorized`** → tap "Allow USB
  debugging" on the watch face. Some watches need a manual reauthorization
  on every host change.

- **`./gradlew install*` fails with "more than one device"** → use the
  scripts in this dir; they pick by serial. Or pass
  `-Pandroid.injected.deviceSerial=<serial>` to gradle.

- **`processFdroidDebugGoogleServices` fails with "No matching client found"**
  → `google-services.json` is at `app/`. Move it to `app/src/play/`. The
  plugin in `app/build.gradle.kts` is configured with
  `MissingGoogleServicesStrategy.IGNORE` so fdroid variants skip the file
  entirely once it's under the play flavor.

- **Phone gets `CLEARTEXT_NOT_PERMITTED`** → laptop's current LAN IP doesn't
  match the one whitelisted in `app/src/debug/res/xml/network_security_config.xml`.
  Update the `<domain>` entry under the dev-laptop `<domain-config>` and rebuild.

- **`POST /v1/devices/register` returns 401** → `pushSharedSecret` in
  `local.properties` doesn't match `TIGERDUCK_API_SHARED_SECRET` in
  `~/tigerduck-app/backend/.env`. Either side can be regenerated; keep them
  in sync.

- **Phone never receives push, but registration succeeded** → backend log
  will say `fcm.using_recording_sender` instead of `fcm.using_real_sender`.
  Check that `~/tigerduck-app/backend/server/secrets/fcm_service_account.json`
  exists and `TIGERDUCK_FCM_PROJECT_ID` in `.env` matches its
  `project_id` field. Restart uvicorn after fixing.

- **Debug clock override seems stuck on** → it persists across app restarts
  by design. Open Settings → Developer → "Use fake time" → toggle off, or
  tap **Reset**. Worst case, clear the app's `debug_clock` prefs:
  `adb shell run-as <applicationId> rm shared_prefs/debug_clock.xml`.
