package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * [rememberCjkCenteredTypography] around a stand-in measurement: when it
 * measures, what it keeps, and what it hands the theme meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CjkCenteredTypographyTest {

    @get:Rule
    val rule = createComposeRule()

    private val base = Typography()

    /** Every density measured at, and a raise that tells them apart. */
    private val measured: MutableList<Float> = Collections.synchronizedList(mutableListOf())
    private val measuring = CjkMeasuring { _, density, _, _ ->
        measured += density.density
        List(15) { CjkCentering.Correction(density.density / 100f) }
    }

    @Before
    fun forgetKept() {
        RuntimeEnvironment.getApplication().getSharedPreferences("cjk_centering", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun raise(typography: Typography?) = typography?.bodyLarge?.baselineShift

    @Test
    fun `another density measures afresh, and doesn't keep the last one's raise meanwhile`() {
        var density by mutableStateOf(2f)
        var seen: Typography? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                seen = rememberCjkCenteredTypography(base, measuring)
            }
        }
        rule.waitUntil(5_000) { raise(seen) == BaselineShift(0.02f) }

        density = 3f
        rule.waitForIdle()
        val meanwhile = raise(seen)
        rule.waitUntil(5_000) { raise(seen) == BaselineShift(0.03f) }

        // Until the new measurement is in, the styles are as they were, not the old raise.
        assertTrue("kept $meanwhile from the last density", meanwhile == null || meanwhile == BaselineShift(0.03f))
        assertEquals(listOf(2f, 3f), measured)
    }

    @Test
    fun `a kept measurement is drawn at once, without measuring again`() {
        var shown by mutableStateOf(true)
        var seen: Typography? = null
        rule.setContent { if (shown) seen = rememberCjkCenteredTypography(base, measuring) }
        rule.waitUntil(5_000) { raise(seen) != null }
        val first = raise(seen)
        measured.clear()

        // As a cold start: a new composition reads what the last one kept.
        shown = false
        rule.waitForIdle()
        seen = null
        shown = true
        rule.waitForIdle()

        assertEquals(first, raise(seen))
        assertEquals(emptyList<Float>(), measured)
    }
}
