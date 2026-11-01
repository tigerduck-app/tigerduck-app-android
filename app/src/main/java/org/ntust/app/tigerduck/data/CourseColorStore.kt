package org.ntust.app.tigerduck.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Cross-ViewModel coordinator for schedule tile colors. Settings can invoke
 * [resetAllColors] to randomize every course's color; all active ViewModels
 * subscribe to [changeEvent] and reload their course state when it fires.
 */
@Singleton
class CourseColorStore @Inject constructor(
    private val dataCache: DataCache,
    private val widgetUpdater: WidgetUpdater,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val moodleColorStore: MoodleColorStore,
) {
    private val _changeEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changeEvent: SharedFlow<Unit> = _changeEvent.asSharedFlow()

    /**
     * Loads which courses take Moodle's color, which nothing may color a course before. Cheap once
     * loaded.
     */
    suspend fun ensureMoodleColorsLoaded() = moodleColorStore.ensureLoaded()

    /** [DataCache.loadCourses] for [semester], once the Moodle colors are loaded, so the screens may color them. */
    suspend fun loadCourses(semester: String): List<Course> {
        ensureMoodleColorsLoaded()
        return dataCache.loadCourses(semester)
    }

    /** [DataCache.loadCourses] of the current term, as [loadCourses]. */
    suspend fun loadCourses(): List<Course> {
        ensureMoodleColorsLoaded()
        return dataCache.loadCourses()
    }

    /**
     * Keeps Moodle's course ids [ids]; a course waiting for its Moodle color among [courses] takes
     * it now, shown on the screens and the widgets.
     */
    suspend fun saveMoodleIds(ids: Map<String, Int>, courses: List<Course>) {
        dataCache.saveMoodleCourseIds(ids)
        ensureMoodleColorsLoaded()
        val book = MoodleColorBook.shared
        book.setIds(ids)
        if (!book.reconcile(courses)) return
        TigerDuckTheme.buildCourseColorMap(courses)
        widgetUpdater.requestUpdate()
    }

    /**
     * Gives every course the color Moodle draws it in, where its Moodle id is known, and the rest a
     * fresh random preset color, forgetting every pick. Distinct presets are drawn first; any
     * course beyond the 18-color palette receives a random non-palette color. Persists to the
     * cache and rebuilds the shared color map before broadcasting [changeEvent].
     */
    suspend fun resetAllColors() {
        ensureMoodleColorsLoaded()
        // Reserved before the colours are read: a pick made from here on is
        // the newer, and the reset's own colour for that course gives way.
        val version = pendingCourseOverrides.reserveVersion()
        val courses = dataCache.loadCourses()
        if (courses.isEmpty()) return

        val moodle = MoodleColorBook.shared.reset(courses)
        val palette = courseColorPalette.shuffled()
        val paletteArgbs = palette.mapTo(mutableSetOf()) { it.toArgb() }
        val used = mutableSetOf<Int>()

        // Two courses may share Moodle's color; the app's own colors stay one to a course.
        var next = 0
        val updated = courses.map { course ->
            val color = moodle[course.courseNo]?.let { Color(0xFF000000L or it.toLong()) }
                ?: if (next < palette.size) {
                    palette[next++].also { used.add(it.toArgb()) }
                } else {
                    randomNonPaletteColor(paletteArgbs, used).also { used.add(it.toArgb()) }
                }
            course.copy(customColorHex = formatHex(color))
        }

        // Through the outbox, which honours the sync switches and keeps a
        // colour that fails to land. Queued whole, at the version reserved
        // above and before the colours are saved: a pick made while they are
        // read or go up one by one is the newer, and is not replaced when the
        // reset's own colour for that course reaches its turn.
        pendingCourseOverrides.queue(
            updated.mapNotNull { course ->
                val hex = course.customColorHex ?: return@mapNotNull null
                val moodleId = course.moodleIdNumber ?: return@mapNotNull null
                PendingCourseOverrides.Change(moodleId, colorHex = hex)
            },
            version,
        )
        dataCache.saveCourses(updated)
        TigerDuckTheme.clearCourseColorMap()
        widgetUpdater.requestUpdate()
        _changeEvent.tryEmit(Unit)
        pendingCourseOverrides.retry()
    }

    private fun formatHex(color: Color): String =
        "#" + String.format("%06X", color.toArgb() and 0xFFFFFF)

    private fun randomNonPaletteColor(
        paletteArgbs: Set<Int>,
        alreadyUsed: Set<Int>
    ): Color {
        while (true) {
            val h = Random.nextFloat() * 360f
            val s = 0.55f + Random.nextFloat() * 0.4f
            val v = 0.55f + Random.nextFloat() * 0.3f
            val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
            val argb = c.toArgb()
            if (argb !in paletteArgbs && argb !in alreadyUsed) return c
        }
    }
}
