# TigerDuck for Android

A native Android companion app for NTUST (National Taiwan University of Science and Technology) students. Built with Kotlin and Jetpack Compose.

> Please note: This project is currently in early development. Not everything is working in current stage.

## Features

- **課表** — View your weekly class timetable with color-coded courses
- **首頁** — Today's courses, upcoming assignment deadlines, and quick-access widgets
- **行事曆** — Monthly calendar with school events, Moodle assignments, and exam dates
- **公告** — Browse and search NTUST announcements with department filtering
- **圖書館** — Log in to the library system and display your borrowing QR code
- **設定** — Manage NTUST SSO and library accounts, theme color, and display preferences

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Hilt (dependency injection) |
| Local storage | Room (SQLite) |
| Network | OkHttp 3 |
| Credentials | EncryptedSharedPreferences |
| Build | Gradle (Kotlin DSL) + Version Catalog |

## Prerequisites

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK with API 35 (compile) / API 26 minimum
- Gradle 8.9 (handled by the wrapper)

## Getting Started

1. Clone the repo:
   ```bash
   git clone https://github.com/<your-org>/tigerduck-app-android.git
   cd tigerduck-app-android
   ```

2. Open the project in Android Studio.

3. Let Android Studio sync the Gradle project automatically, or run:
   ```bash
   ./gradlew assembleDebug
   ```

4. Run on a physical device or emulator (API 26+).

## Authentication

The app uses the NTUST SSO OIDC bridge to authenticate:

1. User enters their student ID and password in the onboarding screen (or Settings).
2. The app performs the full SSO flow against the NTUST identity provider via OkHttp3.
3. Session cookies are stored in memory and refreshed automatically (1-hour TTL).
4. Credentials (student ID, password) are persisted in `EncryptedSharedPreferences`.

Library login is handled separately via `api.lib.ntust.edu.tw/v1` with its own token lifecycle.

## Project Structure

```
app/src/main/java/com/tigerduck/app/
├── data/
│   ├── cache/          # Gson-based JSON disk cache
│   ├── local/          # Room database, DAOs
│   ├── model/          # Data models (Course, Assignment, …)
│   └── preferences/    # AppPreferences, CredentialManager
├── network/            # OkHttp services (SSO, courses, Moodle, library, ICS)
├── auth/               # AuthService (login / ensureAuthenticated / logout)
├── di/                 # Hilt modules
├── ui/
│   ├── navigation/     # AppNavigation, NavHost, tab routing
│   ├── screen/         # One package per screen (Screen + ViewModel)
│   ├── component/      # Shared composables
│   ├── theme/          # Material 3 theme, course color palette
│   └── AppState.kt     # Singleton app-wide state
├── TigerDuckApp.kt     # @HiltAndroidApp Application class
└── MainActivity.kt     # Single activity entry point
```

## License

MIT License — see [LICENSE](LICENSE) for details.
