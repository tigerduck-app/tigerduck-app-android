# ClassTable Home Screen Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add six home-screen Glance widgets (3 layouts × 2 fixed themes) that display the NTUST timetable, highlight the ongoing class, and stay in sync with app and background-sync data changes.

**Architecture:** Six `GlanceAppWidget` subclasses share a common `BaseClassTableWidget` base. `WidgetDataLoader` reads `DataCache` JSON via a Hilt `@EntryPoint`. `WidgetUpdater` (injected into `BackgroundSyncWorker` and `ClassTableViewModel`) refreshes all widgets and schedules `AlarmManager` boundary alarms at class start/end times via `WidgetBoundaryReceiver`.

**Tech Stack:** Jetpack Glance 1.1.1 (Compose-based widget framework), Hilt DI, AlarmManager, existing DataCache / AuthService / CourseColorPalette.

---

## File Map

**New files — `app/src/main/java/org/ntust/app/tigerduck/`**
- `data/CourseScheduleUtils.kt` — top-level `computeOngoingCourse()` and `parseHm()` (extracted from VM)
- `widget/WidgetLayout.kt` — enum: Week, Today, NextClass
- `widget/WidgetColors.kt` — `WidgetColors` data class, `WidgetTheme` object, `widgetCourseColor()` helper
- `widget/WidgetState.kt` — all display state needed by content composables
- `widget/WidgetDataLoader.kt` — `WidgetEntryPoint` (@EntryPoint) + `WidgetDataLoader` object
- `widget/WidgetBoundaryScheduler.kt` — schedules AlarmManager alarms at class boundaries
- `widget/WidgetUpdater.kt` — @Singleton; calls `updateAll()` on all 6 widgets + boundary scheduler
- `widget/WidgetBoundaryReceiver.kt` — @AndroidEntryPoint BroadcastReceiver
- `widget/BaseClassTableWidget.kt` — abstract GlanceAppWidget
- `widget/receivers/WeekLightWidgetReceiver.kt` — `WeekLightWidget` + `WeekLightWidgetReceiver`
- `widget/receivers/WeekDarkWidgetReceiver.kt`
- `widget/receivers/TodayLightWidgetReceiver.kt`
- `widget/receivers/TodayDarkWidgetReceiver.kt`
- `widget/receivers/NextClassLightWidgetReceiver.kt`
- `widget/receivers/NextClassDarkWidgetReceiver.kt`
- `widget/content/WeekGridContent.kt`
- `widget/content/TodayListContent.kt`
- `widget/content/NextClassContent.kt`

**New resource files**
- `res/xml/widget_week_light_info.xml` … (×6, one per widget entry)

**New test files — `app/src/test/java/org/ntust/app/tigerduck/`**
- `data/CourseScheduleUtilsTest.kt`
- `widget/WidgetBoundarySchedulerTest.kt`

**Modified files**
- `gradle/libs.versions.toml` — add `glance = "1.1.1"` + library entry
- `app/build.gradle.kts` — add `implementation(libs.glance.appwidget)`
- `AndroidManifest.xml` — add 7 receivers
- `res/values/strings.xml` — add widget labels + Mandarin UI strings
- `ui/navigation/AppNavigation.kt` — add `widgetStartRoute` param + `LaunchedEffect` navigation
- `MainActivity.kt` — read intent extra, override `onNewIntent`, pass to `AppNavigation`
- `notification/BackgroundSyncWorker.kt` — inject + call `WidgetUpdater`
- `ui/screen/classtable/ClassTableViewModel.kt` — extract `OngoingCourseInfo`, use shared utils, inject + call `WidgetUpdater`

---

## Task 1: Add Glance dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add Glance version + library to version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
glance = "1.1.1"
```
Under `[libraries]` add:
```toml
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
```

- [ ] **Step 2: Add dependency to app module**

In `app/build.gradle.kts`, inside `dependencies { }`, add after the WorkManager lines:
```kotlin
implementation(libs.glance.appwidget)
```

- [ ] **Step 3: Verify build compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add Jetpack Glance dependency for home screen widgets"
```

---

## Task 2: Extract CourseScheduleUtils + refactor ClassTableViewModel

The `computeOngoingCourse` logic currently lives inline in `ClassTableViewModel`. This task extracts it into a shared file so both the ViewModel and the widget data loader can use it without duplication.

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/data/CourseScheduleUtils.kt`
- Create: `app/src/test/java/org/ntust/app/tigerduck/data/CourseScheduleUtilsTest.kt`
- Modify: `app/src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/org/ntust/app/tigerduck/data/CourseScheduleUtilsTest.kt`:

```kotlin
package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Course
import org.junit.Assert.*
import org.junit.Test

class CourseScheduleUtilsTest {

    @Test
    fun `returns null for empty course list`() {
        assertNull(computeOngoingCourse(emptyList(), weekday = 1, minuteOfDay = 660))
    }

