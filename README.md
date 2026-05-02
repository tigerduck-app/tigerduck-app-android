<div align="center">
<img width="2000" src="https://github.com/user-attachments/assets/cf6a1d18-a348-4b83-adfd-81c6dc82855f" />
<br>

[![License](https://img.shields.io/github/license/tigerduck-app/tigerduck-app-android?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-10.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge)](https://developer.android.com/compose)

**繁體中文** | [English](README.en.md)

</div>

## 總覽

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/0557da5b-f168-48b1-ab88-5f038ede7642">
  <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
  <img align="right" width="323" height="682" alt="Dark" src="https://github.com/user-attachments/assets/521e2b57-eb1c-46d2-99e8-b16de026578c">
</picture>

TigerDuck 是由一群學生共同開發的校園助手  
為了解決資源零散、通知不及時與介面不直觀等問題  
有用過 [TAT](https://github.com/morris13579/tat_ntust) 嗎，我們努力把 TigerDuck 做得更 OAO

> 專案目前持續開發中，部分功能仍在完善與調整。

### 📚 **作業**
- 一眼就知道還有多少**作業沒有繳交**
- **全自動**從 Moodle 同步作業與截止日期，再也不被教授偷襲！
- **進行中通知**與訊息提醒，別等到最後一小時才收到 Moodle 的通知

### 📋 **課表**
- 從選課系統同步，不用再**等 Moodle 延遲**
- 互動式時間軸滑條，下一節課在哪一目了然！

### 📊 **歷年成績**
- 學期 / 累計 GPA、排名、各科成績一次看完
- 互動式圖表追蹤成績走勢

### 🗓️ **行事曆**
- 整合校方 ICS 行程與 Moodle 作業截止
- 月曆檢視、切換日期、下拉同步

### 🏛️ **圖書館**（實驗性）
- 秒開入館 QR-Code，無任何延遲

### 🌏 **外觀**
- 與 iOS **共用 50+ 種語系翻譯**，自行設定或跟著系統語言切換
- 名字過長？課程 / 教室名稱**自動簡寫**

### 🎨 **客製化**
- 要就加，不要就刪掉
- 編輯 Tab、首頁區塊自由增減、選擇主題色

<br clear="right"/>

## 開發規劃

### 🎓 教務與學習
- [x] **作業** – 全自動同步 Moodle 作業
- [x] **作業+** – 訊息與進行中通知
- [x] **課表** – 擷取自選課系統
- [x] **課表+** – 可修改的課程名稱、可刪除的課程
- [x] **行事曆** – 整合校公告、Moodle 等行程資訊
- [x] **歷年 GPA 與排名查詢** – 學期 / 累計 / 各科成績 + 互動式圖表
- [ ] **畢業門檻學分計算** – 各通識向度、院 / 系學分、體育、國文、英文等檢核

### 📝 選課相關
- [ ] **選課查詢** – 同時顯示 GPA，提升選課決策效率
- [ ] **中籤機率估算與志願序建議** – 根據人數上限與目前選課人數估算

### 📚 圖書館服務
- [x] **圖書館出入館 QR-Code** – 快速開啟入館 QR-Code
- [ ] **圖書館討論小間借用** – 支援討論室預約與借用查詢
- [ ] **臺科大圖書館講座活動** – 包含活動報名與查詢（需校內連線）

### 📣 校園資訊
- [X] **各處室、中心公告** – 支援公告整合
- [X] **公告 LLM 分類 + 訂閱通知** – 後端自動分類去重、可訂閱類別、未讀篩選
- [ ] **獎學金資訊** – 支援 Filter，可依低收、中低收、原住民等條件過濾
- [ ] **當日社團活動** – 整理每日社團活動資訊
- [ ] **空教室查詢** – 快速查詢目前可使用的教室

### 🍱 校園生活
- [ ] **免費便當通知** – 任何人可實名登記，並整合台科大、台大相關資訊，主動推播通知

### 🌏 在地化與無障礙
- [x] **多語系（與 iOS 共用，50+ 語系）** – 跟著系統或在 App 內單獨切換
- [x] **課程 / 教室名稱簡稱** – 一鍵切換、可還原
- [X] **RTL 版面修正** – 阿拉伯語 / 希伯來語等右至左語系排版

## 系統需求
| 項目 | 需求 |
|------|------|
| 作業系統 | Android 10（API 29）以上 |
| SSO 帳號 | 學生帳號（部分功能需要）|
| 圖書館 | 圖書館帳號（部分功能需要）|


<br/><br/>

---

<br/><br/>

## 開發環境建置
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android Studio](https://img.shields.io/badge/Android%20Studio-Latest-3DDC84?style=for-the-badge&logo=androidstudio&logoColor=white)](https://developer.android.com/studio)

### 需求
- Android Studio（建議最新版）
- Android SDK Platform 36
- JDK 11

### Android App
```bash
# clone 專案（含子模組：localization、name-abbr）
git clone --recurse-submodules https://github.com/tigerduck-app/tigerduck-app-android.git
cd tigerduck-app-android

# 已經 clone 過的話，補抓子模組
git submodule update --init --recursive

# 以 Android Studio 開啟，或用 Gradle 直接 build
# 目前有 fdroid 與 play 兩個 product flavor，請擇一
./gradlew :app:assembleFdroidDebug   # 或 :app:assemblePlayDebug
./gradlew :app:installFdroidDebug    # 或 :app:installPlayDebug
```

> 💡 課程/教室簡稱（`name-abbr/`）與多語系字串（`localization/generated/android/`）皆由子模組提供，clone 後**務必**先抓子模組再開 Android Studio，否則 build 會找不到資源檔。

### 多語系翻譯（Android + iOS 共用）

翻譯字串放在 [`localization/`](https://github.com/tigerduck-app/app-translation) 子模組，與 iOS 共用。

- 翻譯原始檔在 `localization/source/`，共 50+ 種語系（`en.json`、`zh-Hant.json`、`ja.json`、`ko.json`、`ar.json` …）
- 共用翻譯輸出在 `localization/generated/`：
  - Android：`android/values/strings.xml`（繁中預設）、`android/values-<lang>/strings.xml`
  - iOS：`ios/<lang>.lproj/Localizable.strings`
- Android App 使用的 `app/src/main/res/values*/strings.xml` 會由同一支腳本同步覆寫，請不要手動改動生成檔。

手動同步一次翻譯：

```bash
python3 tools/localization/sync_localizations.py
```

Android build 已綁定自動同步（`preBuild` 依賴 `syncLocalizations`），只要修改 `localization/source/*.json` 就會在編譯前自動更新 Android/iOS 生成檔。

新語系或字串請對 [`localization/`](https://github.com/tigerduck-app/app-translation) 子模組另開 PR，**不要**直接改生成檔。

### 課程名稱簡稱

[`name-abbr/`](https://github.com/tigerduck-app/name-abbr) 子模組提供與 iOS 共用的課程 / 教室簡稱字典，避免長名稱破版。

## 專案架構

```text
tigerduck-app-android/                  # Android App（Kotlin 2.3 / Compose / API 26+）
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/org/ntust/app/tigerduck/
│       ├── auth/                       # NTUST SSO 認證、登入狀態
│       ├── data/
│       │   ├── cache/                  # 檔案快取
│       │   ├── local/                  # Room 資料層
│       │   ├── model/                  # Domain / DTO 模型
│       │   └── preferences/            # App 偏好與憑證管理（EncryptedSharedPreferences）
│       ├── di/                         # Hilt 模組
│       ├── liveactivity/               # 即時動態 / 進行中通知
│       ├── network/                    # 課表 / Moodle / 公告 / 圖書館 API
│       │   └── model/
│       ├── notification/               # 作業到期通知排程
│       ├── ui/
│       │   ├── component/              # 共用 Composable
│       │   ├── navigation/             # NavHost / Tab navigation
│       │   ├── screen/                 # 各頁面與 ViewModel
│       │   │   ├── home/               # 首頁（時間滑條、作業、區塊客製化）
│       │   │   ├── classtable/         # 課表
│       │   │   ├── calendar/           # 行事曆
│       │   │   ├── library/            # 圖書館
│       │   │   ├── score/              # 歷年成績與排名
│       │   │   ├── more/               # 「更多」聚合頁
│       │   │   ├── settings/           # 設定（語言、Tab、通知、即時動態、來源碼）
│       │   │   └── onboarding/         # 初次使用引導
│       │   ├── theme/                  # 主題、配色、視覺預設
│       │   └── AppState.kt
│       ├── widget/                     # 桌面 widget
│       ├── MainActivity.kt
│       └── TigerDuckApp.kt
├── gradle/
│   └── libs.versions.toml              # Version Catalog
├── localization/                       # ⤴ git submodule：50+ 語系翻譯
├── name-abbr/                          # ⤴ git submodule：課程 / 教室簡稱字典
├── tools/localization/                 # 翻譯同步腳本（preBuild 自動觸發）
├── build.gradle.kts
└── settings.gradle.kts
```

## 貢獻
歡迎 PR 與 Issue！

送出前請確認
1. 遵循現有的 Kotlin / Compose 程式碼風格與架構慣例
2. 至少完成一次 `:app:compileFdroidDebugKotlin` / `:app:compilePlayDebugKotlin` 或 `:app:assembleFdroidDebug` / `:app:assemblePlayDebug`
3. 以 `feature/your-feature` 或 `fix/your-fix` 命名分支
4. 發布 PR 時，目標分支為 `dev`，且必須勾選 Copilot 做 Revise
5. 翻譯字串請改 [`localization/`](https://github.com/tigerduck-app/app-translation) 子模組（透過獨立 PR），不要直接改生成檔

## 授權
本專案採用 [GNU Affero General Public License v3.0](LICENSE) 授權。
