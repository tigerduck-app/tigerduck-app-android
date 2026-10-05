# Debugging & build variants

## Quick install (scripts in this dir)

| Script                            | What it does                                                                                                                                                                                                                    |
|-----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./debug/install-fdroid.sh`       | Build + install `:app:fdroidDebug` to a chosen phone.                                                                                                                                                                           |
| `./debug/install-play.sh`         | Build + install `:app:playDebug` to a chosen phone; asks if you want `:wear:debug` on a paired watch too.                                                                                                                       |
| `./debug/install-play-release.sh` | Build + install `:app:playRelease` (and optionally `:wear:release`) APK(s) via `adb install`. Use when you need to test release-mode behavior (R8/ProGuard, signing) without going through Internal Testing.                    |
| `./debug/set-clock.sh`           | Set or clear the debug clock override over adb, non-interactively. Applies to the running app immediately — no restart, no tapping through Settings. See "Driving the clock from a shell" below.                          |
| `./debug/sync-localizations.sh`   | Regenerate `app/` and `wear/` `values-*/strings.xml` from the app-translation submodule. Run after `git submodule update --remote app-translation` so committed resources match the new submodule pointer before you build or commit. |

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
| `playDebug`     | Sideload + dev                | Yes      | Yes (any unpinned host — see *Cleartext HTTP* below)      | Day-to-day local dev with the laptop backend. Default in Android Studio. |
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

### Driving the clock from a shell

The picker in Settings → Developer is fine when a human is holding the
phone, but everything time-dependent in this app — ongoing-class UI,
next-class resolution, the Live Update, widgets, every AlarmManager
notification — was reachable *only* by tapping it. `set-clock.sh`
broadcasts to `DebugClockReceiver` (debug builds only) instead:

```bash
./debug/set-clock.sh 2026-09-09 10:44          # freeze at that Taipei time
./debug/set-clock.sh 2026-09-09T09:20 --tick   # ...and let it advance 1:1
./debug/set-clock.sh --clear                   # back to real time
./debug/set-clock.sh 2026-09-09 10:44 -p org.ntust.app.tigerduck.fdroid
```

Or by hand, without the script:

```bash
adb shell am broadcast -a org.ntust.app.tigerduck.debug.SET_CLOCK \
  -p org.ntust.app.tigerduck --es at "2026-09-09T10:44" --ez frozen true
adb shell am broadcast -a org.ntust.app.tigerduck.debug.CLEAR_CLOCK \
  -p org.ntust.app.tigerduck
```

`--es at` takes `YYYY-MM-DDTHH:MM[:SS]`, the same string with a space
instead of the `T`, or a bare `YYYY-MM-DD` (midnight), always interpreted
in **Asia/Taipei** — matching what the in-app picker means. `--el instant
<epochMillis>` is accepted too and wins if both are given.

`am broadcast` reports delivery, not what the receiver decided, so a
malformed timestamp looks like success on the command line. The script
reads the receiver's own log line back and fails if it's missing; if you
broadcast by hand, check it yourself:

```bash
adb logcat -d -s DebugClock:V | tail -1
```

**How this differs from `maybe_preset_clock`** (the prompt the `install-*`
scripts offer): that one writes `shared_prefs/debug_clock.xml` directly and
force-stops the app, so the override is picked up by
`DebugClockController.bootstrap()` on the *next* launch. Use it to set a
clock before the app has ever started. The broadcast goes through
`DebugClockController.setOverride()` — the same entry point the settings
screen uses — so it also mirrors to a paired watch and reschedules every
alarm, widget and Live Update against the new clock, live. Use it for
anything scripted.

Release builds have no such receiver: the `<receiver>` lives in
`app/src/debug/AndroidManifest.xml`, which is merged only into
`playDebug` / `fdroidDebug`.

## Wireless ADB recipe

```bash
# Pair once (Android 11+):
#   Settings → Developer options → Wireless debugging → Pair device with pairing code
adb pair  <phone-ip>:<pair-port>    <code>
adb connect <phone-ip>:<connect-port>

adb devices                                        # confirm phone listed

./debug/install-play.sh                            # build + push the APK

adb logcat -c && adb logcat \
  Push.Register:V Push.FcmBootstrap:V \
  FcmService:V FirebaseMessaging:I *:S
```

For a watch, repeat the pair/connect over ADB-over-Bluetooth or its own
Wi-Fi pair flow, then re-run `./debug/install-play.sh` and answer "Y" to
the wear prompt.

## Local push backend

The backend repo (`tigerduck-app/tigerduck-backend`) lives outside this tree.
Clone it next to this repo and run it the way its README describes: with
`TIGERDUCK_ENV=development` in its `.env`, `./start.sh` brings the stack up
under Docker Compose and publishes the backend on host port `40000`.

```bash
cd ../tigerduck-backend
./start.sh                                        # up + status block
docker compose exec backend curl -sS localhost:40000/health
./logs.sh                                         # follow the logs
./stop.sh                                         # down, volume kept
```

The Android side reads the dev backend URL from `local.properties` (root of
this repo, gitignored), key `pushBaseUrl`, baked into `BuildConfig` for
`debug` builds. It defaults to `http://10.0.2.2:40000/v3` — the emulator's
loopback to the host — so a physical phone needs
`pushBaseUrl=http://<laptop-LAN-IP>:40000/v3`. The `release` block uses the
`PUSH_BASE_URL` env var, then `pushBaseUrlRelease`, then
`https://api.tigerduck.app/v3`. No shared secret is involved any more.

Settings → Other settings → API endpoint overrides that URL at run time, in
every build, for both push registration and bulletins — handy for switching
backends without a rebuild.

## Cleartext HTTP

Production network security pins the NTUST hosts and forbids cleartext. The
debug variant overrides that with `app/src/debug/res/xml/network_security_config.xml`,
whose `<base-config>` permits cleartext to any host that has no
`<domain-config>` of its own, so a LAN backend at any address works with
nothing to edit. The pinned NTUST hosts and the app's own backend domain keep
`cleartextTrafficPermitted="false"` even in debug. Release builds use the
locked-down `app/src/main/` file.

## Push smoke test

With the `playDebug` build installed (`./debug/install-play.sh`), signed in,
and Wi-Fi sharing the laptop's network:

1. Confirm registration: the backend log should show
   `POST /v3/devices/register` answering 200.

2. Seed bulletins for the dispatcher to send. The backend ships a script for
   exactly this: it inserts already-classified rows (no LLM call) that the
   next dispatcher tick fans out to every matching device.

   ```bash
   cd ../tigerduck-backend
   docker compose exec backend python scripts/seed_test_bulletins.py
   # --clear deletes earlier seeded rows first
   ```

3. On the next dispatcher tick the phone should display a notification on
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

- **Phone never receives push, but registration succeeded** → backend log
  will say `fcm.using_recording_sender` instead of `fcm.using_real_sender`.
  Check that `server/secrets/fcm_service_account.json` exists in the backend
  checkout and `TIGERDUCK_FCM_PROJECT_ID` in its `.env` matches that file's
  `project_id` field. Restart the stack (`./stop.sh && ./start.sh`) after
  fixing.

- **Debug clock override seems stuck on** → it persists across app restarts
  by design. Open Settings → Developer → "Use fake time" → toggle off, or
  tap **Reset**. Worst case, clear the app's `debug_clock` prefs:
  `adb shell run-as <applicationId> rm shared_prefs/debug_clock.xml`.
