package org.ntust.app.tigerduck.wear.complication

import android.app.Application
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Next class complication, as the iPhone's complication families: the room first, the full
 * range, the day of a later class, spoken ranges, progress for the class going on, and a
 * timeline through each change so none of it goes stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class NextClassComplicationTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private val monday = LocalDate.of(2026, 11, 2)
    private val db = Course.fromSchedule("DB", "Databases", classroom = "TR-412", schedule = mapOf(1 to listOf("3", "4")))   // 10:20-12:10
    private val ai = Course.fromSchedule("AI", "Artificial Intelligence", schedule = mapOf(1 to listOf("7", "8")))         // 14:20-16:20, no room
    private val os = Course.fromSchedule("OS", "Systems", classroom = "T2-103", schedule = mapOf(3 to listOf("1")))       // 08:10-09:00

    private fun snapshot(courses: List<Course> = listOf(db, ai, os), changes: WatchCourseChanges = WatchCourseChanges.EMPTY) =
        WatchSnapshot(courses, SchedulePersistence.DEFAULT_ACCENT, 1_000L, true, null, emptySet(), courseChanges = changes)

    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m)

    private fun data(type: ComplicationType, at: LocalDateTime, snap: WatchSnapshot = snapshot()) =
        complicationAt(context, snap, type, at, tap = null)

    private fun ComplicationText?.read(): String? = this?.getTextAt(context.resources, Instant.EPOCH)?.toString()

    @Before
    fun setUp() = AppClock.setOverride(null)

    @Test
    fun `short text puts the room first, with the class going on's range read out`() {
        val now = data(ComplicationType.SHORT_TEXT, at(monday, 11, 15)) as ShortTextComplicationData
        assertEquals("TR-412", now.text.read())
        assertEquals("NOW", now.title.read())
        assertEquals("TR-412, 10:20 to 12:10", now.contentDescription.read())
    }

    @Test
    fun `short text for a class without a room is its name, then its start`() {
        val next = data(ComplicationType.SHORT_TEXT, at(monday, 12, 30)) as ShortTextComplicationData
        assertEquals("Artific", next.text.read())
        assertEquals("14:20", next.title.read())
        assertEquals("Artificial Intelligence, 14:20 to 16:20", next.contentDescription.read())
    }

    @Test
    fun `a class on a later day gives its day before its start, today's none`() {
        val tomorrow = data(ComplicationType.SHORT_TEXT, at(monday.plusDays(1), 20, 0)) as ShortTextComplicationData
        assertEquals("T2-103", tomorrow.text.read())
        assertEquals("Tomorrow 08:10", tomorrow.title.read())
        assertEquals("T2-103, Tomorrow 08:10 to 09:00", tomorrow.contentDescription.read())

        val wednesday = data(ComplicationType.SHORT_TEXT, at(monday, 17, 0)) as ShortTextComplicationData
        assertEquals("Wed 08:10", wednesday.title.read())
        assertEquals("T2-103, Wed 08:10 to 09:00", wednesday.contentDescription.read())
    }

    @Test
    fun `long text is the name on top, then the room before the range`() {
        val now = data(ComplicationType.LONG_TEXT, at(monday, 11, 15)) as LongTextComplicationData
        assertEquals("NOW · Databas", now.title.read())
        assertEquals("TR-412 · 10:20–12:10", now.text.read())
        assertEquals("Databases, TR-412, 10:20 to 12:10", now.contentDescription.read())

        val later = data(ComplicationType.LONG_TEXT, at(monday, 17, 0)) as LongTextComplicationData
        assertEquals("Systems", later.title.read())
        assertEquals("T2-103 · Wed 08:10–09:00", later.text.read())
        assertEquals("Systems, T2-103, Wed 08:10 to 09:00", later.contentDescription.read())
    }

    @Test
    fun `long text without a room is the range alone`() {
        val next = data(ComplicationType.LONG_TEXT, at(monday, 12, 30)) as LongTextComplicationData
        assertEquals("Artificial Intelligence", next.title.read())
        assertEquals("14:20–16:20", next.text.read())
        assertEquals("Artificial Intelligence, 14:20 to 16:20", next.contentDescription.read())
    }

    @Test
    fun `an online class shows Online as a moved room`() {
        val online = WatchCourseChanges(mapOf("DB" to mapOf(monday to listOf(WatchClassChange(listOf("3", "4"), room = "Online")))))
        val long = data(ComplicationType.LONG_TEXT, at(monday, 11, 15), snapshot(changes = online)) as LongTextComplicationData
        assertEquals("⇄ Online · 10:20–12:10", long.text.read())
        val short = data(ComplicationType.SHORT_TEXT, at(monday, 11, 15), snapshot(changes = online)) as ShortTextComplicationData
        assertEquals("Online", short.text.read())
    }

    @Test
    fun `the ranged value shows the class going on's progress, moved by the platform`() {
        val now = data(ComplicationType.RANGED_VALUE, at(monday, 11, 15)) as RangedValueComplicationData
        assertEquals(0f, now.min)
        assertEquals(6600f, now.max)
        assertEquals(3300f, now.value)
        assertNotNull(now.dynamicValue)
        assertEquals("TR-412", now.text.read())
        assertNull(now.title)
        assertEquals("TR-412, 10:20 to 12:10", now.contentDescription.read())
    }

    @Test
    fun `the ranged value of a class shown next is empty, with its start`() {
        val next = data(ComplicationType.RANGED_VALUE, at(monday, 12, 30)) as RangedValueComplicationData
        assertEquals(0f, next.value)
        assertNull(next.dynamicValue)
        assertEquals("Artific", next.text.read())
        assertEquals("14:20", next.title.read())
    }

    @Test
    fun `the timeline moves at each start and end and at midnight`() {
        val timeline = complicationTimeline(context, snapshot(), ComplicationType.SHORT_TEXT, at(monday, 11, 15), tap = null)
        assertEquals("NOW", (timeline.defaultComplicationData as ShortTextComplicationData).title.read())

        fun instant(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(TaipeiClock.zone).toInstant()
        val entries = timeline.timelineEntries.toList()
        assertEquals(
            listOf(
                instant(monday, 12, 10), instant(monday, 14, 20), instant(monday, 16, 20),
                instant(monday.plusDays(1), 0, 0), instant(monday.plusDays(2), 0, 0),
            ),
            entries.map { it.validity.start },
        )
        assertEquals(entries.drop(1).map { it.validity.start }, entries.dropLast(1).map { it.validity.end })
        val titles = entries.map { (it.complicationData as ShortTextComplicationData).title.read() }
        assertEquals(listOf("14:20", "NOW", "Wed 08:10", "Tomorrow 08:10", "08:10"), titles)
    }

    @Test
    fun `the timeline's progress starts again from each class's start`() {
        val timeline = complicationTimeline(context, snapshot(), ComplicationType.RANGED_VALUE, at(monday, 11, 15), tap = null)
        val ai = timeline.timelineEntries.first { (it.complicationData as RangedValueComplicationData).dynamicValue != null }
        val data = ai.complicationData as RangedValueComplicationData
        assertEquals(0f, data.value)
        assertEquals(7200f, data.max)
        assertEquals(monday.atTime(14, 20).atZone(TaipeiClock.zone).toInstant(), ai.validity.start)
    }

    @Test
    fun `with the debug clock held still the complication holds still too`() {
        val now = monday.atTime(11, 15)
        AppClock.setOverride(ClockOverride(now.atZone(TaipeiClock.zone).toInstant().toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
        try {
            val timeline = complicationTimeline(context, snapshot(), ComplicationType.RANGED_VALUE, now, tap = null)
            assertTrue(timeline.timelineEntries.isEmpty())
            val ring = timeline.defaultComplicationData as RangedValueComplicationData
            assertNull(ring.dynamicValue)
            assertEquals(3300f, ring.value)
        } finally {
            AppClock.setOverride(null)
        }
    }

    @Test
    fun `with nothing to show the complication has no data and no timeline`() {
        val timeline = complicationTimeline(context, snapshot(courses = emptyList()), ComplicationType.SHORT_TEXT, at(monday, 11, 15), tap = null)
        assertTrue(timeline.defaultComplicationData is NoDataComplicationData)
        assertTrue(timeline.timelineEntries.isEmpty())
    }

    @Test
    fun `a type the complication doesn't offer has no data`() {
        val data: ComplicationData = data(ComplicationType.SMALL_IMAGE, at(monday, 11, 15))
        assertTrue(data is NoDataComplicationData)
    }
}
