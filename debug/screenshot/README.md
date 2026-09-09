# Store screenshots

Everything needed to shoot the images in
`fastlane/metadata/android/<locale>/images/`.

```
debug/screenshot/
├── README.md              this
├── screenshot.sh          the interactive menu
├── fixture.example.json   template for the fake data
├── fixture.json           yours; the script offers to create it from the example
└── out/                   captures (gitignored)
```

Only `out/` is gitignored. `fixture.json` is left tracked-able on purpose —
commit yours if you want the next release's screenshots to be reproducible.

**The script does not drive the app.** You navigate on the device and press
Enter to capture. What it automates is the *state* worth photographing — a
made-up timetable, a made-up student ID, a chosen library QR payload, a frozen
clock, a clean status bar — because that is the part that is tedious and
easy to get wrong by hand.

That also means widgets and the watch's tile and complication are capturable
the same way as everything else: they render in the launcher and the watch
face carousel, where no automated test could reach them, but a screencap
does not care.

## Quick start

```bash
./debug/screenshot/screenshot.sh
```

1. Pick the device.
2. `8` — build and install a debug APK (skip if one is already on there;
   `run-as` in step 2 needs a **debug** build).
3. `2` — load the fake data. It offers to create `fixture.json` from the
   example on first run; edit that, then choose `2` again.
4. `5` — clean up the status bar.
5. `1` — navigate on the device, press Enter for each shot.
6. `3` — put the real data back when you're done.

Shots land in `out/<timestamp>/<model>-<size>-<dpi>/`.

## The menu

| # | Does |
|---|---|
| 1 | Capture loop. Enter shoots, `<name>`+Enter names the file, `q` returns to the menu. Files are numbered `01`, `02`, … With a watch selected one Enter shoots **both**, under the same number, into each device's own folder. |
| 2 | Push `fixture.json` into the app and load it, on every selected device. Asks which language variant. Restarts the apps so it takes hold. |
| 3 | Undo it: demo mode off, overrides dropped, real hand-added courses restored. |
| 4 | The **app's** clock — ongoing class, next class, Live Update, widgets. Delegates to `../set-clock.sh`. Blank input clears it. |
| 5 | The **status bar** clock — SystemUI demo mode. Fixed time, full battery and signal, no notification icons. Off again on exit, even if the script is killed. |
| 6 | The **system** clock. Only offered where `date -s` actually works: a device with `su`, or a userdebug/eng build. Offers to open Settings → Date & time otherwise. |
| 7 | Wi-Fi and mobile data off, device-wide. A `demoMode` fixture already does this per-app, so you rarely need it. |
| 8 | Build and install `playDebug` or `fdroidDebug`. |
| 9 | Re-pick the phone and the watch. |

## Three clocks, and only two of them work everywhere

This trips people up, so: they are independent.

| What you want to change | Menu | Works where? |
|---|---|---|
| The clock drawn in the status bar | 5 | Anywhere |
| What the app thinks "now" is | 4 | Anywhere |
| The device's real system clock | 6 | Only with `su`, or a userdebug/eng build |

If you want the class table to show a class in progress, you want **4**. If
you want the status bar to read a tidy 09:30, you want **5**. Setting the real
system clock is almost never what you actually needed.

**Being an emulator is not enough for 6**, which is what the menu used to
assume. An AVD created with the Play Store is a production image: `ro.build.type`
is `user`, `adb root` answers *"adbd cannot run as root in production builds"*,
there is no `su`, and `date -s` is denied exactly as on a retail phone. Make the
AVD from a plain **Google APIs** image (no Play Store) and it works. Otherwise
the menu offers to open **Settings → Date & time**, having turned automatic time
off first — which is what makes the manual Date and Time rows tappable.

## fixture.json

Copy `fixture.example.json` and edit. **Every key is optional** — omit a
section and that part of the app is left alone.

| Key | Effect |
|---|---|
| `demoMode` | Every outbound request is refused, so nothing overwrites the fake data, and onboarding is treated as complete. Read once at process start — hence the restart. |
| `studentId` | Shown on the class table and in Settings. Display only. |
| `libraryQr.content` | Encoded into the library QR. Any string; a URL is the point. |
| `libraryQr.fakeLoggedIn` | Renders the library screen as signed in, so the QR actually appears. |
| `courses[]` | The timetable. |
| `assignments[]` | The to-do list on Home. |
| `bulletins[]` | The announcements list. |
| `calendar[]` | Calendar events. |

A course:

```json
{
  "courseNo": "CS3005301",
  "name":       { "zh": "資料結構", "en": "Data Structures" },
  "instructor": { "zh": "王小明",   "en": "Wang Hsiao-Ming" },
  "credits": 3,
  "classroom": "TR-513",
  "colorHex": "#E57373",
  "schedule": { "1": ["2", "3", "4"], "3": ["6", "7"] },
  "classroomMap": { "3-6": "TR-215", "3-7": "TR-215" }
}
```

- `schedule` keys are the weekday, `1` = Monday … `7` = Sunday. Values are
  period codes.
- `classroomMap` keys are `"weekday-period"`, for a course that changes room
  partway through the day. Optional; `classroom` covers the common case.

### Bilingual fields, and when they are resolved

`name` and `instructor` take either a plain string or a `{"zh": …, "en": …}`
pair. Assignments accept the same shape, but the shipped example is
English-only, which is what the listing wants.

