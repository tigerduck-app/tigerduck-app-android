package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.settings.previewDensity
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app's display and text sizes laid over the phone's configuration, as
 * MainActivity applies them, and an AppCompat Activity keeping them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-420dpi")
class AppScaleTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun phone(fontScale: Float = 1f) = Configuration().apply {
        densityDpi = 420
        screenWidthDp = 411
        screenHeightDp = 891
        smallestScreenWidthDp = 411
        this.fontScale = fontScale
    }

    @Test
    fun `the default sizes lay nothing over the phone's`() {
        assertNull(AppScale.DEFAULT.overlay(phone()))
        assertSame(context, AppScale.DEFAULT.wrap(context))
    }

    @Test
    fun `a larger display size is a higher density, and the screen fewer dp across`() {
        val overlay = AppScale(1.2f, 1f).overlay(phone())!!

        assertEquals(504, overlay.densityDpi)
        assertEquals(342, overlay.screenWidthDp)
        assertEquals(742, overlay.screenHeightDp)
        assertEquals(342, overlay.smallestScreenWidthDp)
        assertEquals(1f, overlay.fontScale)
    }

    @Test
    fun `a smaller display size is a lower density, and the screen more dp across`() {
        val overlay = AppScale(0.75f, 1f).overlay(phone())!!

        assertEquals(315, overlay.densityDpi)
        assertEquals(548, overlay.screenWidthDp)
    }

    @Test
    fun `a text size alone leaves the density and the screen to the phone`() {
        val overlay = AppScale(1f, 1.3f).overlay(phone(fontScale = 1.15f))!!

        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, overlay.densityDpi)
        assertEquals(Configuration.SCREEN_WIDTH_DP_UNDEFINED, overlay.screenWidthDp)
        assertEquals(1.495f, overlay.fontScale, 0.0001f)
    }

    @Test
    fun `the text size stops at the phone's largest font scale, unless the phone is past it`() {
        assertEquals(2f, AppScale(1f, 1.5f).fontScale(1.8f), 0.0001f)
        assertEquals(2.2f, AppScale(1f, 1.5f).fontScale(2.2f), 0.0001f)
        assertEquals(0.68f, AppScale(1f, 0.8f).fontScale(0.85f), 0.0001f)
    }

    @Test
    fun `a wrapped context draws at the sizes`() {
        val scaled = AppScale(1.2f, 1.3f).wrap(context)

        assertEquals(504, scaled.resources.configuration.densityDpi)
        assertEquals(3.15f, scaled.resources.displayMetrics.density, 0.0001f)
        assertEquals(1.3f, scaled.resources.configuration.fontScale, 0.0001f)
        assertEquals(342, scaled.resources.configuration.screenWidthDp)
    }

    @Test
    fun `a rotation leaves a resized screen stale, and nothing else does`() {
        val attached = phone()
        val rotated = phone().apply {
            screenWidthDp = 891
            screenHeightDp = 411
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }

        assertTrue(AppScale(1.2f, 1f).isStale(attached, rotated))
        assertFalse(AppScale(1.2f, 1f).isStale(attached, phone()))
        // The text size alone lays no screen size over the phone's.
        assertFalse(AppScale(1f, 1.3f).isStale(attached, rotated))
    }

    /**
     * On a phone AppCompat wraps the base context to apply its own night mode
     * and locales, carrying over what a wrapped base changed as a delta of its
     * configuration. Robolectric skips that branch, so the delta is taken here
     * as AppCompat takes it.
     */
    @Test
    fun `AppCompat's delta of a wrapped base carries the sizes over`() {
        val base = AppScale(1.2f, 1.3f).wrap(context)
        val reference = base.createConfigurationContext(
            Configuration().apply {
                uiMode = -1
                fontScale = 0f
            },
        ).resources.configuration
        val baseConfig = base.resources.configuration
        reference.uiMode = baseConfig.uiMode
        val generateConfigDelta = Class.forName("androidx.appcompat.app.AppCompatDelegateImpl")
            .getDeclaredMethod("generateConfigDelta", Configuration::class.java, Configuration::class.java)
            .apply { isAccessible = true }

        val delta = generateConfigDelta.invoke(null, reference, baseConfig) as Configuration

        assertEquals(504, delta.densityDpi)
        assertEquals(1.3f, delta.fontScale, 0.0001f)
        assertEquals(342, delta.screenWidthDp)
        assertEquals(742, delta.screenHeightDp)
        assertEquals(342, delta.smallestScreenWidthDp)
    }

    @Test
    fun `the preview draws at the dragged sizes over the phone's, capped as the app is`() {
        val preview = previewDensity(systemDensity = 2.625f, systemFontScale = 1.8f, ui = 1.2f, text = 1.5f)

        assertEquals(3.15f, preview.density, 0.0001f)
        assertEquals(2f, preview.fontScale, 0.0001f)
        assertEquals(1.8f, previewDensity(2.625f, 1.8f, ui = 1f, text = 1f).fontScale, 0.0001f)
    }

    @Test
    fun `an AppCompat Activity keeps the sizes, and Compose draws at them`() {
        val controller = Robolectric.buildActivity(ScaledActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertEquals(504, activity.resources.configuration.densityDpi)
        assertEquals(1.3f, activity.resources.configuration.fontScale, 0.0001f)
        val density = activity.composeDensity
        assertNotNull(density)
        assertEquals(3.15f, density!!.density, 0.0001f)
        assertEquals(1.3f, density.fontScale, 0.0001f)
    }
}

/** An AppCompat Activity sized as MainActivity sizes itself. */
class ScaledActivity : AppCompatActivity() {

    var composeDensity: Density? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppScale(1.2f, 1.3f).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        setContent { composeDensity = LocalDensity.current }
    }
}
