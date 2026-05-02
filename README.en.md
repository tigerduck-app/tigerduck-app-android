<div align="center">
<img width="2000" src="https://github.com/user-attachments/assets/cf6a1d18-a348-4b83-adfd-81c6dc82855f" />
<br>

[![License](https://img.shields.io/github/license/tigerduck-app/tigerduck-app-android?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-10.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge)](https://developer.android.com/compose)

[繁體中文](README.md) | **English**

</div>

## Overview

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/0557da5b-f168-48b1-ab88-5f038ede7642">
  <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
  <img align="right" width="323" height="682" alt="Dark" src="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
</picture>

TigerDuck is a campus companion app built by a group of students at **NTUST**.  
It was created to solve common pain points: scattered resources, delayed notifications, and unintuitive interfaces.  
Ever used [TAT](https://github.com/morris13579/tat_ntust)? We're working hard to make TigerDuck feel even more OAO!

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

### 🏛️ **Library** (Experimental)
- Instant library entry QR code with zero delay

### 🌏 **Multilingual**
- **50+ locales shared with the iOS client** — follow the system language or set per-app
- Course / classroom names **automatically abbreviated** when long

### 🎨 **Customization**
- Add what you want, remove what you don't
- Editable tabs, freely add/remove home sections, accent color theming

<br clear="right"/>

## Roadmap

### 🎓 Academics & Learning
- [x] **Assignments** — Fully automatic Moodle assignment sync
- [x] **Assignments+** — Push and ongoing notifications
- [x] **Class Table** — Fetched from the course enrollment system
- [x] **Class Table+** — Editable course names, deletable courses
- [x] **Calendar** — Aggregated events from school announcements, Moodle, etc.
- [x] **Historical GPA & Rankings** — Per-semester / cumulative / per-course grades + interactive charts
- [ ] **Graduation Credit Calculator** — Check completion status for general education categories, college / department credits, PE, Chinese, English, and other requirements

### 📝 Course Enrollment
- [ ] **Course Search** — Display GPA alongside results for better enrollment decisions
- [ ] **Lottery Probability & Preference Suggestions** — Estimate admission odds based on capacity and current enrollment

### 📚 Library Services
- [x] **Library Entry QR Code** — Quick access to the library entry QR code
- [ ] **Study Room Booking** — Reserve and check availability of library study rooms
- [ ] **NTUST Library Events** — Event registration and lookup (campus network required)

### 📣 Campus Information
- [X] **Department & Office Announcements** — Aggregated announcements
- [X] **LLM-classified bulletins + subscriptions** — Server-side classification & de-duplication, subscribable categories, unread filter
- [ ] **Scholarships** — Filterable by eligibility (low-income, indigenous, etc.)
- [ ] **Daily Club Activities** — Curated daily club event listings
- [ ] **Empty Classroom Finder** — Quickly find currently available classrooms

### 🍱 Campus Life
- [ ] **Free Lunch Notifications** — Anyone can register (real-name); aggregates info from NTUST and NTU with push notifications

### 🌏 Localization & Accessibility
- [x] **Multilingual (50+ locales, shared with iOS)** — Follows system language or per-app override
- [x] **Course / Classroom name abbreviations** — One-tap toggle, fully reversible
- [X] **RTL layout fixes** — Arabic / Hebrew and other right-to-left scripts

## System Requirements
| Item | Requirement |
|------|-------------|
| OS | Android 10 (API 29) or later |
| SSO Account | Student account (required for some features) |
| Library | Library account (required for some features) |


<br/><br/>

---

<br/><br/>

## Development Setup
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android Studio](https://img.shields.io/badge/Android%20Studio-Latest-3DDC84?style=for-the-badge&logo=androidstudio&logoColor=white)](https://developer.android.com/studio)

### Prerequisites
- Android Studio (latest preferred)
- Android SDK Platform 36
- JDK 11

### Android App
```bash
# Clone the repository (with submodules: localization, name-abbr)
git clone --recurse-submodules https://github.com/tigerduck-app/tigerduck-app-android.git
cd tigerduck-app-android

# Already cloned without --recurse-submodules? Pull them in:
git submodule update --init --recursive

# Open in Android Studio, or build directly with Gradle.
# There are two product flavors — fdroid and play — pick one:
./gradlew :app:assembleFdroidDebug   # or :app:assemblePlayDebug
./gradlew :app:installFdroidDebug    # or :app:installPlayDebug
```

> 💡 Course / classroom abbreviations (`name-abbr/`) and localization strings (`localization/generated/android/`) come from submodules. **Always** initialize submodules before opening Android Studio, otherwise the build will fail to locate resource files.

### Localization (shared with iOS)

Translation strings live in the [`localization/`](https://github.com/tigerduck-app/app-translation) submodule and are shared with the iOS client.

- Source files in `localization/source/` — 50+ locales (`en.json`, `zh-Hant.json`, `ja.json`, `ko.json`, `ar.json`, …)
- Generated outputs in `localization/generated/`:
  - Android: `android/values/strings.xml` (Traditional Chinese as default), `android/values-<lang>/strings.xml`
  - iOS: `ios/<lang>.lproj/Localizable.strings`
- The Android app's `app/src/main/res/values*/strings.xml` is overwritten by the same script — **do not** edit generated files by hand.

Run a one-shot sync:

```bash
python3 tools/localization/sync_localizations.py
```

The Android build wires this in automatically (`preBuild` depends on `syncLocalizations`), so editing `localization/source/*.json` regenerates Android/iOS outputs before each build.

For new locales or strings, open a separate PR against the [`localization/`](https://github.com/tigerduck-app/app-translation) submodule — do **not** edit generated files.

### Course Name Abbreviations

The [`name-abbr/`](https://github.com/tigerduck-app/name-abbr) submodule ships shared course / classroom abbreviation dictionaries used by both the Android and iOS apps to keep long names readable.

## Project Structure

```text
tigerduck-app-android/                  # Android App (Kotlin 2.3 / Compose / API 26+)
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/org/ntust/app/tigerduck/
│       ├── auth/                       # NTUST SSO authentication, login state
│       ├── data/
│       │   ├── cache/                  # File cache
│       │   ├── local/                  # Room data layer
│       │   ├── model/                  # Domain / DTO models
│       │   └── preferences/            # App preferences and credential vault (EncryptedSharedPreferences)
│       ├── di/                         # Hilt modules
│       ├── liveactivity/               # Live activity / ongoing notification
│       ├── network/                    # Class table / Moodle / bulletins / library APIs
│       │   └── model/
│       ├── notification/               # Assignment due notification scheduling
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
│       │   │   ├── settings/           # Settings (language, tabs, notifications, live activity, source)
│       │   │   └── onboarding/         # First-run onboarding flow
│       │   ├── theme/                  # Tokens, palette, visual presets
│       │   └── AppState.kt
│       ├── widget/                     # Home screen widgets
│       ├── MainActivity.kt
│       └── TigerDuckApp.kt
├── gradle/
│   └── libs.versions.toml              # Version Catalog
├── localization/                       # ⤴ git submodule: 50+ locale translations
├── name-abbr/                          # ⤴ git submodule: course / classroom abbreviations
├── tools/localization/                 # Translation sync script (auto-triggered by preBuild)
├── build.gradle.kts
└── settings.gradle.kts
```

## Contributing
Pull requests and issues are welcome!

Before submitting, please make sure to:
1. Follow the existing Kotlin / Compose code style and architectural conventions
2. Run at least `:app:compileFdroidDebugKotlin` / `:app:compilePlayDebugKotlin` or `:app:assembleFdroidDebug` / `:app:assemblePlayDebug` once
3. Name your branch using `feature/your-feature` or `fix/your-fix`
4. Target the `dev` branch when opening a PR, and enable Copilot review
5. For translation strings, open a separate PR against the [`localization/`](https://github.com/tigerduck-app/app-translation) submodule — do **not** edit generated files

## License
This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
