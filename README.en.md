<div align="center">
<img width="2000" src="https://github.com/user-attachments/assets/cf6a1d18-a348-4b83-adfd-81c6dc82855f" />
<br>

[![License](https://img.shields.io/github/license/tigerduck-app/tigerduck-app-android?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-10.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge)](https://developer.android.com/compose)

<a href="https://play.google.com/store/apps/details?id=org.ntust.app.tigerduck">
  <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80">
</a>

<a href="https://f-droid.org/packages/org.ntust.app.tigerduck.fdroid">
  <img alt="Get it on F-Droid" src="https://f-droid.org/badge/get-it-on.png" height="80">
</a>

[繁體中文](README.md) | **English**

</div>

## Overview

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/0557da5b-f168-48b1-ab88-5f038ede7642">
  <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
  <img align="right" width="323" height="682" alt="Dark" src="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
</picture>

TigerDuck is a campus companion app built by a group of students at **NTUST**.  
It was created to solve common pain points: scattered resources, delayed notifications, and
unintuitive interfaces.  
Ever used [TAT](https://github.com/morris13579/tat_ntust)? We're working hard to make TigerDuck feel
even more OAO!

> The project is under active development; some features are still being polished.

### 📚 **Assignments**

- See how many **assignments are still due** at a glance
- **Fully automatic** sync of assignments and deadlines from Moodle — no more surprise due dates!
- **Ongoing notifications** and push alerts — don't wait until the last hour for Moodle's reminder

### 📋 **Class Table**

- Synced directly from the course enrollment system — no more **Moodle delay**
- Interactive Time Slider — see exactly where your next class is

### 📊 **GPA & Rankings**

- Per-semester / cumulative GPA, rankings, and per-course grades in one place
- Interactive charts to track grade trends over time

### 🗓️ **Calendar**

- Aggregates the school's ICS calendar with Moodle deadlines
- Month view, date navigation, pull-to-refresh

### 📢 **Bulletins**

- Every department / center announcement aggregated into one feed
- Backend LLM auto-classifies and de-duplicates; subscribe to categories and filter to unread
- Operators can push one-off alerts to all devices (toggle in Settings)

### 🏛️ **Library** (Experimental)

- Instant library entry QR code with zero delay
- **Flip-to-open**: leave the phone face-down and it jumps straight to the entry QR
- Login / QR and other sensitive screens auto-enable `FLAG_SECURE` to block screenshots and screen recording

### 🌏 **Multilingual**

- **65 locales shared with the iOS client** — follow the system language or set per-app
- Course / classroom names **automatically abbreviated** when long

### 🎨 **Customization**

- Add what you want, remove what you don't
- Editable tabs, freely add/remove home sections, accent color theming
- Per-scenario haptics — toggle each one independently (including the flip-to-QR gesture)

### 🔄 **Auto-Update** (Play only)

- Play In-App Update FLEXIBLE flow built in — proactively prompts when a new version ships
- Post-upgrade "What's new" dialog highlights what changed at a glance

### ⌚ **Wear OS** (Play only)

- **Now & Next** main screen: current / upcoming class with an in-progress progress bar
- **Today** list and per-course detail screen
- **Tile** and **Complication** to surface the next class on the watch face
- Auto-syncs schedule, locale, and accent color from the phone over the Wearable Data Layer
- Tap the empty state on the watch to open TigerDuck on the paired phone

<br clear="right"/>

## Roadmap

### 🎓 Academics & Learning

- [x] **Assignments** — Fully automatic Moodle assignment sync
- [x] **Assignments+** — Push and ongoing notifications
- [x] **Class Table** — Fetched from the course enrollment system
- [x] **Class Table+** — Editable course names, deletable courses
- [x] **Calendar** — Aggregated events from school announcements, Moodle, etc.
- [x] **Historical GPA & Rankings** — Per-semester / cumulative / per-course grades + interactive
  charts
- [ ] **Graduation Credit Calculator** — Check completion status for general education categories,
  college / department credits, PE, Chinese, English, and other requirements

### 📝 Course Enrollment

- [ ] **Course Search** — Display GPA alongside results for better enrollment decisions
- [ ] **Lottery Probability & Preference Suggestions** — Estimate admission odds based on capacity
  and current enrollment

### 📚 Library Services

- [x] **Library Entry QR Code** — Quick access to the library entry QR code
- [x] **Flip-to-open QR** — Place the phone face-down to jump straight to the entry QR
- [ ] **Study Room Booking** — Reserve and check availability of library study rooms
- [ ] **NTUST Library Events** — Event registration and lookup (campus network required)

### 📣 Campus Information

- [X] **Department & Office Announcements** — Aggregated announcements
- [X] **LLM-classified bulletins + subscriptions** — Server-side classification & de-duplication,
  subscribable categories, unread filter
- [X] **Server-push alerts** — One-off notifications sent manually by operators; toggleable in
  Settings
- [ ] **Scholarships** — Filterable by eligibility (low-income, indigenous, etc.)
- [ ] **Daily Club Activities** — Curated daily club event listings
- [ ] **Empty Classroom Finder** — Quickly find currently available classrooms

### 🍱 Campus Life

- [ ] **Free Lunch Notifications** — Anyone can register (real-name); aggregates info from NTUST and
  NTU with push notifications

### 🌏 Localization & Accessibility

- [x] **Multilingual (65 locales, shared with iOS)** — Follows system language or per-app override
- [x] **Course / Classroom name abbreviations** — One-tap toggle, fully reversible
- [X] **RTL layout fixes** — Arabic / Hebrew and other right-to-left scripts

### 🔔 Notifications & Privacy

- [x] **TigerDuck-branded notification icons + per-scenario haptics**
- [x] **Bulletin notification channels** (sound / silent) — adjustable independently
- [x] **In-App Update + What's new dialog** — Play build prompts on new releases
- [x] **`FLAG_SECURE` on sensitive screens** — Login and library QR block screenshots /
  screen recording
- [x] **Account deletion entry** — Request deletion of the server-side push identity from
  Settings

### ⌚ Wear OS (Play only)

- [x] **Now & Next main screen** — Current / next class with an in-progress progress bar
- [x] **Today list + course detail**
- [x] **Tile and Complication** — Surface the next class directly on the home screen / watch face
- [x] **Phone ↔ Watch sync** — Schedule, auth state, locale, and accent color over the Wearable Data
  Layer
- [x] **Empty-state wake** — Tap on the watch to open TigerDuck on the phone

## System Requirements

| Item        | Requirement                                                              |
|-------------|--------------------------------------------------------------------------|
| OS          | Android 10 (API 29) or later                                             |
| Wear OS     | Wear OS 4 (API 30) or later, paired with the Play build of the phone app |
| SSO Account | Student account (required for some features)                             |
| Library     | Library account (required for some features)                             |

<br/><br/>

---

<br/><br/>

## Development Setup

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android Studio](https://img.shields.io/badge/Android%20Studio-Latest-3DDC84?style=for-the-badge&logo=androidstudio&logoColor=white)](https://developer.android.com/studio)

### Prerequisites

- Android Studio (latest preferred)
- Android SDK Platform 37
- JDK 21 (Gradle 9.7 / AGP 9.3 require JDK 17+)

### Android App

```bash
# Clone the repository (with submodules: app-translation, name-abbr)
git clone --recurse-submodules https://github.com/tigerduck-app/tigerduck-app-android.git
cd tigerduck-app-android

# Already cloned without --recurse-submodules? Pull them in:
git submodule update --init --recursive

# Open in Android Studio, or build directly with Gradle.
# There are two product flavors — fdroid and play — pick one:
./gradlew :app:assembleFdroidDebug   # or :app:assemblePlayDebug
./gradlew :app:installFdroidDebug    # or :app:installPlayDebug
```

> 💡 Course / classroom abbreviations (`name-abbr/`) and localization strings (
`app-translation/generated/android/`) come from submodules. **Always** initialize submodules before
> opening Android Studio, otherwise the build will fail to locate resource files.

### Wear OS App (`:wear` module)

The Wear app is **Play only**: it shares `applicationId = org.ntust.app.tigerduck` with the Play
phone build, and shares `Course` / `PeriodTimes` / `NextClassResolver` / `AppClock` with the phone
via the new `:shared` module.

```bash
./gradlew :wear:assembleDebug
./gradlew :wear:installDebug   # requires a Wear OS emulator or paired watch
```

> ⚠️ The wear app depends on `play-services-wearable` (GMS) for pairing, which is incompatible with
> F-Droid policy — there is **no F-Droid variant** of the wear app, and it will not appear on F-Droid.

### Quick install scripts (`debug/`)

Three install scripts under `debug/` filter by `ro.build.characteristics` so they push each APK to
the right device, which is especially handy when a phone and watch are connected at the same time:

| Script                            | What it does                                                                                            |
|-----------------------------------|---------------------------------------------------------------------------------------------------------|
| `./debug/install-fdroid.sh`       | Build + install `:app:fdroidDebug`                                                                      |
| `./debug/install-play.sh`         | Build + install `:app:playDebug`, optionally pushing `:wear:debug` to a paired watch in the same run    |
| `./debug/install-play-release.sh` | Build + install `:app:playRelease` (and optionally `:wear:release`) — for testing R8 / signing behavior |

For the full picture of build variants, the debug clock override (time-travel testing), wireless
ADB, push backend wiring, and common pitfalls, see [`debug/DEBUG.md`](debug/DEBUG.md).

### Localization (shared with iOS)

Translation strings live in the [`app-translation/`](https://github.com/tigerduck-app/app-translation)
submodule and are shared with the iOS client.

- Source files in `app-translation/source/` — 55 files (`en.json`, `zh-Hant.json`, `ja.json`,
  `ko.json`, `ar.json`, …)
- Generated outputs in `app-translation/generated/`:
    - Android: `android/values/strings.xml` (Traditional Chinese as default),
      `android/values-<lang>/strings.xml`
    - iOS: `ios/<lang>.lproj/Localizable.strings`
- The Android app's `app/src/main/res/values*/strings.xml` is overwritten by the same script — **do
  not** edit generated files by hand.

Run a one-shot sync:

```bash
python3 tools/localization/sync_localizations.py
```

The Android build wires this in automatically (`preBuild` depends on `syncLocalizations`), so
editing `app-translation/source/*.json` regenerates Android/iOS outputs before each build.

For new locales or strings, open a separate PR against the
[`app-translation/`](https://github.com/tigerduck-app/app-translation) submodule — do **not** edit
generated files.

### Course Name Abbreviations

The [`name-abbr/`](https://github.com/tigerduck-app/name-abbr) submodule ships shared course /
classroom abbreviation dictionaries used by both the Android and iOS apps to keep long names
readable.

## Project Structure

```text
tigerduck-app-android/                  # Android App + Wear OS (Kotlin 2.4 / Compose / API 29+)
├── app/                                # Phone app (fdroid / play flavors)
│   ├── build.gradle.kts
│   └── src/main/java/org/ntust/app/tigerduck/
│       ├── announcements/              # Bulletin feed, LLM categories, subscription rules
│       ├── auth/                       # NTUST SSO authentication, login state
│       ├── data/
│       │   ├── cache/                  # File cache
│       │   ├── local/                  # Room data layer
│       │   ├── model/                  # Domain / DTO models
│       │   └── preferences/            # App preferences and credential vault (EncryptedSharedPreferences)
│       ├── debug/                      # Developer tools incl. debug clock + API endpoint override
│       ├── di/                         # Hilt modules
│       ├── liveactivity/               # Live activity / ongoing notification
│       ├── network/                    # Class table / Moodle / bulletins / library APIs
│       │   └── model/
│       ├── notification/               # Assignment due notification scheduling + channels
│       ├── push/                       # FCM registration + server-push API client
│       ├── sensor/                     # Flip-to-library detector
│       ├── serverpush/                 # Server-push popup coordinator + intent token
│       ├── ui/
│       │   ├── component/              # Shared composables
│       │   ├── navigation/             # NavHost / tab navigation
│       │   ├── screen/                 # Screens and ViewModels
│       │   │   ├── home/               # Home (Time Slider, assignments, customizable sections)
│       │   │   ├── classtable/         # Class table
│       │   │   ├── calendar/           # Calendar
│       │   │   ├── library/            # Library
│       │   │   ├── score/              # Historical GPA & rankings
│       │   │   ├── more/               # "More" hub
│       │   │   ├── settings/           # Settings (language, tabs, notifications, haptics, server push, live activity, source)
│       │   │   ├── whatsnew/           # "What's new" dialog
│       │   │   └── onboarding/         # First-run onboarding + privacy gate
│       │   ├── theme/                  # Tokens, palette, visual presets
│       │   └── AppState.kt
│       ├── update/                     # In-App Update gate + What's new repository
│       ├── widget/                     # Home screen widgets
│       ├── MainActivity.kt
│       └── TigerDuckApp.kt
├── shared/                             # Phone + watch shared module (`:shared`)
│   └── src/main/java/org/ntust/app/tigerduck/shared/
│       ├── clock/                      # AppClock abstraction (overridable by debug clock)
│       └── …                           # Course / PeriodTimes / CourseScheduleUtils / NextClassResolver
├── wear/                               # ⌚ Wear OS app (Play only, `:wear`)
│   └── src/main/java/org/ntust/app/tigerduck/wear/
│       ├── ui/                         # Now & Next / Today / course detail / settings
│       ├── tile/                       # NextClassTileService
│       ├── complication/               # NextClassComplicationService
│       └── data/                       # DataLayerListener / SchedulePersistence / Repository / SyncRequester
├── debug/                              # Quick install scripts and [DEBUG.md](debug/DEBUG.md) (build variants, debug clock, push)
├── gradle/
│   └── libs.versions.toml              # Version Catalog
├── app-translation/                    # ⤴ git submodule: 65 locale translations (incl. `watch_*` keys)
├── name-abbr/                          # ⤴ git submodule: course / classroom abbreviations
├── tools/localization/                 # Translation sync script (auto-triggered by preBuild)
├── build.gradle.kts
└── settings.gradle.kts
```

## Contributing

Pull requests and issues are welcome!

Before submitting, please make sure to:

1. Follow the existing Kotlin / Compose code style and architectural conventions
2. Run at least `:app:compileFdroidDebugKotlin` / `:app:compilePlayDebugKotlin` or
   `:app:assembleFdroidDebug` / `:app:assemblePlayDebug` once
3. Name your branch using `feature/your-feature` or `fix/your-fix`
4. Target the `dev` branch when opening a PR, and enable Copilot review
5. For translation strings, open a separate PR against the
   [`app-translation/`](https://github.com/tigerduck-app/app-translation) submodule — do **not** edit
   generated files

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
