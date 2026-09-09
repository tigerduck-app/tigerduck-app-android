---
name: generating-release-notes
description: Use when the user asks to generate, draft, or write release notes for a TigerDuck Android version (e.g. "write release notes for v1.4.3", "draft v1.5.0 notes", "generate the changelog for the next release"). Produces bilingual zh-TW / en-US release notes matching the established format used from v1.3.x onward on GitHub Releases. Enforces natural Taiwan Mandarin translation (no word-by-word literal translation) and requires the user to specify what to emphasize before drafting.
---

# Generating Release Notes

This skill drafts bilingual release notes for TigerDuck Android in the
established format used on GitHub Releases (`gh release view v1.4.2`,
`v1.4.1`, `v1.4.0`, `v1.3.x` are the canonical references).

Output is **always reviewed by the user before publishing** — never call
`gh release create` or `gh release edit --notes` directly. Print the
drafted notes in the chat and let the user paste them into the GitHub
Release web UI (or hand back a file they can pipe).

## Required workflow (do every step in order)

### 1. Establish version scope

Ask or confirm:

- **Target tag** (e.g. `v1.4.3`) — what version is being released
- **Previous tag** (e.g. `v1.4.2`) — what we're diffing against

If the user didn't say, infer from `git tag --list 'v*' --sort=-version:refname | head -3` and
`git log --oneline <prev>..HEAD` and confirm.

### 2. Gather the actual changes

Don't invent items. Pull from real sources, in this order:

```bash
# Commits since previous tag
git log --oneline <prev-tag>..HEAD

# Merged PRs in the window (when richer context is needed)
gh pr list --state merged --base main --limit 50 --json number,title,mergedAt,body

# versionCode / versionName bumps
git diff <prev-tag>..HEAD -- app/build.gradle.kts wear/build.gradle.kts | grep -E "versionCode|versionName"
```

If a commit message or PR body is unclear, **read the diff** before
writing the bullet — don't guess from the title.

### 3. ASK what to emphasize — REQUIRED

Before drafting, ask the user one question via `AskUserQuestion`:

> What should the summary paragraph emphasize for this release?

Don't draft the opening summary until they answer. The summary is the
first thing readers see and should foreground what the user thinks is
the headline of the release (a flagship feature, a critical hotfix, a
platform expansion, etc.) — not whatever's mechanically biggest in the
commit log.

If the release is a pure hotfix, also confirm whether to lead with
"this is a hotfix for X" framing (see v1.4.1 / v1.4.2 hotfix notes for
the established tone).

### 4. Draft following the template

See the **Template** section below. Use the section emoji conventions
in the **Emoji vocabulary** table.

### 5. Translate naturally — read the Translation Principles section

This is where past drafts have gone wrong. Read that section before
writing any zh-TW string.

### 6. Show the draft, don't publish it

Output the full markdown to chat. Wait for user edits. Only after the
user explicitly says "publish" / "looks good, ship it" do you offer to
write it to a file or use `gh release edit <tag> --notes-file -`.

## Template

```markdown
<one-paragraph zh-TW summary>

<one-paragraph en-US summary>

注意 / Notes:
- <upgrade notes — required if there's any upgrade-path nuance>
  <english line>
- 不同安裝渠道 (APK, F-Droid, Play Store) 由於 Sign Key 不同，將無法互相更新。
  Different distribution channels (APK / F-Droid / Play Store) use different signing keys and cannot upgrade across each other.
- 由於 F-Droid 應用程式商店規定不能使用 FireBase 相關服務，F-Droid 版本將無法獲得即時公告推送通知。
  Due to FireBase related services not being usable in apps published on F-Droid, the F-Droid flavor will not receive push notifications for Announcements.
- 由於 F-Droid 應用程式商店規定不能使用 GSM 套件，F-Droid 版本將不支援 Wear OS。
  Due to GSM package not being usable in apps published on F-Droid, the F-Droid flavor will not have Wear OS support.

---

## ✨ 新功能 / New Features

### <emoji> <zh section title> / <en section title>

- **<zh bullet headline>** — <zh detail sentence>
  **<en bullet headline>** — <en detail sentence>

## 🐛 修正 / Fixes

### <emoji> <zh section title> / <en section title>

- **<zh bullet>** — <zh detail>
  **<en bullet>** — <en detail>

## 🔧 改善與優化 / Improvements

<same shape>

## 🏗️ 架構與相依升級 / Architecture & Dependency Upgrades

<same shape>

## 🔖 版本 / Version

- **手機：`versionCode` X → Y, `versionName` A.B.C → A.B.D**
  **Phone: `versionCode` X → Y, `versionName` A.B.C → A.B.D**
- **手錶：`versionCode` X → Y, `versionName` A.B.C → A.B.D**
  **Watch: `versionCode` X → Y, `versionName` A.B.C → A.B.D**

## 🔁 比較 / Diff

- **完整變更紀錄：** https://github.com/tigerduck-app/tigerduck-app-android/compare/<prev>...<new>
  **Full changelog:** https://github.com/tigerduck-app/tigerduck-app-android/compare/<prev>...<new>
```

