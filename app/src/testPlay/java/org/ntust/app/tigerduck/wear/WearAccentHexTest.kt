package org.ntust.app.tigerduck.wear

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.ui.AccentSetting
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [wearAccentHex] and [wearAccentGradient], the accent the phone sends the watch: the
 * first stop always, and the whole gradient only while Settings' watch switch is on, as the
 * iPhone sends its Apple Watch. Also while the phone follows the wallpaper, as the watch has
 * no wallpaper colors of its own to follow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class WearAccentHexTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    private val blue = 0x007AFF
    private val purple = 0xAF52DE

    private fun gradient(
        vararg stops: Int,
        style: AccentGradientStyle = AccentGradientStyle.Linear,
        direction: AccentGradientDirection = AccentGradientDirection.TopLeftToBottomRight,
    ) = requireNotNull(AccentGradient.of(stops.toList(), style, direction))

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    private fun Int.onBlack(): Float {
        val color = Color(0xFF000000.toInt() or this)
        return (color.luminance() + 0.05f) / (Color.Black.luminance() + 0.05f)
    }

    @Test
    fun `with the wallpaper picked the watch is sent the swatch last picked`() {
        AccentSetting(prefs).apply {
            pick(0xFF2D55)
            pickWallpaper()
        }
        assertEquals("#FF2D55", wearAccentHex(prefs))
    }

    @Test
    fun `a stored -1 is sent as Blue, not white`() {
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit()
            .putInt("accentColorHex", -1).commit()
        assertEquals("#007AFF", wearAccentHex(prefs))
    }

    // The watch draws on black: black picked as one's own would leave its
    // rings and guides invisible there.
    @Test
    fun `a dark color of the user's own is sent in a tone that stands out on the watch's black`() {
        AccentSetting(prefs).pick(0x000000)
        val sent = Color(android.graphics.Color.parseColor(wearAccentHex(prefs)))
        val contrast = (sent.luminance() + 0.05f) / (Color.Black.luminance() + 0.05f)
        assertTrue("$sent stands out at $contrast:1", contrast >= 3f)
    }

    @Test
    fun `a preset is sent as it is`() {
        AccentSetting(prefs).pick(0x5856D6)
        assertEquals("#5856D6", wearAccentHex(prefs))
    }

    @Test
    fun `a single color sends no gradient, the watch switch on or not`() {
        val setting = AccentSetting(prefs).apply { pick(0xFF2D55) }
        assertNull(wearAccentGradient(prefs))
        setting.gradientOnWatch = true
        assertNull(wearAccentGradient(prefs))
    }

    // As on the iPhone: the watch drew the gradient with no switch before, and now waits for it.
    @Test
    fun `a gradient goes to the watch only while its switch is on`() {
        val setting = AccentSetting(prefs).apply { pickAccent(gradient(blue, purple)) }
        assertEquals("#007AFF", wearAccentHex(prefs))
        assertNull(wearAccentGradient(prefs))

        setting.gradientOnWatch = true
        assertEquals(gradient(blue, purple), wearAccentGradient(prefs))
    }

    @Test
    fun `every stop goes as the watch draws it, a preset as it is and a color of one's own lightened`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(blue, 0x000000, 0x5856D6, style = AccentGradientStyle.Mesh))
            gradientOnWatch = true
        }
        val sent = requireNotNull(wearAccentGradient(prefs))

        assertEquals(AccentGradientStyle.Mesh, sent.style)
        assertEquals(blue, sent.stops[0])
        assertTrue("black goes lightened, at ${sent.stops[1].onBlack()}:1", sent.stops[1].onBlack() >= 3f)
        assertEquals(0x5856D6, sent.stops[2])
    }

    @Test
    fun `the payload carries the gradient's JSON, and its first and last stop for an older watch`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(blue, 0x34C759, purple, direction = AccentGradientDirection.BottomToTop))
            gradientOnWatch = true
        }
        val map = DataMap().apply { putAccent(prefs) }

        assertEquals("#007AFF", map.getString(WearProtocol.Schedule.KEY_ACCENT))
        assertEquals(wearAccentGradient(prefs)?.encode(), map.getString(WearProtocol.Schedule.KEY_ACCENT_GRADIENT))
        // What a watch of the release before draws: the first stop into the last, as it ran.
        assertEquals("#AF52DE", map.getString(WearProtocol.Schedule.KEY_ACCENT_END))
        assertEquals(AccentGradientDirection.BottomToTop.code, map.getInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION))
    }

    @Test
    fun `an older watch is sent a gradient of another style as one from the top left`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(blue, purple, style = AccentGradientStyle.Angular, direction = AccentGradientDirection.RightToLeft))
            gradientOnWatch = true
        }
        val map = DataMap().apply { putAccent(prefs) }

        assertEquals(AccentGradientDirection.DEFAULT.code, map.getInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION))
    }

    @Test
    fun `with the switch off the payload carries the first stop alone`() {
        AccentSetting(prefs).pickAccent(gradient(blue, purple))
        val map = DataMap().apply { putAccent(prefs) }

        assertEquals("#007AFF", map.getString(WearProtocol.Schedule.KEY_ACCENT))
        assertFalse(map.containsKey(WearProtocol.Schedule.KEY_ACCENT_GRADIENT))
        assertFalse(map.containsKey(WearProtocol.Schedule.KEY_ACCENT_END))
        assertFalse(map.containsKey(WearProtocol.Schedule.KEY_ACCENT_DIRECTION))
    }

    // An older build knows only accentColorHex: a color picked there leaves the
    // gradient behind, which the phone then no longer draws, nor the watch.
    @Test
    fun `a gradient an older build picked over sends none`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(blue, purple))
            gradientOnWatch = true
        }
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit()
            .putInt("accentColorHex", 0xFF2D55).commit()
        assertEquals("#FF2D55", wearAccentHex(prefs))
        assertNull(wearAccentGradient(prefs))
    }

    // The watch hears of an accent pick through accentColorChanged. A gradient from the color
    // already picked, a new style and the switch leave accentColorHex as it was, and must reach
    // the watch too.
    @Test
    fun `a gradient from the color already picked, a new style and the switch each reach the watch`() = runTest {
        val setting = AccentSetting(prefs).apply { pick(blue) }
        setting.gradientOnWatch = true
        var heard = 0
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.accentColorChanged.collect { heard++ }
        }

        setting.pickAccent(gradient(blue, purple))
        assertTrue("a gradient from the color picked is heard", heard > 0)

        heard = 0
        setting.pickAccent(gradient(blue, purple, style = AccentGradientStyle.Radial))
        assertTrue("a new style is heard", heard > 0)

        heard = 0
        setting.gradientOnWatch = false
        assertTrue("the switch is heard", heard > 0)

        heard = 0
        setting.pickAccent(gradient(blue, purple, style = AccentGradientStyle.Mesh))
        assertEquals("a gradient the watch is not sent changes nothing it is sent", 0, heard)
        setting.gradientOnWidgets = true
        assertEquals("nor does another surface's switch", 0, heard)

        listening.cancel()
    }

    // The watch bridge reads the preferences each time it hears a change. A
    // pick heard half stored would publish an accent nobody picked.
    @Test
    fun `each change the watch hears is a whole pick`() = runTest {
        val setting = AccentSetting(prefs).apply {
            pick(blue)
            gradientOnWatch = true
        }
        val sent = mutableListOf<Pair<String, AccentGradient?>>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.accentColorChanged.collect { sent += wearAccentHex(prefs) to wearAccentGradient(prefs) }
        }

        setting.pickAccent(gradient(0xFF2D55, 0xFF9500))
        setting.pick(0x34C759)

        assertEquals(listOf("#FF2D55" to gradient(0xFF2D55, 0xFF9500), "#34C759" to null), sent)
        listening.cancel()
    }

    // A reset while signed out publishes nothing else, so the watch would
    // keep the gradient the phone no longer draws.
    @Test
    fun `a full reset sends the watch Blue again`() = runTest {
        AccentSetting(prefs).apply {
            pickAccent(gradient(0xFF2D55, 0xFF9500))
            gradientOnWatch = true
        }
        val sent = mutableListOf<Pair<String, AccentGradient?>>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.accentColorChanged.collect { sent += wearAccentHex(prefs) to wearAccentGradient(prefs) }
        }

        prefs.clearAllPrefs()

        assertEquals(listOf("#007AFF" to null), sent)
        listening.cancel()
    }

    @Test
    fun `a full reset with the accent untouched tells the watch nothing`() = runTest {
        var heard = 0
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.accentColorChanged.collect { heard++ }
        }

        prefs.clearAllPrefs()

        assertEquals(0, heard)
        listening.cancel()
    }

    @Test
    fun `with the wallpaper picked the watch is sent the gradient last picked`() {
        AccentSetting(prefs).apply {
            pickAccent(gradient(0xFF2D55, 0xFF9500))
            gradientOnWatch = true
            pickWallpaper()
        }
        assertEquals("#FF2D55", wearAccentHex(prefs))
        assertEquals(gradient(0xFF2D55, 0xFF9500), wearAccentGradient(prefs))
    }
}
