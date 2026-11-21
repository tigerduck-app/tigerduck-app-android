package org.ntust.app.tigerduck.liveactivity

import android.app.Application
import android.text.Spanned
import android.text.style.TtsSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Live Update's title marks a class with an assessment as a notification can: underlined, and read with it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveUpdateTitleTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun snapshot(assessment: Boolean) = LiveActivitySnapshot(
        scenario = LiveActivityScenario.CLASS_PREPARING,
        title = "Compilers",
        subtitle = "09:10–12:10",
        locationText = "T4-501",
        instructor = null,
        countdownTarget = null,
        progress = null,
        accentHex = 0,
        sourceId = "prep-CC900-2",
        assessment = assessment,
    )

    @Test
    fun `an assessment's title is underlined and read with the assessment after it`() {
        val title = liveUpdateTitle(snapshot(assessment = true), context) as Spanned

        assertEquals("Compilers", title.toString())
        assertEquals(1, title.getSpans(0, title.length, UnderlineSpan::class.java).size)
        val spoken = title.getSpans(0, title.length, TtsSpan::class.java).single()
        assertEquals(TtsSpan.TYPE_TEXT, spoken.type)
        assertEquals("Compilers, ${context.getString(R.string.course_change_assessment)}", spoken.args.getString(TtsSpan.ARG_TEXT))
    }

    @Test
    fun `any other title is plain`() {
        val title = liveUpdateTitle(snapshot(assessment = false), context)

        assertEquals("Compilers", title)
        assertFalse(title is Spanned)
        assertTrue(title.isNotEmpty())
    }
}
