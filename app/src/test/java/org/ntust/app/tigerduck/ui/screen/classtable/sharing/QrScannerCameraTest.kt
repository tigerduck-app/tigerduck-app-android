package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The import page's camera with access granted but no camera that starts, as when another app
 * holds it: it offers Retry, and Retry tries again rather than leaving the page without a camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QrScannerCameraTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val retry get() = app.getString(R.string.action_retry)

    @Before
    fun grantCamera() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    @Test
    fun `a camera that does not start offers Retry, which tries again, and offers no flashlight`() {
        val torch = ScannerTorch()
        composeRule.setContent { QrScannerCamera(onScanned = { ClassTableImportModel.ScanAnswer.NOT_SHARE_CODE }, torch = torch) }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(retry)).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(torch.isPending)
        assertFalse(torch.isAvailable)
        composeRule.onNodeWithText(retry).performClick()

        // Tried again, and with still no camera, offered again.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(retry)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
