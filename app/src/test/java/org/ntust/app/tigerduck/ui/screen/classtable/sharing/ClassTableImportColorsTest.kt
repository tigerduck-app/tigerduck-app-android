package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scanner is black from its bar to its sources, whatever the theme: the page is the camera's picture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassTableImportColorsTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model = ClassTableImportModel(
        ImportedClassTableStore({ ImportedClassTableStore.fileIn(app) }, { "B11300000" }, Dispatchers.Unconfined),
        { "B11300000" }, { true }, { _, _ -> emptyList() }, scope,
    )

    @After
    fun stop() = scope.cancel()

    @Test
    fun `the bar and the page behind it are both black`() {
        composeRule.setContent {
            ClassTableImportPage(
                model, onClose = {}, onFinished = {}, onPickPicture = {},
                camera = { _, _, _ -> },
            )
        }
        composeRule.waitForIdle()
        val pixels = composeRule.onNode(isDialog()).captureToImage().toPixelMap()
        val density = composeRule.activity.resources.displayMetrics.density
        // Between the start icon and the title, inside the bar; then well below it, over the page.
        assertEquals("the bar", Color.Black, pixels[pixels.width / 2, (4 * density).toInt()])
        assertEquals("the page", Color.Black, pixels[pixels.width / 2, (200 * density).toInt()])
    }
}
