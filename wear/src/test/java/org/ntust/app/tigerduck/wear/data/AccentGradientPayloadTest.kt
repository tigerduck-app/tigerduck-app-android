package org.ntust.app.tigerduck.wear.data

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.shared.WearProtocol
import org.robolectric.RobolectricTestRunner

/** [accentGradientOf]: the accent gradient as the phone's schedule payload carries it. */
@RunWith(RobolectricTestRunner::class)
class AccentGradientPayloadTest {

    private val gradient = requireNotNull(
        AccentGradient.of(listOf(0x8A87FF, 0xB35CEB, 0xF0507A), AccentGradientStyle.Angular),
    )

    private fun payload(block: DataMap.() -> Unit) = DataMap().apply {
        putString(WearProtocol.Schedule.KEY_ACCENT, "#8A87FF")
        block()
    }

    @Test
    fun `the gradient the phone sends is read, its stops and style`() {
        val map = payload {
            putString(WearProtocol.Schedule.KEY_ACCENT_GRADIENT, gradient.encode())
            // What it sends beside it for a watch of the release before.
            putString(WearProtocol.Schedule.KEY_ACCENT_END, "#F0507A")
            putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 5)
        }
        assertEquals(gradient, accentGradientOf(map))
    }

    @Test
    fun `a gradient a newer build sent, or a damaged one, is none, whatever else the payload carries`() {
        listOf(
            """{"stops":[1,2],"style":"spiral","direction":"leftToRight"}""",
            """{"stops":[],"style":"linear","direction":"leftToRight"}""",
            """{"stops":[1,2,3,4,5,6],"style":"linear","direction":"leftToRight"}""",
            "{",
        ).forEach { json ->
            val map = payload {
                putString(WearProtocol.Schedule.KEY_ACCENT_GRADIENT, json)
                putString(WearProtocol.Schedule.KEY_ACCENT_END, "#F0507A")
                putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 5)
            }
            assertNull(json, accentGradientOf(map))
        }
    }

    // The iPhone sends its gradient as bytes; a phone may one day send one of another type.
    @Test
    fun `a gradient of another type is none`() {
        assertNull(accentGradientOf(payload { putByteArray(WearProtocol.Schedule.KEY_ACCENT_GRADIENT, gradient.encode().toByteArray()) }))
    }

    @Test
    fun `an end and a direction from a phone of the release before read as two stops of a linear gradient`() {
        val map = payload {
            putString(WearProtocol.Schedule.KEY_ACCENT_END, "#AF52DE")
            putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 2)
        }
        assertEquals(
            AccentGradient.of(listOf(0x8A87FF, 0xAF52DE), AccentGradientStyle.Linear, AccentGradientDirection.LeftToRight),
            accentGradientOf(map),
        )
    }

    // DataMap.getInt answers 0, top to bottom, for a key it lacks.
    @Test
    fun `an end sent without a direction runs from the top left, rather than top to bottom`() {
        val map = payload { putString(WearProtocol.Schedule.KEY_ACCENT_END, "#AF52DE") }
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, accentGradientOf(map)?.direction)
    }

    @Test
    fun `a payload with no gradient and no end, as from a single color, is no gradient`() {
        assertNull(accentGradientOf(payload {}))
        assertNull(accentGradientOf(payload { putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 2) }))
        assertNull(accentGradientOf(payload { putString(WearProtocol.Schedule.KEY_ACCENT_END, "not a color") }))
    }
}