### Rules for the template

- **Bilingual line pattern:** `**zh headline** — zh sentence` on one line,
  then `**en headline** — en sentence` on the next. No blank line between
  the two languages within a single bullet.
- **Drop sections that don't apply.** A pure hotfix has no `✨ 新功能`.
  A feature release with no dep bumps has no `🏗️ 架構與相依升級`.
- **Drop the `注意 / Notes` bullet about Wear OS / F-Droid push** if the
  release predates Wear OS (pre-v1.4.0) or has no F-Droid-relevant
  change. Keep the signing-key bullet on every release.
- **Watch versionCode/versionName line:** only include for releases that
  ship a `:wear` change. v1.3.x releases didn't have it.

## Emoji vocabulary

Use these for section / sub-section headers. Pick the one that already
exists in prior releases over inventing new ones.

| Emoji         | When to use                      |
|---------------|----------------------------------|
| `✨ 新功能`       | Top-level new-features section   |
| `🐛 修正`       | Top-level fixes section          |
| `🔧 改善與優化`    | Top-level improvements section   |
| `🏗️ 架構與相依升級` | Top-level architecture / deps    |
| `🔖 版本`       | Version-bump footer              |
| `🔁 比較`       | Diff link footer                 |
| `⌚`           | Anything Wear OS / watch         |
| `📚`          | Schedule / 衝堂 / class-table      |
| `🏫`          | Classroom / room-resolution      |
| `📋`          | Course detail / popup            |
| `🏠`          | Home screen                      |
| `🚪`          | Onboarding                       |
| `⚙️`          | Settings                         |
| `🔔` / `📢`   | Notifications / push / bulletins |
| `📨`          | Moodle                           |
| `🔐`          | Auth / login / security          |
| `🧱`          | Widgets                          |
| `♿`           | Accessibility / TalkBack         |
| `⏱️`          | Live Activity / scheduling       |
| `🌐`          | Localization / i18n              |
| `🧹`          | Cleanup / cache / data           |
| `🚨`          | Crash hotfix                     |
| `🛡️`         | Runtime guards / safety nets     |
| `🧪`          | Debug / developer tooling        |
| `🛠️`         | Internal tooling                 |

## Translation principles (Taiwan Mandarin)

The user is a native Taiwan Mandarin speaker. Past drafts have shipped
literal word-by-word translations that read as unnatural Chinglish. The
rule: **translate the meaning of the sentence, not each word.** If a
direct character mapping produces something a Taiwanese reader would
never say, pick the natural phrasing instead — even if it diverges from
the English structure.

### Hard rules

- **Traditional characters only** (繁體中文). Never `优化` (use `優化`),
  never `软件` (use `軟體`), never `菜单` (use `選單`).
- **Use Taiwan-standard tech vocabulary**: `軟體` (not `軟件`), `程式`
  (not `程序`), `資料` (not `數據`), `網路` (not `網絡`),
  `預設` (not `默認`), `設定` (not `設置`), `通知` (not `通告`),
  `應用程式 / App` (not `应用`), `登入 / 登出` (not `登錄`).
- **Keep technical terms in English when natural**: `padding`, `Widget`,
  `Tile`, `Complication`, `Gson`, `R8`, `Moodle`, `QR Code`, `TalkBack`,
  `OkHttp`, `Compose`, `KSP`, etc. The codebase audience knows them;
  forced Chinese translations like `填充` for `padding` or
  `小部件` for `widget` look worse, not better.
- **App-internal proper nouns in zh-TW**: `課表`, `首頁`, `時間軸`,
  `滑桿`, `引導頁`, `設定`, `衝堂`, `加課搜尋`, `課程詳情`. Match
  what the in-app UI string actually says.
- **No `Co-Authored-By: Claude` trailer** if the user asks you to commit
  the notes file — per global CLAUDE.md rule.

### Bad → good examples

These are real corrections from past releases. Pattern-match against
them — if your draft has the same shape as the "bad" column, rewrite.

