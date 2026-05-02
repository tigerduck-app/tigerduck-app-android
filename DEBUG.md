# Debugging & build variants

The app ships in two **distribution flavors** crossed with the standard
**debug / release** build types, so there are four variants:

| Variant | Distribution channel | FCM push | Cleartext to dev backend | Use when |
| --- | --- | --- | --- | --- |
| `playDebug` | Sideload + dev | Yes | Yes (one LAN IP allowlisted — see *Cleartext HTTP* below) | Day-to-day local dev with the laptop backend. Default in Android Studio after the flavor refactor. |
| `playRelease` | Google Play Store | Yes | No | Producing the Play Store APK / bundle. |
| `fdroidDebug` | Sideload of the F-Droid build | No | Yes | Smoke-testing the FOSS variant locally. |
| `fdroidRelease` | F-Droid (anti-features-clean) | No | No | The artifact F-Droid's buildserver actually produces. |

`fdroid*` builds get an `applicationIdSuffix` of `.fdroid`, so they install
side-by-side with the play build. They contain **zero Firebase / Google Play
Services classes** (verified via `aapt2 dump xmltree`). Bulletins still work
on F-Droid via manual refresh / pull-to-refresh; there is just no real-time
push.

## Build / install commands

```bash
# Pick a variant by name. CamelCase is what Gradle expects.
./gradlew :app:assemblePlayDebug          # APK only
./gradlew :app:installPlayDebug           # build + push to connected device

./gradlew :app:assembleFdroidDebug
./gradlew :app:installFdroidDebug

./gradlew :app:assemblePlayRelease        # signed only when KEYSTORE_PASSWORD is set
./gradlew :app:assembleFdroidRelease
```

In Android Studio: **Build → Select Build Variant…** → pick `playDebug` (or
whichever) before pressing Run. The default "Build APKs" / hammer button
assembles every variant; that's fine but slower than asking for one.

## Wireless ADB recipe

```bash
# Pair once (Android 11+):
#   Settings → Developer options → Wireless debugging → Pair device with pairing code
adb pair  <phone-ip>:<pair-port>    <code>
adb connect <phone-ip>:<connect-port>

adb devices                                        # confirm phone listed

./gradlew :app:installPlayDebug                    # push the APK

adb logcat -c && adb logcat \
  TigerDuck-Push:V Push.Register:V \
  TigerDuck-Bulletin:V FirebaseMessaging:I *:S
```

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

With the `playDebug` build installed, signed in, and Wi-Fi sharing the
laptop's network:

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
