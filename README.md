<div align="center">
<img width="2000" src="https://github.com/user-attachments/assets/cf6a1d18-a348-4b83-adfd-81c6dc82855f" />
<br>

[![License](https://img.shields.io/github/license/tigerduck-app/tigerduck-app-android?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge)](https://developer.android.com/compose)

**TigerDuck Android**

</div>

## 總覽

<!-- <img align="right" width="330" alt="portrait" src="https://github.com/user-attachments/assets/c2b24060-969a-4132-bb72-347d9227f1b4" /> -->
<img align="right" width="260" alt="screenshot-portrait" src="https://github.com/user-attachments/assets/21c7e61d-d2a4-4103-aa23-dddc275aa582" />

# TODO: 更新截圖

TigerDuck 是由學生共同開發的台科大校園助手 Android App。  
目標是把課表、作業、行事曆、公告、圖書館等零散服務整合在同一個流暢的原生介面中。

> 專案目前持續開發中，部分功能仍在完善與調整。

### 📚 作業

- 從 Moodle 同步作業與截止時間
- 首頁快速查看近期截止項目
- 作業到期本機推播通知提醒
- 課表中有作業的科目顯示書籍圖示

### 📋 課表

- 從選課系統同步修課資訊
- 顯示今日課程、學分總覽與色彩區分課程
- 時光機：互動式時間軸滑條，以流動軌道呈現今日行程

### 🗓️ 行事曆

- 整合校方 ICS 行程與 Moodle 作業截止
- 支援月曆檢視、切換日期、下拉同步

### 🏛️ 圖書館

- 圖書館帳號登入
- 即時產生入館 QR Code，含自動更新倒數

### ⚙️ 設定與客製化

- NTUST / 圖書館帳號管理
- 主題色、作業時間顯示格式、公告篩選記憶等偏好設定
- 開啟連結方式（系統瀏覽器 / App 內瀏覽器）
- 時間滑條樣式與方向設定
- 作業到期通知開關
- 關於頁面：版本資訊、問題回報、隱私權政策、開源授權

## 功能規劃（Roadmap）

> 以下為規劃方向，實際優先順序可能調整。

### 🎓 教務與學習

- [x] Moodle 作業同步
- [x] 課表同步（選課系統）
- [x] 行事曆整合（校方 + Moodle）
- [x] 歷年 GPA / 排名查詢
- [ ] 畢業門檻學分檢核

### 📚 圖書館服務

- [x] 入館 QR Code
- [ ] 討論小間借用
- [ ] 圖書館講座資訊

### 📣 校園資訊

- [ ] 公告查詢與篩選
- [ ] 獎學金資訊整合
- [ ] 空教室查詢

## 系統需求

| 項目          | 需求                         |
| ------------- | ---------------------------- |
| Android 版本  | Android 8.0 (API 26) 以上    |
| 編譯 SDK      | Android API 36               |
| 開發工具      | Android Studio（建議最新版） |
| Java / Kotlin | Java 11 / Kotlin 2.3.0       |
| 帳號          | NTUST 學號（部分功能）       |
| 圖書館        | 圖書館帳號（部分功能）       |

## 開發環境建置

### 需求

- Android Studio
- Android SDK Platform 36
- JDK 11

### 下載與執行

```bash
git clone https://github.com/tigerduck-app/tigerduck-app-android.git
cd tigerduck-app-android
```

```bash
sh ./gradlew :app:assembleDebug
```

```bash
sh ./gradlew :app:installDebug
```

也可直接使用 Android Studio 開啟專案並執行 `app` 模組。

### 多語系翻譯（Android + iOS 共用）

- 翻譯原始檔在 `localization/source/`：
  - `en.json`
  - `zh-Hant.json`
- 共用翻譯輸出在 `localization/generated/`：
  - Android：`android/values/strings.xml`（繁中預設）、`android/values-en/strings.xml`
  - iOS：`ios/en.lproj/Localizable.strings`、`ios/zh-Hant.lproj/Localizable.strings`
- Android App 使用的 `app/src/main/res/values*/strings.xml` 會由同一支腳本同步覆寫，請不要手動改動生成檔。

手動同步一次翻譯：

```bash
python3 tools/localization/sync_localizations.py
```

產出位置：

- 共用翻譯（可獨立推送）：`localization/generated/android/*`、`localization/generated/ios/*`
- Android App 目標檔：`app/src/main/res/values/strings.xml`（繁中預設）、`app/src/main/res/values-en/strings.xml`

此外，Android build 已綁定自動同步（`preBuild` 依賴 `syncLocalizations`），只要修改 `localization/source/*.json` 就會在編譯前自動更新 Android/iOS 生成檔。

## 技術棧

| 層級     | 技術                                |
| -------- | ----------------------------------- |
| UI       | Jetpack Compose + Material 3        |
| 架構     | MVVM                                |
| DI       | Hilt                                |
| 網路     | OkHttp + Gson                       |
| 本地資料 | Room + JSON Cache                   |
| 憑證儲存 | EncryptedSharedPreferences          |
| 非同步   | Kotlin Coroutines                   |
| 建置系統 | Gradle Kotlin DSL + Version Catalog |

## 認證流程（摘要）

1. 使用者輸入學號與密碼。
2. App 透過 NTUST SSO / OIDC 流程建立登入狀態。
3. Moodle、課表、行事曆等功能共用已建立的 Session。
4. 帳密以 `EncryptedSharedPreferences` 保存。
5. 圖書館系統使用獨立 token 流程。

## 專案結構

```text
tigerduck-app-android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/org/ntust/app/tigerduck/
│       ├── auth/               # 登入狀態與驗證流程
│       ├── data/
│       │   ├── cache/          # 檔案快取
│       │   ├── local/          # Room 資料層
│       │   ├── model/          # Domain / DTO model
│       │   └── preferences/    # App 偏好與憑證管理
│       ├── di/                 # Hilt 模組
│       ├── network/            # 課表 / Moodle / 公告 / 圖書館 API
│       │   └── model/
│       ├── notification/       # 作業到期通知排程
│       ├── ui/
│       │   ├── component/      # 共用 Composable
│       │   ├── navigation/     # NavHost / Tab navigation
│       │   ├── screen/         # 各頁面與 ViewModel
│       │   ├── theme/          # 主題與色彩
│       │   └── AppState.kt
│       ├── MainActivity.kt
│       └── TigerDuckApp.kt
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

## 貢獻

歡迎 Issue 與 PR。

送出前請確認：

1. 遵循既有 Kotlin / Compose 程式碼風格
2. 至少完成一次 `:app:compileDebugKotlin` 或 `:app:assembleDebug`
3. 分支命名建議使用 `feature/xxx` 或 `fix/xxx`
4. 發布 PR 時，目標分支為 `dev`，且必須勾選 Copilot 做 Revise

## 授權

本專案採用 [GNU Affero General Public License v3.0](LICENSE) 授權。
