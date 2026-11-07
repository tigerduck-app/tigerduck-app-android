package org.ntust.app.tigerduck.liveactivity

import android.app.Application
import android.app.Notification
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.core.app.NotificationCompat
import org.ntust.app.tigerduck.notification.DeviceSkin
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.ui.AccentSetting
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the Live Update draws of the accent: a notification takes no gradient, so the stops go
 * where Android takes several colors, the progress bar's segments, and only while Settings'
 * Live Updates switch is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveUpdateSegmentsTest {

    private val gradient = requireNotNull(
        AccentGradient.of(listOf(0x007AFF, 0xAF52DE, 0xFF2D55), AccentGradientStyle.Angular),
    )

    private fun snapshot(accentGradient: AccentGradient?) = LiveActivitySnapshot(
        scenario = LiveActivityScenario.IN_CLASS,
        title = "Course",
        subtitle = "09:10",
        locationText = null,
        instructor = null,
        countdownTarget = null,
        progress = 0.5,
        accentHex = 0x007AFF,
        sourceId = "c_1",
        accentGradient = accentGradient,
    )

    // dev's notifier added `Segment(PROGRESS_MAX)`, whose color is 0, COLOR_DEFAULT: the bar then
    // takes the notification's own color, the brand's (brandedNotification).
    @Test
    fun `without a gradient the bar is the one segment dev drew, in the notification's own color`() {
        val segments = LiveUpdateSegments.of(snapshot(null), max = 1000)
        val dev = NotificationCompat.ProgressStyle.Segment(1000)

        assertEquals(listOf(dev.length), segments.map { it.length })
        assertEquals(dev.color, segments.single().color)
        assertEquals(Notification.COLOR_DEFAULT, segments.single().color)
        assertEquals(dev.id, segments.single().id)
    }

    @Test
    fun `the gradient can show only where the Live Update is drawn with ProgressStyle, the ColorOS card`() {
        fun skin(manufacturer: String, sdkInt: Int = 36) = DeviceSkin(
            sdkInt = sdkInt, manufacturer = manufacturer, brand = manufacturer,
            oneUiVersion = null, hyperOsVersion = null, vivoOverseas = false,
        )

        for (maker in listOf("OPPO", "OnePlus", "realme")) {
            assertEquals(maker, true, LiveUpdateSegments.canShowGradient(skin(maker)))
        }
        for (maker in listOf("Google", "samsung", "Xiaomi", "vivo", "HONOR")) {
            assertEquals(maker, false, LiveUpdateSegments.canShowGradient(skin(maker)))
        }
        // Before Android 16 no phone has a Live Update chip, nor the card.
        assertEquals(false, LiveUpdateSegments.canShowGradient(skin("OPPO", sdkInt = 35)))
    }

    @Test
    fun `with a gradient the bar has a segment for each stop, in its color, the whole length shared`() {
        val segments = LiveUpdateSegments.of(snapshot(gradient), max = 1000)

        assertEquals(listOf(0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt()), segments.map { it.color })
        assertEquals(1000, segments.sumOf { it.length })
        assertEquals(listOf(334, 333, 333), segments.map { it.length })
    }

    @Test
    fun `the Live Update is handed the gradient only while its switch is on`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = AppPreferences(context)
        val setting = AccentSetting(prefs).apply { pickAccent(gradient) }

        assertNull(LiveUpdateSegments.accentOf(prefs))
        setting.gradientOnLiveActivities = true
        assertEquals(gradient, LiveUpdateSegments.accentOf(prefs))
    }
}
