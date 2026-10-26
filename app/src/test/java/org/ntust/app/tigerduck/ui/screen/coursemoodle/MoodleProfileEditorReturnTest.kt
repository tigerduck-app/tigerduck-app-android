package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The member page after Moodle's profile editor: the members are fetched again on coming back
 * from it, so the edit shows, as on the iPhone (tigerduck-app#802, #806), and on no other return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleProfileEditorReturnTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The page's back stack entry, which the test pauses and resumes as the editor opens and closes. */
    private val page = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun leaveAndComeBack() {
        composeRule.runOnIdle {
            page.registry.currentState = Lifecycle.State.STARTED
            page.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the members are fetched again once on coming back from the editor, and on no other return`() {
        var fetches = 0
        var openEditor: () -> Unit = {}
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                openEditor = rememberRefetchAfterEditor(refetch = { fetches++ })
            }
        }

        leaveAndComeBack()
        assertEquals("a return from anywhere else fetches nothing", 0, fetches)

        composeRule.runOnIdle { openEditor() }
        composeRule.waitForIdle()
        assertEquals("not before the editor closes", 0, fetches)

        leaveAndComeBack()
        assertEquals(1, fetches)

        leaveAndComeBack()
        assertEquals("once per edit", 1, fetches)
    }

    /**
     * The system ended the page while the editor was open: it comes back before the course's
     * store, and fetches once the store is there.
     */
    @Test
    fun `a page recreated while the editor was open fetches once its store is there`() {
        val restoration = StateRestorationTester(composeRule)
        var fetches = 0
        var storeReady by mutableStateOf(true)
        var openEditor: () -> Unit = {}
        restoration.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                openEditor = rememberRefetchAfterEditor(refetch = if (storeReady) ({ fetches++ }) else null)
            }
        }
        composeRule.runOnIdle { openEditor() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            storeReady = false
            page.registry.currentState = Lifecycle.State.STARTED
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { page.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        assertEquals("nothing to fetch with yet", 0, fetches)

        composeRule.runOnIdle { storeReady = true }
        composeRule.waitForIdle()
        assertEquals(1, fetches)

        leaveAndComeBack()
        assertEquals(1, fetches)
    }

    /**
     * Recreated while the fetch after the editor runs, as by a switch to dark mode: the new page
     * fetches and reports again, as the old page's wait, and its snackbar, went with it.
     */
    @Test
    fun `a page recreated while the fetch runs fetches again`() {
        val restoration = StateRestorationTester(composeRule)
        val answer = CompletableDeferred<Unit>()
        var fetches = 0
        var openEditor: () -> Unit = {}
        restoration.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                openEditor = rememberRefetchAfterEditor(refetch = { fetches++; answer.await() })
            }
        }
        composeRule.runOnIdle { openEditor() }
        leaveAndComeBack()
        assertEquals(1, fetches)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(2, fetches)

        composeRule.runOnIdle { answer.complete(Unit) }
        composeRule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals("done, nothing more", 2, fetches)
    }

    /**
     * Edited again while the first edit's fetch, or its snackbar, still waits: that fetch may have
     * read the profile before the second edit, so the second return fetches too.
     */
    @Test
    fun `a second edit while the first one's fetch waits fetches again`() {
        val answer = CompletableDeferred<Unit>()
        var fetches = 0
        var openEditor: () -> Unit = {}
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                openEditor = rememberRefetchAfterEditor(refetch = { fetches++; answer.await() })
            }
        }
        composeRule.runOnIdle { openEditor() }
        leaveAndComeBack()
        assertEquals(1, fetches)

        composeRule.runOnIdle { openEditor() }
        leaveAndComeBack()
        assertEquals(2, fetches)

        composeRule.runOnIdle { answer.complete(Unit) }
        leaveAndComeBack()
        assertEquals("done, nothing more", 2, fetches)
    }

    @Test
    fun `a failed fetch is offered again until it works or the student lets it go`() = runBlocking {
        val failure = IllegalStateException("offline")
        val answers = ArrayDeque(listOf<Throwable?>(failure, failure, null))
        val offered = mutableListOf<Throwable>()
        fetchMembersUntilDone(fetch = { answers.removeFirst() }, retry = { offered += it; true })
        assertEquals(listOf<Throwable>(failure, failure), offered)
        assertEquals(0, answers.size)

        var fetches = 0
        fetchMembersUntilDone(fetch = { fetches++; failure }, retry = { false })
        assertEquals("let go", 1, fetches)
    }
}
