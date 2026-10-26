package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShare
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.material3.R as M3R

/** The share sheet's Copy link, and its way to the import scanner (#490), as the iOS sheet has them. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableShareSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** A fake student, of year 000. */
    private val share = ClassTableShare(
        "b00015999",
        listOf(
            ClassTableShare.Term(
                "1151",
                listOf(Course.fromSchedule(courseNo = "CS1010001", courseName = "A", schedule = mapOf(1 to listOf("3")))),
            ),
        ),
    )

    private val copyLink: String get() = app.getString(R.string.class_table_share_copy_link)

    @Test
    fun `Copy link puts the code shown on the clipboard as a link, and shows it copied for a moment`() {
        composeRule.setContent { ClassTableShareSheet(share, labelFor = { it }, onDismiss = {}, onScan = {}) }
        composeRule.mainClock.autoAdvance = false
        val copy = composeRule.onNodeWithContentDescription(copyLink)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        copy.performClick()
        composeRule.mainClock.advanceTimeBy(100)

        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip, clip.startsWith("https://share.tigerduck.app/CLASSTABLE/"))
        val read = ClassTableShareCode.decode(clip)
        assertEquals("B00015999", read.studentId)
        assertEquals(listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))), read.terms)
        val copied = app.getString(R.string.class_table_share_link_copied)
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, copied))

        composeRule.mainClock.advanceTimeBy(1_500)
        copy.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the scanner button closes the sheet, then opens the scanner`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            ClassTableShareSheet(share, labelFor = { it }, onDismiss = { calls += "dismiss" }, onScan = { calls += "scan" })
        }
        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import))
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss", "scan"), calls)
    }

    @Test
    fun `a scan whose close was caught and pulled back doesn't open the scanner at a later Done`() {
        val calls = mutableListOf<String>()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            if (shown) {
                ClassTableShareSheet(
                    share,
                    labelFor = { it },
                    onDismiss = { calls += "dismiss"; shown = false },
                    onScan = { calls += "scan" },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import)).performClick()
        composeRule.mainClock.advanceTimeBy(96)
        // A finger catches the sheet by its handle on the way down and pulls it back up.
        composeRule.onNodeWithContentDescription(app.getString(M3R.string.m3c_bottom_sheet_drag_handle_description)).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, -100f), delayMillis = 16) }
            // Held still before the lift: no fling, only where it was let go.
            repeat(8) { moveBy(Offset(0f, 0f), delayMillis = 16) }
            up()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("it closed anyway", emptyList<String>(), calls)

        composeRule.onNodeWithText(app.getString(R.string.action_done)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `the scanner button twice before the sheet has closed still opens the scanner once`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            ClassTableShareSheet(share, labelFor = { it }, onDismiss = { calls += "dismiss" }, onScan = { calls += "scan" })
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val scanner = composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import))
        scanner.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeBy(48)
        scanner.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss", "scan"), calls)
    }

    @Test
    fun `a sheet gone before it finished closing opens no scanner`() {
        var shown by mutableStateOf(true)
        var scans = 0
        composeRule.setContent {
            if (shown) ClassTableShareSheet(share, labelFor = { it }, onDismiss = { shown = false }, onScan = { scans++ })
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import)).performClick()
        composeRule.mainClock.advanceTimeByFrame()

        // A link closed it meanwhile.
        composeRule.runOnIdle { shown = false }
        composeRule.mainClock.advanceTimeBy(2_000)

        assertEquals(0, scans)
    }

    @Test
    fun `the scanner's share sheet closes for an import opened over it, and at a sign-out`() {
        var importOpen by mutableStateOf(false)
        var signedIn by mutableStateOf(true)
        var shared by mutableStateOf<ClassTableShare?>(share)
        composeRule.setContent {
            ScannerShareSheet(
                share = shared, importOpen = importOpen, signedIn = signedIn,
                labelFor = { it }, onDismiss = { shared = null }, onScan = {},
            )
        }
        val copy = composeRule.onNodeWithContentDescription(copyLink)
        copy.assertExists()

        // A share link opened its import.
        composeRule.runOnIdle { importOpen = true }
        composeRule.waitForIdle()
        copy.assertDoesNotExist()
        assertEquals(null, shared)

        composeRule.runOnIdle {
            importOpen = false
            shared = share
        }
        composeRule.waitForIdle()
        copy.assertExists()
        composeRule.runOnIdle { signedIn = false }
        composeRule.waitForIdle()
        copy.assertDoesNotExist()
        assertEquals(null, shared)
    }

    @Test
    fun `the scanner button sits at the top start, and Copy link beside Done at the top end`() {
        composeRule.setContent { ClassTableShareSheet(share, labelFor = { it }, onDismiss = {}, onScan = {}) }
        val scan = composeRule.onNodeWithContentDescription(app.getString(R.string.class_table_import)).getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText(app.getString(R.string.class_table_share)).getUnclippedBoundsInRoot()
        val copy = composeRule.onNodeWithContentDescription(copyLink).getUnclippedBoundsInRoot()
        val done = composeRule.onNodeWithText(app.getString(R.string.action_done)).getUnclippedBoundsInRoot()
        assertTrue(scan.right <= title.left)
        assertTrue(title.right <= copy.left)
        assertTrue(copy.right <= done.left)
    }
}