    @Test
    fun `returns ongoing course when current time is within a contiguous block`() {
        // Period 3: 10:20-11:10 (620-670 min), Period 4: 11:20-12:10 (680-730 min)
        // Contiguous block 3+4: startMinute=620, endMinute=730
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(1 to listOf("3", "4")))
        val result = computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 660) // 11:00
        assertNotNull(result)
        assertEquals("CS101", result!!.course.courseNo)
        assertEquals("3", result.firstPeriodId)
        assertEquals(620, result.startMinute)
        assertEquals(730, result.endMinute)
    }

    @Test
    fun `returns null when between two non-contiguous blocks of the same course`() {
        // Period 3 (620-670) and Period 6 (800-850) have a gap; 12:30=750 is in the gap
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(1 to listOf("3", "6")))
        assertNull(computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 750))
    }

    @Test
    fun `returns null when course is on a different weekday`() {
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(2 to listOf("3")))
        assertNull(computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 660))
    }

    @Test
    fun `parseHm converts HH colon MM to total minutes`() {
        assertEquals(620, parseHm("10:20"))
        assertEquals(0,    parseHm("00:00"))
        assertEquals(1439, parseHm("23:59"))
    }

    @Test
    fun `parseHm returns null for null or malformed input`() {
        assertNull(parseHm(null))
        assertNull(parseHm("invalid"))
        assertNull(parseHm(""))
        assertNull(parseHm("10"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.ntust.app.tigerduck.data.CourseScheduleUtilsTest"
```
Expected: compilation error — `computeOngoingCourse` and `parseHm` do not exist yet.

- [ ] **Step 3: Create CourseScheduleUtils.kt**

Create `app/src/main/java/org/ntust/app/tigerduck/data/CourseScheduleUtils.kt`:

```kotlin
package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course

data class OngoingCourseInfo(
    val course: Course,
    val weekday: Int,
    val firstPeriodId: String,
    val startMinute: Int,
    val endMinute: Int,
)

fun computeOngoingCourse(
    courses: List<Course>,
    weekday: Int,
    minuteOfDay: Int,
): OngoingCourseInfo? {
    val order = AppConstants.Periods.chronologicalOrder
    for (course in courses) {
        val periods = course.schedule[weekday]
            ?.sortedBy { order.indexOf(it) } ?: continue
        if (periods.isEmpty()) continue
        var blockStart = 0
        while (blockStart < periods.size) {
            var blockEnd = blockStart
            while (blockEnd + 1 < periods.size &&
                order.indexOf(periods[blockEnd + 1]) == order.indexOf(periods[blockEnd]) + 1
            ) blockEnd++
            val firstId = periods[blockStart]
            val lastId = periods[blockEnd]
            val startMin = parseHm(AppConstants.PeriodTimes.mapping[firstId]?.first)
            val endMin = parseHm(AppConstants.PeriodTimes.mapping[lastId]?.second)
            if (startMin != null && endMin != null && minuteOfDay in startMin..endMin) {
                return OngoingCourseInfo(
                    course = course,
                    weekday = weekday,
                    firstPeriodId = firstId,
                    startMinute = startMin,
                    endMinute = endMin,
                )
            }
            blockStart = blockEnd + 1
        }
    }
    return null
}

fun parseHm(hhmm: String?): Int? {
    hhmm ?: return null
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.ntust.app.tigerduck.data.CourseScheduleUtilsTest"
```
Expected: All 6 tests PASS.

- [ ] **Step 5: Refactor ClassTableViewModel to use shared functions**

In `ClassTableViewModel.kt`:

1. Add these two imports at the top:
```kotlin
import org.ntust.app.tigerduck.data.OngoingCourseInfo
import org.ntust.app.tigerduck.data.computeOngoingCourse
import org.ntust.app.tigerduck.data.parseHm
```

2. **Remove** the entire `data class OngoingCourseInfo(...)` block (lines ~226-233 — the inner data class inside the ViewModel).

3. **Remove** the private `parseHm` function at the bottom of the ViewModel.

4. Replace `val ongoingCourse: OngoingCourseInfo?` computed property body:
```kotlin
val ongoingCourse: OngoingCourseInfo?
    get() {
        val dayTime = _currentDayTime.value
        return computeOngoingCourse(_courses.value, dayTime.weekday, dayTime.minuteOfDay)
    }
```

5. Verify build compiles:
```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/data/CourseScheduleUtils.kt \
        app/src/test/java/org/ntust/app/tigerduck/data/CourseScheduleUtilsTest.kt \
        app/src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt
git commit -m "refactor: extract computeOngoingCourse into CourseScheduleUtils"
```

---

## Task 3: Widget data models

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetLayout.kt`
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetColors.kt`
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetState.kt`

- [ ] **Step 1: Create WidgetLayout.kt**

```kotlin
package org.ntust.app.tigerduck.widget

enum class WidgetLayout { Week, Today, NextClass }
```

- [ ] **Step 2: Create WidgetColors.kt**

`widgetCourseColor` replicates `TigerDuckTheme.resolveForMode()` + the hash-based assignment so widgets work without the in-memory color map.

```kotlin
package org.ntust.app.tigerduck.widget

import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

data class WidgetColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val highlight: Color,
    val emptyCell: Color,
)

object WidgetTheme {
    val Light = WidgetColors(
        isDark             = false,
        background         = Color(0xFFF5F5F5),
        surface            = Color(0xFFFFFFFF),
        onSurface          = Color(0xFF1C1C1E),
        onSurfaceVariant   = Color(0xFF6E6E73),
        highlight          = Color(0xFF0066CC),
        emptyCell          = Color(0xFFECECEC),
    )
    val Dark = WidgetColors(
        isDark             = true,
        background         = Color(0xFF1C1C1E),
        surface            = Color(0xFF2C2C2E),
        onSurface          = Color(0xFFF5F5F5),
        onSurfaceVariant   = Color(0xFF8E8E93),
        highlight          = Color(0xFF4DA3FF),
        emptyCell          = Color(0xFF2C2C2E),
    )
}

/** Resolves a tile color for [course] matching TigerDuckTheme's palette logic. */
fun widgetCourseColor(course: Course, isDark: Boolean): Color {
    val base = course.customColorHex?.let { hex ->
        try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { null }
    } ?: hashPaletteColor(course.courseNo)

    if (!isDark) return base
    val lightIdx = courseColorPalette.indexOfFirst { it == base }
    return if (lightIdx >= 0) courseColorPaletteDark[lightIdx] else base
}

private fun hashPaletteColor(courseNo: String): Color {
    val hash = courseNo.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return courseColorPalette[hash % courseColorPalette.size]
}
```

- [ ] **Step 3: Create WidgetState.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.data.model.Course

data class WidgetState(
    val courses: List<Course>,
    val activeWeekdays: List<Int>,
    val activePeriodIds: List<String>,
    val currentWeekday: Int,
    val currentMinuteOfDay: Int,
    val isLoggedIn: Boolean,
    val ongoingCourseNo: String?,
    val nextCourseTodayNo: String?,
    val tomorrowFirstCourseName: String?,
    val tomorrowFirstCourseTime: String?,
)
```

- [ ] **Step 4: Verify build compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/
git commit -m "feat: add widget data models (WidgetLayout, WidgetColors, WidgetState)"
```

---

## Task 4: WidgetDataLoader

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetDataLoader.kt`

No unit tests — `EntryPointAccessors.fromApplication` requires a real application context (instrumented test territory). The logic is exercised at install-time via manual verification.

- [ ] **Step 1: Create WidgetDataLoader.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.computeOngoingCourse
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.parseHm
import java.util.Calendar

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dataCache(): DataCache
    fun authService(): AuthService
}

object WidgetDataLoader {

    suspend fun load(context: Context): WidgetState {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val courses = entry.dataCache().loadCourses()
        val isLoggedIn = entry.authService().isNtustAuthenticated

        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        val weekday = cal.toWeekday()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val ongoingInfo = computeOngoingCourse(courses, weekday, minuteOfDay)
        val nextCourseTodayNo = computeNextCourseTodayNo(
            courses, weekday, minuteOfDay, ongoingInfo?.course?.courseNo
        )
        val (tomorrowName, tomorrowTime) = computeTomorrowFirst(courses, weekday)

        return WidgetState(
            courses = courses,
            activeWeekdays = computeActiveWeekdays(courses),
            activePeriodIds = computeActivePeriodIds(courses),
            currentWeekday = weekday,
            currentMinuteOfDay = minuteOfDay,
            isLoggedIn = isLoggedIn,
            ongoingCourseNo = ongoingInfo?.course?.courseNo,
            nextCourseTodayNo = nextCourseTodayNo,
            tomorrowFirstCourseName = tomorrowName,
            tomorrowFirstCourseTime = tomorrowTime,
        )
    }

    private fun Calendar.toWeekday(): Int = when (get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        else -> 7
    }

    private fun computeActiveWeekdays(courses: List<Course>): List<Int> {
        val days = courses.flatMap { it.schedule.keys }.toMutableSet()
        val result = (1..5).toMutableList()
        if (6 in days) result.add(6)
        if (7 in days) result.add(7)
        return result
    }

    private fun computeActivePeriodIds(courses: List<Course>): List<String> {
        val ids = AppConstants.Periods.defaultVisible.toMutableSet()
        courses.forEach { course -> course.schedule.values.forEach { ids.addAll(it) } }
        return AppConstants.Periods.chronologicalOrder.filter { it in ids }
    }

    private fun computeNextCourseTodayNo(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        ongoingNo: String?,
    ): String? {
        return courses
            .filter { it.schedule.containsKey(weekday) && it.courseNo != ongoingNo }
            .mapNotNull { course ->
                val firstFutureMinute = course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(AppConstants.PeriodTimes.mapping[pid]?.first) }
                    .filter { it > minuteOfDay }
                    .minOrNull()
                firstFutureMinute?.let { course to it }
            }
            .minByOrNull { it.second }
            ?.first?.courseNo
    }

    private fun computeTomorrowFirst(
        courses: List<Course>,
        todayWeekday: Int,
    ): Pair<String?, String?> {
        val tomorrowWeekday = if (todayWeekday >= 7) 1 else todayWeekday + 1
        val order = AppConstants.Periods.chronologicalOrder
        val course = courses
            .filter { it.schedule.containsKey(tomorrowWeekday) }
            .minByOrNull { c ->
                c.schedule[tomorrowWeekday]!!
                    .minByOrNull { order.indexOf(it) }
                    ?.let { order.indexOf(it) } ?: Int.MAX_VALUE
            } ?: return null to null
        val firstPeriodId = course.schedule[tomorrowWeekday]!!
            .minByOrNull { order.indexOf(it) }!!
        return course.courseName to AppConstants.PeriodTimes.mapping[firstPeriodId]?.first
    }
}
```

- [ ] **Step 2: Verify build compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/WidgetDataLoader.kt
git commit -m "feat: add WidgetDataLoader with Hilt EntryPoint"
```

---

## Task 5: WidgetBoundaryScheduler

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetBoundaryScheduler.kt`
- Create: `app/src/test/java/org/ntust/app/tigerduck/widget/WidgetBoundarySchedulerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/org/ntust/app/tigerduck/widget/WidgetBoundarySchedulerTest.kt`:

```kotlin
package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.data.model.Course
import org.junit.Assert.*
import org.junit.Test

class WidgetBoundarySchedulerTest {

    // Period 3: 10:20–11:10 = 620–670 min

    @Test
    fun `returns class start time when before all classes`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            620,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 500),
        )
    }

    @Test
    fun `returns class end time when currently inside class`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            670,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 650),
        )
    }

    @Test
    fun `returns null when no future boundaries today`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertNull(
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 700),
        )
    }

    @Test
    fun `returns null for empty course list`() {
        assertNull(WidgetBoundaryScheduler.nextBoundaryMinuteAfter(emptyList(), weekday = 1, currentMinute = 0))
    }

    @Test
    fun `ignores courses on other weekdays`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(2 to listOf("3")))
        assertNull(WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 0))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "org.ntust.app.tigerduck.widget.WidgetBoundarySchedulerTest"
```
Expected: compilation error — `WidgetBoundaryScheduler` does not exist yet.

- [ ] **Step 3: Create WidgetBoundaryScheduler.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.parseHm
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetBoundaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleForToday(courses: List<Course>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = makePendingIntent()
        alarmManager.cancel(pi)

        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        val weekday = cal.toWeekday()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nextMinute = nextBoundaryMinuteAfter(courses, weekday, nowMinute) ?: return

        val triggerCal = Calendar.getInstance(AppConstants.TAIPEI_TZ).apply {
            set(Calendar.HOUR_OF_DAY, nextMinute / 60)
            set(Calendar.MINUTE, nextMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerCal.timeInMillis, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerCal.timeInMillis, pi)
        }
    }

    private fun makePendingIntent(): PendingIntent {
        val intent = Intent(context, WidgetBoundaryReceiver::class.java)
            .setAction(ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun Calendar.toWeekday(): Int = when (get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        else -> 7
    }

    companion object {
        internal const val ACTION_BOUNDARY = "org.ntust.app.tigerduck.WIDGET_BOUNDARY"
        internal const val REQUEST_CODE = 9001

        internal fun nextBoundaryMinuteAfter(
            courses: List<Course>,
            weekday: Int,
            currentMinute: Int,
        ): Int? {
            val boundaries = mutableSetOf<Int>()
            for (course in courses) {
                val periods = course.schedule[weekday] ?: continue
                for (periodId in periods) {
                    val times = AppConstants.PeriodTimes.mapping[periodId] ?: continue
                    parseHm(times.first)?.let { boundaries.add(it) }
                    parseHm(times.second)?.let { boundaries.add(it) }
                }
            }
            return boundaries.filter { it > currentMinute }.minOrNull()
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "org.ntust.app.tigerduck.widget.WidgetBoundarySchedulerTest"
```
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/WidgetBoundaryScheduler.kt \
        app/src/test/java/org/ntust/app/tigerduck/widget/WidgetBoundarySchedulerTest.kt
git commit -m "feat: add WidgetBoundaryScheduler with unit tests"
```

---

## Task 6: WidgetUpdater

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetUpdater.kt`

This task creates the `WidgetUpdater` class. The 6 receiver classes it references are created in Task 9 — the class will compile after Task 9 completes.

- [ ] **Step 1: Create WidgetUpdater.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.widget.receivers.NextClassDarkWidget
import org.ntust.app.tigerduck.widget.receivers.NextClassLightWidget
import org.ntust.app.tigerduck.widget.receivers.TodayDarkWidget
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidget
import org.ntust.app.tigerduck.widget.receivers.WeekDarkWidget
import org.ntust.app.tigerduck.widget.receivers.WeekLightWidget
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val boundaryScheduler: WidgetBoundaryScheduler,
) {
    suspend fun updateAll() {
        WeekLightWidget().updateAll(context)
        WeekDarkWidget().updateAll(context)
        TodayLightWidget().updateAll(context)
        TodayDarkWidget().updateAll(context)
        NextClassLightWidget().updateAll(context)
        NextClassDarkWidget().updateAll(context)
        boundaryScheduler.scheduleForToday(dataCache.loadCourses())
    }
}
```

Note: this file references classes that don't exist yet (`WeekLightWidget`, etc.). It will fail to compile until Task 9 is done. That is expected — do not attempt a build between Tasks 6 and 9.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/WidgetUpdater.kt
git commit -m "feat: add WidgetUpdater singleton"
```

---

## Task 7: WidgetBoundaryReceiver + manifest entry

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/WidgetBoundaryReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create WidgetBoundaryReceiver.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WidgetBoundaryReceiver : BroadcastReceiver() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                widgetUpdater.updateAll()
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 2: Add receiver to AndroidManifest.xml**

Inside the `<application>` block, after the existing receivers, add:
```xml
<receiver
    android:name="org.ntust.app.tigerduck.widget.WidgetBoundaryReceiver"
    android:exported="false" />
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/WidgetBoundaryReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add WidgetBoundaryReceiver for class-boundary widget refresh"
```

---

## Task 8: appwidget-provider XMLs and string resources

**Files:**
- Create: `app/src/main/res/xml/widget_week_light_info.xml` (and 5 similar files)
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Create all 6 appwidget-provider XML files**

`app/src/main/res/xml/widget_week_light_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="250dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_week_light_label"
    android:description="@string/widget_week_light_desc" />
```

`app/src/main/res/xml/widget_week_dark_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="250dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_week_dark_label"
    android:description="@string/widget_week_dark_desc" />
```

`app/src/main/res/xml/widget_today_light_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_today_light_label"
    android:description="@string/widget_today_light_desc" />
```

`app/src/main/res/xml/widget_today_dark_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_today_dark_label"
    android:description="@string/widget_today_dark_desc" />
```

`app/src/main/res/xml/widget_next_class_light_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_next_class_light_label"
    android:description="@string/widget_next_class_light_desc" />
```

`app/src/main/res/xml/widget_next_class_dark_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:label="@string/widget_next_class_dark_label"
    android:description="@string/widget_next_class_dark_desc" />
```

- [ ] **Step 2: Add string resources to strings.xml**

Append inside `<resources>` in `app/src/main/res/values/strings.xml`:
```xml
<!-- Widget picker labels and descriptions -->
<string name="widget_week_light_label">Classtable Week (Light)</string>
<string name="widget_week_dark_label">Classtable Week (Dark)</string>
<string name="widget_today_light_label">Classtable Today (Light)</string>
<string name="widget_today_dark_label">Classtable Today (Dark)</string>
<string name="widget_next_class_light_label">Next Class (Light)</string>
<string name="widget_next_class_dark_label">Next Class (Dark)</string>
<string name="widget_week_light_desc">Weekly class schedule, light theme</string>
<string name="widget_week_dark_desc">Weekly class schedule, dark theme</string>
<string name="widget_today_light_desc">Today\'s classes, light theme</string>
<string name="widget_today_dark_desc">Today\'s classes, dark theme</string>
<string name="widget_next_class_light_desc">Next class card, light theme</string>
<string name="widget_next_class_dark_desc">Next class card, dark theme</string>
<!-- Widget UI strings (Mandarin) -->
<string name="widget_no_classes_today">今日沒有課</string>
<string name="widget_no_classes_weekend">週末沒有課，週一見！</string>
<string name="widget_no_more_classes">今日課程已結束</string>
<string name="widget_sign_in">請先登入 TigerDuck</string>
<string name="widget_ongoing">進行中</string>
<string name="widget_tomorrow">明天</string>
<string name="widget_next_class">下一堂課</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/xml/ app/src/main/res/values/strings.xml
git commit -m "feat: add appwidget-provider XMLs and widget string resources"
```

---

## Task 9: BaseClassTableWidget, 6 receivers, manifest entries, deep-link handling

**Files:**
- Create: `widget/BaseClassTableWidget.kt`
- Create: `widget/receivers/WeekLightWidgetReceiver.kt` (×6)
- Modify: `AndroidManifest.xml`
- Modify: `ui/navigation/AppNavigation.kt`
- Modify: `MainActivity.kt`

- [ ] **Step 1: Create BaseClassTableWidget.kt**

```kotlin
package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.widget.content.NextClassContent
import org.ntust.app.tigerduck.widget.content.TodayListContent
import org.ntust.app.tigerduck.widget.content.WeekGridContent

abstract class BaseClassTableWidget(
    private val layout: WidgetLayout,
    val isDark: Boolean,
) : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetDataLoader.load(context)
        val colors = if (isDark) WidgetTheme.Dark else WidgetTheme.Light
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", "classTable")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            when (layout) {
                WidgetLayout.Week -> WeekGridContent(state, colors, tapAction)
                WidgetLayout.Today -> TodayListContent(state, colors, tapAction)
                WidgetLayout.NextClass -> NextClassContent(state, colors, tapAction)
            }
        }
    }
}
```

- [ ] **Step 2: Create all 6 receiver files**

`widget/receivers/WeekLightWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class WeekLightWidget : BaseClassTableWidget(WidgetLayout.Week, isDark = false)
class WeekLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeekLightWidget()
}
```

`widget/receivers/WeekDarkWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class WeekDarkWidget : BaseClassTableWidget(WidgetLayout.Week, isDark = true)
class WeekDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeekDarkWidget()
}
```

`widget/receivers/TodayLightWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayLightWidget : BaseClassTableWidget(WidgetLayout.Today, isDark = false)
class TodayLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayLightWidget()
}
```

`widget/receivers/TodayDarkWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayDarkWidget : BaseClassTableWidget(WidgetLayout.Today, isDark = true)
class TodayDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayDarkWidget()
}
```

`widget/receivers/NextClassLightWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class NextClassLightWidget : BaseClassTableWidget(WidgetLayout.NextClass, isDark = false)
class NextClassLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextClassLightWidget()
}
```

`widget/receivers/NextClassDarkWidgetReceiver.kt`:
```kotlin
package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class NextClassDarkWidget : BaseClassTableWidget(WidgetLayout.NextClass, isDark = true)
class NextClassDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextClassDarkWidget()
}
```

- [ ] **Step 3: Add 6 widget receivers to AndroidManifest.xml**

Inside `<application>`, after the `WidgetBoundaryReceiver` entry added in Task 7:
```xml
<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.WeekLightWidgetReceiver"
    android:label="@string/widget_week_light_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_week_light_info" />