**The language is chosen when the fixture is loaded, not when the screen is
drawn.** Names go into the cache as plain strings, so switching the app's
language afterwards cannot retranslate them. To shoot both languages:

1. Switch the app's language.
2. Load the fixture again (menu `2`), answering `zh` or `en`.
3. Capture.

Leaving the language answer blank uses whatever the app's current language is,
which is usually what you want.

If a fixture only has one of the two languages, the other one is used rather
than leaving the field blank — a course row in the wrong language is obvious
on sight, whereas a blank one just looks like a bug in the app.

## Without the script

```bash
P=org.ntust.app.tigerduck; F=/data/user/0/$P/files/fixture.json
adb shell "run-as $P sh -c 'cat > $F'" < fixture.json
adb shell am broadcast -a org.ntust.app.tigerduck.debug.LOAD_FIXTURE \
  -p $P --es file $F --es lang zh
adb shell am force-stop $P && adb shell am start -n $P/org.ntust.app.tigerduck.MainActivity

adb shell am broadcast -a org.ntust.app.tigerduck.debug.CLEAR_FIXTURE -p $P
adb logcat -s DebugFixture      # watch it land
```

`run-as` into the app's own internal files dir, rather than `adb push` to
`/data/local/tmp`: SELinux denies an app reading a shell-owned file there.
`run-as` needs a debug build.

## Promoting the shots

Nothing is ever written into `fastlane/` — a half-finished run must not be
able to overwrite what is live on the store. Move the keepers by hand into:

```
fastlane/metadata/android/<zh-TW|en-US>/images/<phone|sevenInch|tenInch>Screenshots/
```

Wear shots go in `wearScreenshots/`.

## The watch

The script asks for a phone first and then offers whatever watch is also
attached, so the pair is chosen once at startup. Everything that sets state —
the fixture, the radios — goes to both, and one Enter in the capture loop
shoots both under the same number, into a folder per device:

```
out/20260910-005856/
├── sdk_gphone16k_arm64-1080x2400-420dpi/01-home.png
└── sdk_gwear_arm64-480x480-320dpi/01-home.png
```

Pose the phone on one page and the watch on another, then press Enter once.
The watch's virtual pass is page 0 of its pager and the app opens on page 1,
so swipe **right** to reach it.

Declining the watch leaves everything exactly as it was with one device.

It is the same fixture file, and the watch takes only the `libraryQr` and
`studentId` keys from it.

Everything else the watch draws — the timetable, the accent colour, the
"logged in" state — is mirrored from the phone over the Wearable Data Layer,
so loading the fixture on the phone has already put the fake courses on the
watch. The virtual pass is the exception: the watch fetches its own QR from
`api.lib.ntust.edu.tw` with credentials mirrored from the phone, so without a
real library account that page can only render "open TigerDuck on your phone".
`WearFixtureStore` overrides the payload and the username so it renders a pass
instead. Mirroring a fake credential would not work — the watch would make a
real call with it and show the error.

The pass page is `FLAG_SECURE` by default, so `screencap` returns black. The
phone's screen-capture toggle turns that off and the setting rides the same
Data Layer sync.

Demo mode and the status bar demo (menu `5`) are phone-only: SystemUI demo
mode is not implemented on Wear, and the watch does not talk to a server for
anything the fixture covers, so there is nothing there to cut off. The app
clock (menu `4`) needs no watch handling either — `DebugClockListener` mirrors
the phone's override over the Data Layer.

Two gotchas:

- `logcat -s WearFixture` on the watch, not `DebugFixture`.
- The watch APK carries the **same applicationId** as the play phone build, so
  a bare `./gradlew :wear:installDebug` with both a phone and a watch attached
  installs the watch app over the phone one. Use `ANDROID_SERIAL=<watch>`, or
  menu `8`, which builds and installs `:app` to the phone and `:wear` to the
  watch, each by serial.

## Troubleshooting

**`run-as` fails.** You have a release APK installed. Menu `8`.

**The fake timetable vanished after a few seconds.** A background sync
replaced it. Set `"demoMode": true` in the fixture, or use menu `7`.

**Menu 6 says this device won't let adb set the clock, but it's an emulator.**
It is a Play Store AVD, so it is a production image — see "Three clocks" above.
Take the offer to open Settings → Date & time, or rebuild the AVD from a
Google APIs image.

**Cutting the network killed adb.** The device was connected over wireless
adb. Plug in over USB first; the script warns before letting you do it.

**The QR shows the sign-in form instead.** Set
`"libraryQr": { "fakeLoggedIn": true }`.

**Nothing happened at all.** `adb logcat -s DebugFixture` — a bad `courseNo`,
an unparseable `due` date, or malformed JSON is logged and skipped there.

## How this stays out of release builds

`DebugFixtureReceiver` is declared in `app/src/debug/AndroidManifest.xml`, so
the component does not exist in `playRelease` / `fdroidRelease` — verified with
`aapt2 dump xmltree`. The overrides it writes are read from `BuildConfig.DEBUG`
branches, which R8 folds away.

The student ID and the library QR are **display-only** overrides. The real
`CredentialManager` fields are never touched: every network call keys off the
stored student ID, so writing a fake one would break the signed-in session the
screenshots are being taken from and leave the install needing a re-login.

Courses the user added by hand live in a durable file no fetch can rebuild.
`saveCourses` rewrites that file from whatever it is handed, so the receiver
stashes the real ones before writing the fixture and restores them on clear.
See the upgrade-safe-persistence skill for why none of this hand-writes the
cache format.
