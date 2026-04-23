# ClassTable Home Screen Widget — Design Spec

**Date:** 2026-04-23
**Branch:** feature/new-features

---

## Overview

Add home-screen widgets that display the NTUST timetable. Users can place six distinct widget variants — three layouts × two fixed themes — independently on their launcher. All widgets highlight the current ongoing class and stay in sync with any data change made in the app or by the background sync worker.

---

## Section 1 — Architecture & Package Structure

**Technology:** Jetpack Glance (`androidx.glance:glance-appwidget` 1.1.1). Compose-based, consistent with the rest of the codebase.

**New package:** `org.ntust.app.tigerduck.widget/`

```
widget/
  BaseClassTableWidget.kt         abstract GlanceAppWidget; shared provideGlance()
  WidgetLayout.kt                  enum: Week, Today, NextClass
  WidgetState.kt                   data class: courses, ongoingCourseNo, currentWeekday,
                                   currentMinuteOfDay, activeWeekdays, activePeriods, isLoggedIn
  WidgetDataLoader.kt              suspend fun load(context): WidgetState
                                   reads DataCache + computes ongoing class (pure logic,
                                   extracted from ClassTableViewModel.ongoingCourse)
  WidgetUpdater.kt                 @Singleton Hilt class
                                   updateAll(context): refreshes all 6 widgets +
                                   calls WidgetBoundaryScheduler.scheduleForToday()
  WidgetBoundaryScheduler.kt       schedules AlarmManager exact alarms at each class
                                   start/end time today
  WidgetBoundaryReceiver.kt        BroadcastReceiver; fires at class boundaries,
                                   calls WidgetUpdater.updateAll()
  content/
    WeekGridContent.kt             GlanceComposable: Mon–(Fri/Sat/Sun) × periods grid
    TodayListContent.kt            GlanceComposable: today's courses as vertical list
    NextClassContent.kt            GlanceComposable: single ongoing/next-class card
  receivers/
    WeekLightWidgetReceiver.kt
    WeekDarkWidgetReceiver.kt
    TodayLightWidgetReceiver.kt
    TodayDarkWidgetReceiver.kt
    NextClassLightWidgetReceiver.kt
    NextClassDarkWidgetReceiver.kt
```

Six concrete widget classes (`WeekLightWidget`, `WeekDarkWidget`, etc.) each extend `BaseClassTableWidget(layout, isDark)` with a single-line body. Six matching receiver classes each extend `GlanceAppWidgetReceiver` and return the corresponding widget instance. All logic lives in the base class and content composables.

The `ongoingCourse` detection is extracted from `ClassTableViewModel` into a standalone pure function in `WidgetDataLoader` — takes `List<Course>` + current Taipei time, returns `OngoingCourseInfo?`. Both the ViewModel and the widget data loader call this shared function.

**Hilt access from Glance and BroadcastReceiver:** Glance's `provideGlance()` is not a Hilt injection point. `WidgetDataLoader` accesses `DataCache` and `AuthService` via a `@EntryPoint` interface installed in `SingletonComponent`, retrieved with `EntryPointAccessors.fromApplication(context)`. `WidgetBoundaryReceiver` is annotated `@AndroidEntryPoint` — Hilt supports this on `BroadcastReceiver` and injects `WidgetUpdater` directly.

---

## Section 2 — Data Flow & Update Triggers

### Reading