| Bad (literal)                       | Good (natural Taiwan Mandarin)   | Why                                                                                                                         |
|-------------------------------------|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| 全螢幕 QR **尊重** padding 邊界            | 全螢幕 QR **使用** padding 邊界         | `respects` ≠ `尊重`; `尊重` reads as "to respect a person/opinion". The intent is "honors the setting" → `使用` (or `套用` / `依照`). |
| 修正手錶全螢幕掃碼畫面**忽略** padding 設定**的問題** | 修正手錶全螢幕掃碼畫面**未套用** padding 設定的問題 | `ignored` → `未套用` is more natural for a bug description than the literal `忽略`.                                              |
| **記住**科系篩選                          | **記憶**科系篩選 / **保留**科系篩選          | `remember` as a setting-state verb is usually `記憶` or `保留`, not the conversational `記住`.                                    |
| 滑桿**揭露**所有衝堂時段                      | 滑桿**展開**所有衝堂時段 / **顯示**所有衝堂時段    | `expose` is a CS metaphor; `揭露` means "reveal a scandal". Use `展開` or `顯示`.                                                 |
| 課程**標題與**課程名稱解碼 HTML entities       | 課程標題**和**課程名稱解碼 HTML entities    | Stacked `與` reads stiff. One `和` flows better in spoken Mandarin.                                                           |
| **觸發**搜尋從第 1 個字                     | 輸入第 1 個字即開始搜尋                    | Don't preserve English word order; rewrite as the natural Chinese clause shape (subject → time → action).                   |
| 在**對應的**時段顯示**正確的**教室               | 該時段對應的教室                         | Don't double-translate `correct` + `corresponding`. One adjective is enough.                                                |
| 重設計**了** / 修正**了** in bullets       | 重設計 / 修正                         | Bullet-style zh-TW drops the perfective `了` — it's prose-style.                                                             |
| 同步**與** Apple 版本                    | 與 Apple 版本同步                     | Don't transliterate `synced with X` as `同步與 X`; the `與` goes before the verb in Chinese.                                    |

### Quick self-check before submitting a zh-TW line

Read it aloud (mentally). If your reaction is "no Taiwanese person would
phrase it this way", rewrite. Common smell tests:

- Does it contain `了` in a section that's noun-phrase-style? Drop the `了`.
- Does it use a literal English-cognate verb (`尊重`, `揭露`, `觸發`) where a domain verb (`使用`,
  `展開`, `啟動`) is the natural choice?
- Did you keep English word order (Subject-Verb-PrepPhrase) instead of Chinese topic-comment?
  Restructure.
- Did you translate every adjective in the English bullet? Chinese tolerates fewer modifiers; drop
  redundant ones.

## Section ordering convention

Top-level sections in this order, omitting any that are empty:

1. `## ✨ 新功能 / New Features`
2. `## 🐛 修正 / Fixes`
3. `## 🔧 改善與優化 / Improvements`
4. `## 🏗️ 架構與相依升級 / Architecture & Dependency Upgrades`
5. `## 🔖 版本 / Version`
6. `## 🔁 比較 / Diff`

Within `新功能` / `修正`, lead with the section that matches what the
summary paragraph emphasized (per step 3). If the user said "the
headline is the Wear OS library QR", `### ⌚` goes first under `✨`.

## Common mistakes

| Mistake                                                                                                 | Fix                                                                                                         |
|---------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Drafting the summary before asking what to emphasize                                                    | Always ask first via `AskUserQuestion`.                                                                     |
| Translating word-by-word so the zh-TW is Chinglish                                                      | Read it aloud; rewrite as a sentence a Taiwanese reader would naturally say.                                |
| Using Simplified Chinese vocabulary (`优化`, `軟件`, `默認`)                                                  | Traditional + Taiwan tech vocab only.                                                                       |
| Inventing changes from imagination                                                                      | Pull from `git log <prev>..HEAD` and PR bodies. If you can't find evidence, drop it.                        |
| Force-translating English tech terms (`填充` for padding, `小部件` for widget)                               | Keep `padding` / `Widget` / `Tile` in English.                                                              |
| Calling `gh release create` / `gh release edit --notes` without the user explicitly approving           | Always show the draft first. Only publish on explicit go-ahead.                                             |
| Omitting the bilingual pair (zh line without en, or vice versa)                                         | Every bullet ships both languages.                                                                          |
| Skipping the Notes block when there's an upgrade-path nuance                                            | If the release changes persistence, auth, or signing, surface the upgrade impact in the `注意 / Notes` block. |
| Including the Wear OS notes bullet on a release that didn't touch `:wear`                               | The bullet is conditional, not boilerplate.                                                                 |
| Adding `🤖 Generated with Claude Code` / `Co-Authored-By: Claude` to a commit that lands the notes file | Global rule: never.                                                                                         |

## When NOT to use this skill

- The user is asking for a commit message or a PR description (different
  format — use the project's PR template).
- The user is asking for in-app announcement text or the App Store
  short / full description (`fastlane/metadata/android/zh-TW/full_description.txt`).
- The user wants a CHANGELOG.md file inside the repo. This project
  publishes notes via GitHub Releases, not via an in-repo changelog.
