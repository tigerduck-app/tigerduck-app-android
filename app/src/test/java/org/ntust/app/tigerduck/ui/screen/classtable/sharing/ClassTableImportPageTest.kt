package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * While the import writes the person, nothing closes it: not Close, not Cancel, as iOS refuses a
 * swipe while busy. The page closes once the person is saved, and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableImportPageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val storeThread = HeldStoreDispatcher()
    private val store = ImportedClassTableStore({ ImportedClassTableStore.fileIn(app) }, { "B11300000" }, storeThread)
    private val model = ClassTableImportModel(
        store, { "B11300000" }, { true }, { _, _ -> listOf(Course(courseNo = "MA1010001", courseName = "Calculus")) }, scope,
    )
    private val code = ClassTableShareCode.encode(
        "B11300001", Instant.parse("2026-10-14T03:20:00Z"), listOf(ClassTableShareCode.Term("1151", listOf("MA1010001"))),
    )!!

    @After
    fun stop() = scope.cancel()

    @Test
    fun `close and cancel do nothing while the person is saved, and the page closes once it is`() {
        var closed = 0
        var finished = 0
        composeRule.setContent {
            ClassTableImportPage(model, onClose = { closed++ }, onFinished = { finished++ }, onPickPicture = {})
        }
        composeRule.runOnIdle { model.imageRead(listOf(code)) }
        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).assertIsEnabled()

        composeRule.onNodeWithText(app.getString(R.string.action_import)).performClick()
        composeRule.waitForIdle()

        // The lookup answered and the write is held: too late to leave.
        val close = composeRule.onNodeWithContentDescription(app.getString(R.string.action_close))
        close.assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(0, closed)
        assertEquals(0, finished)

        composeRule.runOnIdle { storeThread.release() }
        composeRule.waitForIdle()

        assertEquals(1, finished)
        assertEquals(0, closed)
        assertEquals(listOf("B11300001"), store.tables.value.people.map { it.nickname })
    }

    @Test
    fun `a code from a newer app says the app needs an update, under the import's failure title`() {
        composeRule.setContent {
            ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {})
        }
        composeRule.runOnIdle { model.imageRead(listOf("HTTPS://SHARE.TIGERDUCK.APP/FRIEND/1.B11300001")) }

        composeRule.onNodeWithText(app.getString(R.string.class_table_import_failed)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.share_code_needs_update_message)).assertExists()
    }

    @Test
    fun `a scanned code called off leaves the scanner open for another`() {
        var closed = 0
        composeRule.setContent {
            ClassTableImportPage(model, onClose = { closed++ }, onFinished = {}, onPickPicture = {})
        }
        composeRule.runOnIdle { model.imageRead(listOf(code)) }

        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(0, closed)
        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import_choose_photo)).assertExists()
    }

    @Test
    fun `a link's import offers no photo and closes once its code is called off`() {
        val linked = linkedModel()
        var closed = 0
        composeRule.setContent {
            ClassTableImportPage(linked, onClose = { closed++ }, onFinished = {}, onPickPicture = {})
        }
        composeRule.runOnIdle { linked.scanned(code) }

        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import_choose_photo)).assertDoesNotExist()
        assertEquals(0, closed)

        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun `a link whose code a newer app wrote closes with its prompt`() {
        val linked = linkedModel()
        var closed = 0
        composeRule.setContent {
            ClassTableImportPage(linked, onClose = { closed++ }, onFinished = {}, onPickPicture = {})
        }
        composeRule.runOnIdle { linked.scanned("HTTPS://SHARE.TIGERDUCK.APP/FRIEND/1.B11300001") }
        assertEquals(0, closed)

        composeRule.onNodeWithText(app.getString(R.string.action_confirm)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun `a link that replaces a link's import keeps the new one open`() {
        val first = linkedModel()
        first.scanned(code)
        var current by mutableStateOf(first)
        var closed = 0
        composeRule.setContent {
            ClassTableImportPage(current, onClose = { closed++ }, onFinished = {}, onPickPicture = {})
        }
        composeRule.waitForIdle()

        // As linkedImport does: the import on screen is called off, and the link's own takes its place.
        composeRule.runOnIdle {
            first.cancel()
            current = linkedModel().also { it.scanned(code) }
        }
        composeRule.waitForIdle()

        assertEquals(0, closed)
        composeRule.onNodeWithText(app.getString(R.string.action_import)).assertExists()
    }

    // --- Sharing from the scanner, and a typed link (#490) ---

    /** A code for a fake student, of year 000. */
    private val fakeCode = ClassTableShareCode.encode(
        "B00015999", Instant.parse("2026-10-14T03:20:00Z"), listOf(ClassTableShareCode.Term("1151", listOf("MA1010001"))),
    )!!

    private val enterLink: String get() = app.getString(R.string.class_table_import_enter_link)

    @Test
    fun `the share button shows only for someone with a class table to share, and hands over to it`() {
        var shared = 0
        var onShare by mutableStateOf<(() -> Unit)?>(null)
        composeRule.setContent {
            ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {}, onShare = onShare)
        }
        val share = app.getString(R.string.class_table_share)
        composeRule.onNodeWithContentDescription(share).assertDoesNotExist()

        composeRule.runOnIdle { onShare = { shared++ } }
        composeRule.onNodeWithContentDescription(share).performClick()
        composeRule.waitForIdle()

        assertEquals(1, shared)
    }

    @Test
    fun `the scanner asks for the user's terms to share as it opens, once`() {
        var refreshed = 0
        composeRule.setContent {
            ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {}, refreshShare = { refreshed++ })
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { model.imageRead(listOf(fakeCode)) }
        composeRule.waitForIdle()

        assertEquals(1, refreshed)
    }

    @Test
    fun `the scanner asks again once the class table it shares from changes, as when its first load ends`() {
        var refreshed = 0
        var source by mutableStateOf<Any?>(emptyList<String>())
        composeRule.setContent {
            ClassTableImportPage(
                model, onClose = {}, onFinished = {}, onPickPicture = {},
                refreshShare = { refreshed++ }, shareSource = source,
            )
        }
        composeRule.waitForIdle()
        assertEquals(1, refreshed)

        composeRule.runOnIdle { source = listOf("MA1010001") }
        composeRule.waitForIdle()

        assertEquals(2, refreshed)
    }

    @Test
    fun `a link's page asks for no terms to share`() {
        var refreshed = 0
        composeRule.setContent {
            ClassTableImportPage(linkedModel(), onClose = {}, onFinished = {}, onPickPicture = {}, refreshShare = { refreshed++ })
        }
        composeRule.waitForIdle()

        assertEquals(0, refreshed)
    }

    @Test
    fun `a link's page offers neither the share button nor Enter link`() {
        composeRule.setContent {
            ClassTableImportPage(linkedModel(), onClose = {}, onFinished = {}, onPickPicture = {}, onShare = {})
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_share)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(enterLink).assertDoesNotExist()
    }

    @Test
    fun `a typed link goes through the import as a scanned code, and Import waits for some text`() {
        composeRule.setContent { ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {}) }
        composeRule.onNodeWithContentDescription(enterLink).performClick()

        val import = composeRule.onNodeWithText(app.getString(R.string.action_import))
        import.assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("  ")
        import.assertIsNotEnabled()

        // As pasted from a chat: lower case, no scheme, and a tracking query.
        composeRule.onNode(hasSetTextAction()).performTextReplacement(fakeCode.removePrefix("HTTPS://").lowercase() + "?fbclid=x")
        import.assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(ClassTableShareCode.decode(fakeCode), model.state.value.pending)
    }

    @Test
    fun `a typed link the decoder does not take says the code is damaged`() {
        composeRule.setContent { ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {}) }
        composeRule.onNodeWithContentDescription(enterLink).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("hello")
        composeRule.onNodeWithText(app.getString(R.string.action_import)).performClick()
        composeRule.waitForIdle()

        assertEquals(ClassTableImportModel.Prompt.Invalid, model.state.value.prompt)
        composeRule.onNodeWithText(app.getString(R.string.class_table_import_invalid_message)).assertExists()
    }

    @Test
    fun `the camera takes no code while a link is typed`() {
        var onScanned: ((String) -> ClassTableImportModel.ScanAnswer)? = null
        composeRule.setContent {
            ClassTableImportPage(
                model, onClose = {}, onFinished = {}, onPickPicture = {},
                camera = { scanned, _, _ -> onScanned = scanned },
            )
        }
        composeRule.onNodeWithContentDescription(enterLink).performClick()
        composeRule.runOnIdle { assertEquals(ClassTableImportModel.ScanAnswer.BUSY, onScanned!!(fakeCode)) }
        assertNull(model.state.value.pending)

        composeRule.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(ClassTableImportModel.ScanAnswer.TAKEN, onScanned!!(fakeCode)) }
        assertNotNull(model.state.value.pending)
    }

    // --- The bottom row: flashlight, Choose from photos, Enter link ---

    private val flashlight: String get() = app.getString(R.string.class_table_import_flashlight)

    @Test
    fun `the flashlight is a switch read with its label once a camera with a flash is bound, and the row keeps its place meanwhile`() {
        var torch: ScannerTorch? = null
        val sent = mutableListOf<Boolean>()
        composeRule.setContent {
            ClassTableImportPage(
                model, onClose = {}, onFinished = {}, onPickPicture = {},
                camera = { _, scannerTorch, _ -> torch = scannerTorch },
            )
        }
        composeRule.onNodeWithContentDescription(flashlight).assertDoesNotExist()
        val photos = composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import_choose_photo))
        val before = placeIn(photos)

        composeRule.runOnIdle { torch!!.bound(sent::add) }
        val toggle = composeRule.onNodeWithContentDescription(flashlight)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        // Reserved while the camera started, so the other two do not move once it answers.
        assertEquals(before, placeIn(photos))

        toggle.performClick()
        composeRule.runOnIdle { assertEquals(listOf(true), sent) }
        composeRule.runOnIdle { torch!!.stateChanged(on = true) }
        toggle.assertIsOn()
    }

    /**
     * [node]'s left and right, and its distance from the bottom of the page's window, read from one
     * layout. The dialog first lays the page out a pixel taller than its window and fits it on a
     * later pass, so the top is not kept. That pass can land between two reads, each of which waits
     * for idle first, as when the theme's text measurement answers from its own thread.
     */
    private fun placeIn(node: SemanticsNodeInteraction): Triple<Dp, Dp, Dp> {
        val semantics = node.fetchSemanticsNode()
        val windowHeight = generateSequence(semantics) { it.parent }.last().size.height
        return with(composeRule.density) {
            val left = semantics.positionInRoot.x
            val bottom = semantics.positionInRoot.y + semantics.size.height
            Triple(left.toDp(), (left + semantics.size.width).toDp(), (windowHeight - bottom).toDp())
        }
    }

    @Test
    fun `without a flash the row holds only Choose from photos and Enter link, centered`() {
        var torch: ScannerTorch? = null
        composeRule.setContent {
            ClassTableImportPage(
                model, onClose = {}, onFinished = {}, onPickPicture = {},
                camera = { _, scannerTorch, _ -> torch = scannerTorch },
            )
        }
        composeRule.runOnIdle { torch!!.bound(enable = null) }
        composeRule.onNodeWithContentDescription(flashlight).assertDoesNotExist()

        val photos = composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import_choose_photo))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val link = composeRule.onNodeWithContentDescription(enterLink)
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        // The page fills the window, which is the dialog's root.
        val middle = (photos.left + link.right) / 2
        assertEquals(app.resources.configuration.screenWidthDp / 2f, middle.value, 1f)
    }

    @Test
    fun `Close sits at the top end and the share button at the top start`() {
        composeRule.setContent {
            ClassTableImportPage(model, onClose = {}, onFinished = {}, onPickPicture = {}, onShare = {})
        }
        val close = composeRule.onNodeWithContentDescription(app.getString(R.string.action_close)).getUnclippedBoundsInRoot()
        val share = composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_share)).getUnclippedBoundsInRoot()
        assertTrue("$share before $close", share.right <= close.left)
    }

    private fun linkedModel() = ClassTableImportModel(
        store, { "B11300000" }, { true }, { _, _ -> listOf(Course(courseNo = "MA1010001", courseName = "Calculus")) }, scope,
        fromLink = true,
    )
}

/** Holds what is dispatched to it until [release] runs it, as a busy I/O thread would. */
private class HeldStoreDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        held.addLast(block)
    }

    fun release() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}