</receiver>

<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.WeekDarkWidgetReceiver"
    android:label="@string/widget_week_dark_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_week_dark_info" />
</receiver>

<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.TodayLightWidgetReceiver"
    android:label="@string/widget_today_light_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_today_light_info" />
</receiver>

<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.TodayDarkWidgetReceiver"
    android:label="@string/widget_today_dark_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_today_dark_info" />
</receiver>

<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.NextClassLightWidgetReceiver"
    android:label="@string/widget_next_class_light_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_next_class_light_info" />
</receiver>

<receiver
    android:name="org.ntust.app.tigerduck.widget.receivers.NextClassDarkWidgetReceiver"
    android:label="@string/widget_next_class_dark_label"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/widget_next_class_dark_info" />
</receiver>
```

- [ ] **Step 4: Add deep-link navigation to AppNavigation.kt**

Add `widgetStartRoute: String? = null` parameter to `AppNavigation` and a `LaunchedEffect` that navigates when a widget opens the app:

```kotlin
// Change signature from:
fun AppNavigation(appState: AppState) {
// To:
fun AppNavigation(appState: AppState, widgetStartRoute: String? = null) {
```

Inside `AppNavigation`, after `val navController = rememberNavController()` and after the NavHost is set up, add:
```kotlin
LaunchedEffect(widgetStartRoute) {
    widgetStartRoute ?: return@LaunchedEffect
    navController.navigate(widgetStartRoute) {
        launchSingleTop = true
    }
}
```

- [ ] **Step 5: Handle widget intent in MainActivity.kt**

Add a `mutableStateOf` for the start route and override `onNewIntent`. In `MainActivity.kt`:

After the `@Inject` fields, add:
```kotlin
private val widgetStartRoute = androidx.compose.runtime.mutableStateOf<String?>(null)
```

In `onCreate`, before `setContent {`, add:
```kotlin
widgetStartRoute.value = intent?.getStringExtra("start_route")
```

In `setContent { ... AppNavigation(appState = appState) }`, change to:
```kotlin
AppNavigation(appState = appState, widgetStartRoute = widgetStartRoute.value)
```

After `onCreate`, add:
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    widgetStartRoute.value = intent.getStringExtra("start_route")
}
```

- [ ] **Step 6: Verify full build compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL — all 6 widget classes and WidgetUpdater now compile together.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/
git add app/src/main/AndroidManifest.xml
git add app/src/main/java/org/ntust/app/tigerduck/ui/navigation/AppNavigation.kt
git add app/src/main/java/org/ntust/app/tigerduck/MainActivity.kt
git commit -m "feat: add BaseClassTableWidget, 6 widget receivers, manifest entries, widget deep-link"
```

---

## Task 10: WeekGridContent

Renders the full-week timetable grid. Course tiles show only their name in the first period of the block; subsequent periods of the same course show the same color without text, giving the visual appearance of a tall tile.

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/content/WeekGridContent.kt`

- [ ] **Step 1: Create WeekGridContent.kt**

```kotlin
package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.clickable
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.widgetCourseColor

@Composable
fun WeekGridContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val periodLabelWidth = 18.dp
    val cellHeight = 26.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(4.dp)
            .clickable(tapAction),
    ) {
        if (!state.isLoggedIn || state.courses.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!state.isLoggedIn) "請先登入 TigerDuck" else "今日沒有課",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            return@Column
        }

        // Header row: blank period-label spacer + day name for each active day
        Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Box(modifier = GlanceModifier.width(periodLabelWidth)) {}
            state.activeWeekdays.forEach { day ->
                Box(
                    modifier = GlanceModifier.defaultWeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayNames[day - 1],
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        // One row per active period
        state.activePeriodIds.forEach { periodId ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(cellHeight),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                // Period label column
                Box(
                    modifier = GlanceModifier.width(periodLabelWidth).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = periodId,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 7.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }

                // One cell per active weekday
                state.activeWeekdays.forEach { weekday ->
                    val course = state.courses.firstOrNull {
                        it.schedule[weekday]?.contains(periodId) == true
                    }
                    val isOngoing = course?.courseNo != null &&
                            course.courseNo == state.ongoingCourseNo
                    // Show text only in the first period of this course's block
                    val isFirstPeriod = course != null && run {
                        val order = AppConstants.Periods.chronologicalOrder
                        val prevId = order.getOrNull(order.indexOf(periodId) - 1)
                        prevId == null || course.schedule[weekday]?.contains(prevId) != true
                    }
                    val cellBg: Color = when {
                        isOngoing -> colors.highlight
                        course != null -> widgetCourseColor(course, colors.isDark)
                        else -> colors.emptyCell
                    }

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(1.dp)
                            .background(ColorProvider(cellBg)),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (course != null && isFirstPeriod) {
                            Text(
                                text = course.courseName,
                                style = TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = 6.sp,
                                ),
                                maxLines = 2,
                                modifier = GlanceModifier.padding(1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build and install**

```bash
./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL, app installs on device.

- [ ] **Step 3: Manually add the Week widgets to the home screen**

Long-press on the home screen → Widgets → find "Classtable Week (light)" and "Classtable Week (dark)" → add both.

Verify:
- Light widget shows a white-background grid with colored course tiles
- Dark widget shows a dark-background grid
- The ongoing class tile (if any class is currently running) has a blue/teal highlight color instead of the course color
- Day header row shows "一", "二", "三", "四", "五" (and "六"/"日" if you have weekend courses)
- Tapping the widget opens the app to the ClassTable tab

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/content/WeekGridContent.kt
git commit -m "feat: implement WeekGridContent Glance composable"
```

---

## Task 11: TodayListContent

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/content/TodayListContent.kt`

- [ ] **Step 1: Create TodayListContent.kt**

```kotlin
package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.Action
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.clickable
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.widgetCourseColor

@Composable
fun TodayListContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val today = state.currentWeekday
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val isWeekend = today == 6 || today == 7
    val order = AppConstants.Periods.chronologicalOrder

    val todayCourses = state.courses
        .filter { it.schedule.containsKey(today) }
        .sortedBy { course ->
            course.schedule[today]!!
                .minByOrNull { order.indexOf(it) }
                ?.let { order.indexOf(it) } ?: Int.MAX_VALUE
        }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(8.dp)
            .clickable(tapAction),
    ) {
        // Day header
        Text(
            text = if (today in 1..7) "星期${dayNames[today - 1]}" else "今日課表",
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.padding(bottom = 6.dp),
        )

        when {
            !state.isLoggedIn -> {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "請先登入 TigerDuck",
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
            todayCourses.isEmpty() && isWeekend -> {
                Text(
                    text = "週末沒有課，週一見！",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 11.sp),
                )
            }
            todayCourses.isEmpty() -> {
                Text(
                    text = "今日沒有課",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 11.sp),
                )
            }
            else -> {
                todayCourses.forEach { course ->
                    val isOngoing = course.courseNo == state.ongoingCourseNo
                    val periods = course.schedule[today]!!.sortedBy { order.indexOf(it) }
                    val firstPeriod = periods.first()
                    val lastPeriod = periods.last()
                    val startTime = AppConstants.PeriodTimes.mapping[firstPeriod]?.first ?: ""
                    val endTime = AppConstants.PeriodTimes.mapping[lastPeriod]?.second ?: ""
                    val periodRange = if (firstPeriod == lastPeriod) firstPeriod
                                     else "$firstPeriod–$lastPeriod"
                    val rowBg = if (isOngoing) colors.highlight else colors.surface
                    val primaryText = if (isOngoing) Color.White else colors.onSurface
                    val secondaryText = if (isOngoing) Color.White.copy(alpha = 0.8f) else colors.onSurfaceVariant

                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(ColorProvider(rowBg))
                            .cornerRadius(4.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Column(modifier = GlanceModifier.width(52.dp)) {
                            Text(
                                text = periodRange,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                            Text(
                                text = "$startTime–$endTime",
                                style = TextStyle(
                                    color = ColorProvider(secondaryText),
                                    fontSize = 9.sp,
                                ),
                            )
                        }
                        Column(
                            modifier = GlanceModifier.defaultWeight().padding(start = 6.dp),
                        ) {
                            Text(
                                text = course.courseName,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                            )
                            if (course.classroom.isNotEmpty()) {
                                Text(
                                    text = course.classroom,
                                    style = TextStyle(
                                        color = ColorProvider(secondaryText),
                                        fontSize = 9.sp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build, install, and verify**

```bash
./gradlew :app:installDebug
```

Add "Classtable Today (light)" and "Classtable Today (dark)" widgets to the home screen. Verify:
- Shows today's courses as a vertical list
- Ongoing course row has highlight background color
- No-class state shows "今日沒有課" or "週末沒有課，週一見！" depending on day

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/content/TodayListContent.kt
git commit -m "feat: implement TodayListContent Glance composable"
```

---

## Task 12: NextClassContent

**Files:**
- Create: `app/src/main/java/org/ntust/app/tigerduck/widget/content/NextClassContent.kt`

- [ ] **Step 1: Create NextClassContent.kt**

```kotlin
package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.Action
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.clickable
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.parseHm
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState

@Composable
fun NextClassContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(12.dp)
            .clickable(tapAction),
    ) {
        Spacer(GlanceModifier.defaultWeight())

        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp),
                )
            }

            state.ongoingCourseNo != null -> {
                val course = state.courses.find { it.courseNo == state.ongoingCourseNo }
                if (course != null) {
                    val weekday = state.currentWeekday
                    val order = AppConstants.Periods.chronologicalOrder
                    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                    val startTime = periods.firstOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""
                    val endTime = periods.lastOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""
                    val startMin = parseHm(startTime) ?: 0
                    val endMin = parseHm(endTime) ?: 0
                    val progress = if (endMin > startMin) {
                        ((state.currentMinuteOfDay - startMin).toFloat() / (endMin - startMin))
                            .coerceIn(0f, 1f)
                    } else 0f
                    val periodRange = if (periods.size > 1)
                        "${periods.first()}–${periods.last()}" else periods.firstOrNull() ?: ""

                    // "進行中" pill
                    Box(
                        modifier = GlanceModifier
                            .background(ColorProvider(colors.highlight))
                            .cornerRadius(12.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "進行中",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = course.courseName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = "$startTime–$endTime  $periodRange",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    if (course.classroom.isNotEmpty()) {
                        Text(
                            text = course.classroom,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    // Progress bar using LocalSize to compute filled width
                    val widgetWidth = LocalSize.current.width
                    val filledWidth = (widgetWidth.value * progress - 24).coerceAtLeast(0f).dp
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp)
                            .background(ColorProvider(colors.emptyCell))
                            .cornerRadius(2.dp),
                    ) {
                        Box(
                            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                                .background(ColorProvider(colors.highlight))
                                .cornerRadius(2.dp),
                        ) {}
                    }
                }
            }

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo }
                if (course != null) {
                    val weekday = state.currentWeekday
                    val order = AppConstants.Periods.chronologicalOrder
                    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                    val startTime = periods.firstOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""

                    Text(
                        text = "下一堂課",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = course.courseName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    if (startTime.isNotEmpty()) {
                        Text(
                            text = startTime,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 11.sp),
                        )
                    }
                    if (course.classroom.isNotEmpty()) {
                        Text(
                            text = course.classroom,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                        )
                    }
                }
            }

            else -> {
                // No more classes today — show tomorrow's first
                Text(
                    text = "今日課程已結束",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurface),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                val name = state.tomorrowFirstCourseName
                val time = state.tomorrowFirstCourseTime
                if (name != null && time != null) {
                    Spacer(GlanceModifier.height(10.dp))
                    Text(
                        text = "明天",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    Text(
                        text = name,
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 12.sp),
                        maxLines = 1,
                    )
                    Text(
                        text = time,
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                }
            }
        }

        Spacer(GlanceModifier.defaultWeight())
    }
}
```

- [ ] **Step 2: Build, install, and verify**

```bash
./gradlew :app:installDebug
```

Add "Next Class (light)" and "Next Class (dark)" widgets. Verify:
- During a class: "進行中" pill, course name, time range, classroom, progress bar
- Between classes: "下一堂課" label + next course info
- After last class of day: "今日課程已結束" + tomorrow's first class name and time
- Not logged in: "請先登入 TigerDuck"

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/widget/content/NextClassContent.kt
git commit -m "feat: implement NextClassContent Glance composable"
```

---

## Task 13: Wire BackgroundSyncWorker

**Files:**
- Modify: `app/src/main/java/org/ntust/app/tigerduck/notification/BackgroundSyncWorker.kt`

- [ ] **Step 1: Inject WidgetUpdater into BackgroundSyncWorker**

In `BackgroundSyncWorker.kt`, add `widgetUpdater` to the `@AssistedInject` constructor, after the `prefs` parameter:

```kotlin
private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
```

- [ ] **Step 2: Call updateAll after liveActivityManager.refresh()**

In `doWork()`, add the call right after `liveActivityManager.refresh()`:

```kotlin
liveActivityManager.refresh()
widgetUpdater.updateAll()
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify with a manual sync**

In the app, trigger a pull-to-refresh on the ClassTable screen, then go to the home screen and confirm the placed widgets reflect the refreshed data.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/notification/BackgroundSyncWorker.kt
git commit -m "feat: trigger widget refresh from BackgroundSyncWorker"
```

---

## Task 14: Wire ClassTableViewModel

**Files:**
- Modify: `app/src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt`

- [ ] **Step 1: Add WidgetUpdater to ViewModel constructor**

In `ClassTableViewModel.kt`, add `widgetUpdater` as a Hilt-injected parameter after `appPreferences`:

```kotlin
private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater
```

Full constructor signature:
```kotlin
@HiltViewModel
class ClassTableViewModel @Inject constructor(
    private val networkChecker: NetworkChecker,
    private val authService: AuthService,
    internal val courseService: CourseService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache,
    private val courseColorStore: CourseColorStore,
    private val appPreferences: AppPreferences,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
) : ViewModel() {
```

- [ ] **Step 2: Call widgetUpdater.updateAll() after each course save**

There are 5 save points. Modify each:

**`addCourse`** — change the launch body to:
```kotlin
viewModelScope.launch {
    dataCache.saveCourses(updated, _currentSemester.value)
    widgetUpdater.updateAll()
}
```

**`renameCourse`** — change the launch body to:
```kotlin
viewModelScope.launch {
    dataCache.saveCourses(updated, _currentSemester.value)
    widgetUpdater.updateAll()
}
```

**`deleteCourse`** — change the launch body to:
```kotlin
viewModelScope.launch {
    dataCache.saveCourses(updated, _currentSemester.value)
    widgetUpdater.updateAll()
}
```

**`updateCourseColor`** — change the launch body to:
```kotlin
viewModelScope.launch {
    dataCache.saveCourses(updated, _currentSemester.value)
    widgetUpdater.updateAll()
}
```

**`fetchData`** — inside `coroutineScope { ... coursesJob ... }`, after `dataCache.saveCourses(merged, semester)`:
```kotlin
dataCache.saveCourses(merged, semester)
widgetUpdater.updateAll()
```

**`courseColorStore.changeEvent` handler** — in the `init { }` block, the existing `courseColorStore.changeEvent.collect { }` lambda reloads courses after a color reset. Add `widgetUpdater.updateAll()` at the end of that lambda so a Settings color-reset also refreshes the widgets:
```kotlin
viewModelScope.launch {
    courseColorStore.changeEvent.collect {
        val fresh = dataCache.loadCourses(_currentSemester.value)
        if (fresh.isNotEmpty()) {
            _courses.value = fresh
            TigerDuckTheme.buildCourseColorMap(fresh)
            widgetUpdater.updateAll()  // ADD THIS LINE
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt
git commit -m "feat: trigger widget refresh from ClassTableViewModel on any course change"
```

---

## Task 15: Final build and verification

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: All tests PASS (CourseScheduleUtilsTest + WidgetBoundarySchedulerTest + any existing tests).

- [ ] **Step 2: Clean build**

```bash
./gradlew :app:clean :app:installDebug
```
Expected: BUILD SUCCESSFUL, app installs.

- [ ] **Step 3: Verification checklist**

Install all 6 widgets and verify each:

**Light vs dark themes:**
- Light widget: white/light-grey background, dark text
- Dark widget: dark background (#1C1C1E), light text — matches the app's dark mode surfaces

**Week grid:**
- Mon–Fri columns always visible; Sat/Sun appear only if courses exist on those days
- Course tiles show the name only in the first period of the block
- Ongoing class tile uses highlight blue (#0066CC light / #4DA3FF dark) instead of course color
- Period labels (1–9 + A–D) visible in the leftmost column

**Today list:**
- Courses sorted by start time
- Ongoing course row has highlight background
- On a weekday with no courses: "今日沒有課"
- On Saturday or Sunday with no weekend courses: "週末沒有課，週一見！"

**Next-class card:**
- During class: "進行中" pill + course name + time range + classroom + progress bar
- Between classes: "下一堂課" + course name + start time + classroom
- After last class: "今日課程已結束" + tomorrow's first class
- Not logged in: "請先登入 TigerDuck"

**Tap behavior:**
- Tapping any widget opens the app and navigates to the ClassTable tab

**Update triggers:**
- Add or delete a course in the ClassTable screen → widgets update within seconds
- Pull-to-refresh in ClassTable → widgets update
- Wait for background sync (hourly) → widgets update

**Boundary highlight:**
- At the start of a class period, the ongoing highlight switches to that course
- At the end of a class period, the highlight clears (or moves to the next course if contiguous)

- [ ] **Step 4: Commit any fixes found during verification, then tag**

```bash
git add -p  # stage only verified fixes
git commit -m "fix: widget polish from manual verification"
```
