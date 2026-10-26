package org.ntust.app.tigerduck.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.data.cache.DataCache
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
) {
    private val _changeEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changeEvent: SharedFlow<Unit> = _changeEvent.asSharedFlow()

    /**
     * Assigns a fresh random preset color to every course. Distinct presets
     * are drawn first; any course beyond the 18-color palette receives a
     * random non-palette color. Persists to the cache and rebuilds the
     * shared color map before broadcasting [changeEvent].
     */
    suspend fun resetAllColors() {
        // Reserved before the colours are read: a pick made from here on is
        // the newer, and the reset's own colour for that course gives way.
        val version = pendingCourseOverrides.reserveVersion()
        val courses = dataCache.loadCourses()
        if (courses.isEmpty()) return

        val palette = courseColorPalette.shuffled()
        val paletteArgbs = palette.mapTo(mutableSetOf()) { it.toArgb() }
        val used = mutableSetOf<Int>()

        val updated = courses.mapIndexed { idx, course ->
            val color = if (idx < palette.size) {
                palette[idx].also { used.add(it.toArgb()) }
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
