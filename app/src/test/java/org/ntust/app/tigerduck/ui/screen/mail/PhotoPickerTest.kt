package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// API 33, where every phone has the framework's Photo Picker: below it androidx
// looks for an SDK extension or another picker first, and may fall back to the
// document picker.
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class PhotoPickerTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the photo button opens the system photo picker for images and hands back every pick`() {
        val context = RuntimeEnvironment.getApplication()
        val first = Uri.parse("content://media/picker/0/1")
        val second = Uri.parse("content://media/picker/0/2")
        val launched = mutableListOf<Intent>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract.createIntent(context, input)
                val result = Intent().apply { clipData = ClipData.newRawUri(null, first).apply { addItem(ClipData.Item(second)) } }
                dispatchResult(requestCode, Activity.RESULT_OK, result)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val picked = mutableListOf<List<Uri>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val pick = rememberPhotoPicker { picked += it }
                Button(onClick = pick) { Text("photos") }
            }
        }
        composeRule.onNodeWithText("photos").performClick()
        composeRule.waitForIdle()
        assertEquals(MediaStore.ACTION_PICK_IMAGES, launched.single().action)
        assertEquals("image/*", launched.single().type)
        assertEquals(listOf(listOf(first, second)), picked)
    }
}
