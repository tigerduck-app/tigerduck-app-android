package org.ntust.app.tigerduck.wear.data

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.WearProtocol
import org.robolectric.RobolectricTestRunner

/** [accentGradientOf]: the accent gradient as the phone's schedule payload carries it. */
@RunWith(RobolectricTestRunner::class)
class AccentGradientPayloadTest {

    private fun payload(block: DataMap.() -> Unit) = DataMap().apply {
        putString(WearProtocol.Schedule.KEY_ACCENT, "#007AFF")
        block()
    }

    @Test
    fun `a gradient's end and direction are read`() {
        val map = payload {
            putString(WearProtocol.Schedule.KEY_ACCENT_END, "#AF52DE")
            putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 2)
        }
        assertEquals("#AF52DE" to 2, accentGradientOf(map))
    }

    // DataMap.getInt answers 0, top to bottom, for a key it lacks.
    @Test
    fun `an end sent without a direction has none, rather than top to bottom`() {
        val map = payload { putString(WearProtocol.Schedule.KEY_ACCENT_END, "#AF52DE") }
        assertEquals("#AF52DE" to null, accentGradientOf(map))
    }

    @Test
    fun `a payload with no end, as from a single color or an older phone, is no gradient`() {
        assertNull(accentGradientOf(payload {}))
        assertNull(accentGradientOf(payload { putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, 2) }))
    }
}