`WidgetDataLoader.load(context)` runs inside `BaseClassTableWidget.provideGlance()` (Glance's coroutine scope):

1. Calls `DataCache.loadCourses()` (current semester)
2. Reads `CredentialManager` / `AuthService` to determine `isLoggedIn`
3. Computes `activeWeekdays`, `activePeriods`, `ongoingCourseNo` using shared pure functions
4. Returns `WidgetState`

### Writing / update triggers

`WidgetUpdater.updateAll()` (no context parameter — it holds `@ApplicationContext` via Hilt injection) is called from two places:

**`BackgroundSyncWorker.doWork()`** — after the existing `liveActivityManager.refresh()` call. `WidgetUpdater` is injected via Hilt alongside existing worker dependencies.

**`ClassTableViewModel`** — after every `dataCache.saveCourses(...)` call (add course, delete course, rename, color change, fetch completion). `WidgetUpdater` is injected into the ViewModel via Hilt.

`WidgetUpdater.updateAll()` does:
1. `GlanceAppWidgetManager.updateAll(context, WidgetClass::class.java)` for each of the 6 classes
2. `WidgetBoundaryScheduler.scheduleForToday(courses)` — sets `AlarmManager` exact alarms at each class start and end time for the rest of today

### Boundary refresh (keeps current-class highlight live)

`WidgetBoundaryReceiver` (`@AndroidEntryPoint`) fires at each alarm. It injects `WidgetUpdater` and calls `updateAll()`, which re-renders all widgets (with an updated `ongoingCourseNo`) and immediately reschedules the next boundary alarm. The alarm chain is self-sustaining from the first `updateAll()` each day.

`updatePeriodMillis = 1800000` (Android's enforced 30-min minimum) acts as a backstop for edge cases.

---

## Section 3 — Widget Content & Sizing

### Six picker entries

| Picker name | Layout | Min size | Resize |
|---|---|---|---|
| Classtable Week (light) | `WeekGridContent` | 250×250dp | horizontal + vertical |
| Classtable Week (dark) | `WeekGridContent` | 250×250dp | horizontal + vertical |
| Classtable Today (light) | `TodayListContent` | 250×110dp | horizontal + vertical |
| Classtable Today (dark) | `TodayListContent` | 250×110dp | horizontal + vertical |
| Next Class (light) | `NextClassContent` | 110×110dp | horizontal + vertical |
| Next Class (dark) | `NextClassContent` | 110×110dp | horizontal + vertical |

### WeekGridContent

Full-week timetable grid matching the in-app ClassTable:
- Columns: active weekdays (Mon–Fri always; Sat/Sun only if courses exist on those days)
- Rows: active period IDs in chronological order
- Course tiles span their contiguous block (rowspan); colored using `customColorHex` or hash-based palette
- Ongoing course tile: distinct highlight border using `WidgetColors.highlight`
- Tap anywhere: `PendingIntent` → `MainActivity` → ClassTable tab

### TodayListContent

Vertical list of today's courses sorted by start time:
- Each row: period range (e.g. "3–4"), time (e.g. "10:20–12:10"), course name, classroom
- Ongoing course row: highlighted background using `WidgetColors.highlight`
- Empty state — no classes today: "今日沒有課"
- Empty state — weekend with no weekend courses: "週末沒有課，週一見！"
- Tap anywhere: `PendingIntent` → `MainActivity` → ClassTable tab

### NextClassContent

Single card:
- **Ongoing class:** course name (large), time range, classroom, period IDs, "進行中" pill, linear progress bar showing elapsed fraction of the block
- **No ongoing class, next class today:** course name, start time, classroom
- **No more classes today:** "今日課程已結束" + tomorrow's first class (name + time)
- **Not logged in:** "請先登入 TigerDuck"
- Tap anywhere: `PendingIntent` → `MainActivity` → ClassTable tab

---

## Section 4 — Theme System

Each widget has a fixed theme set at placement time (not system-adaptive).

```kotlin
data class WidgetColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val highlight: Color,
    val emptyCell: Color,
)

object WidgetTheme {
    val Light = WidgetColors(
        background         = Color(0xFFF5F5F5),
        surface            = Color(0xFFFFFFFF),
        onSurface          = Color(0xFF1C1C1E),
        onSurfaceVariant   = Color(0xFF6E6E73),
        highlight          = Color(0xFF0066CC),
        emptyCell          = Color(0xFFECECEC),
    )
    val Dark = WidgetColors(
        background         = Color(0xFF1C1C1E),
        surface            = Color(0xFF2C2C2E),
        onSurface          = Color(0xFFF5F5F5),
        onSurfaceVariant   = Color(0xFF8E8E93),
        highlight          = Color(0xFF4DA3FF),
        emptyCell          = Color(0xFF2C2C2E),
    )
}
```

Exact values are verified against the app's `colors.xml` and `TigerDuckTheme` during implementation so the widget matches the app's surfaces exactly.

`BaseClassTableWidget` passes `if (isDark) WidgetTheme.Dark else WidgetTheme.Light` to content composables. No system theme queries inside any widget class.

---

## Section 5 — Manifest & Registration

### Dependency additions

`libs.versions.toml`:
```toml
glance = "1.1.1"
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
```

`app/build.gradle.kts`:
```kotlin
implementation(libs.glance.appwidget)
```

### Resource files

6 `res/xml/widget_*_info.xml` provider files, one per widget entry. Key attributes:
- `android:minWidth` / `android:minHeight` per table in Section 3
- `android:resizeMode="horizontal|vertical"`
- `android:updatePeriodMillis="1800000"`
- `android:widgetCategory="home_screen"`
- `android:description` pointing to a string resource for each variant

### AndroidManifest.xml additions

- 6 `<receiver>` entries (one per `*WidgetReceiver` class), each with:
  - `android:exported="true"`
  - `<intent-filter>` for `android.appwidget.action.APPWIDGET_UPDATE`
  - `<meta-data>` pointing to its `res/xml/widget_*_info.xml`
- 1 `<receiver>` entry for `WidgetBoundaryReceiver`:
  - `android:exported="false"`
  - No static intent filter (alarm registered dynamically via `AlarmManager`)

### String resources

New entries in `strings.xml`:
- Widget picker labels: `widget_week_light_label`, `widget_week_dark_label`, etc.
- Widget picker descriptions: `widget_week_light_desc`, etc.
- UI strings: `今日沒有課`, `週末沒有課，週一見！`, `今日課程已結束`, `請先登入 TigerDuck`, `進行中`

---

## Out of scope

- Conflict (L-split) rendering in the week grid widget — too small to be legible; overlapping courses in the same slot are rendered as the first course only
- Widget configuration screen (theme is fixed at placement via picker entry choice)
- iPad / tablet adaptive layouts
