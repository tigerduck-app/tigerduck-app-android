package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveUpdateDetailsTest {

    private fun snapshot(
        subtitle: String = "09:10–10:00",
        locationText: String? = "TR-412",
        instructor: String? = "Debug Menu",
    ) = LiveActivitySnapshot(
        scenario = LiveActivityScenario.IN_CLASS,
        title = "Preview Course",
        subtitle = subtitle,
        locationText = locationText,
        instructor = instructor,
        countdownTarget = null,
        progress = null,
        accentHex = 0xF5A623,
        sourceId = "test",
    )

    @Test
    fun `room, instructor and time each get a line, in that order`() {
        assertEquals(
            listOf("TR-412", "Debug Menu", "09:10–10:00"),
            LiveUpdateDetails.lines(snapshot()),
        )
    }

    @Test
    fun `no line carries an emoji`() {
        val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")
        LiveUpdateDetails.lines(snapshot()).forEach { line ->
            assertFalse("<$line> carries an emoji", emoji.containsMatchIn(line))
        }
    }

    @Test
    fun `missing fields are left out rather than shown blank`() {
        assertEquals(
            listOf("09:10–10:00"),
            LiveUpdateDetails.lines(snapshot(locationText = null, instructor = null)),
        )
        assertEquals(
            emptyList<String>(),
            LiveUpdateDetails.lines(snapshot(subtitle = " ", locationText = null, instructor = null)),
        )
    }

    @Test
    fun `the subtitle can be left out for a card whose text already carries it`() {
        assertEquals(
            listOf("TR-412", "Debug Menu"),
            LiveUpdateDetails.lines(snapshot(), withSubtitle = false),
        )
    }
}
