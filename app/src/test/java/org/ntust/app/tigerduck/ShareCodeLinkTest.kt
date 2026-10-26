@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * A class table share code opened as a link: from the camera, Google Lens or another QR app, a
 * tapped link, or the share page's "Open in TigerDuck" button. Any page or app can open one, so it
 * takes exactly the steps a scanned code does, and only for a signed-in student: one that comes
 * before is held until then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareCodeLinkTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val account = "B11300000"
    private val store by lazy {
        ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { account }, Dispatchers.Unconfined)
    }

    private val sharedAt = Instant.parse("2026-10-14T03:20:00Z")
    private val code = ClassTableShareCode.encode(
        "B11300001", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001", "MA1010001"))),
    )!!
    private val otherCode = ClassTableShareCode.encode(
        "B11300002", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))),
    )!!
    private val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")

    /** What follows the share host: the type, then the code. */
    private fun rest(code: String) = code.removePrefix(ClassTableShareCode.SHARE_ROOT)

    /** The code as it is written, the form App Links deliver, and the web page's button. */
    private val links = listOf(
        code,
        "https://share.tigerduck.app/${rest(code)}",
        "tigerduck://share/${rest(code)}",
    )

    private fun source(rel: String): String {
        val path = "src/main/java/org/ntust/app/tigerduck/$rel"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readText() }
            File(dir, "app/$path").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
    }

    private fun linkIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun take(link: String, restored: Boolean = false) = takeSharedClassTableCode(linkIntent(link), restored)

    private fun TestScope.model(
        lookup: suspend (String, List<String>) -> List<Course> = { _, _ -> listOf(calculus) },
    ) = ClassTableImportModel(store, { account }, { true }, lookup, this)

    /** The import a link opens over [open], as MainActivity hands it over. */
    private suspend fun TestScope.openLink(link: String, open: ClassTableImportModel? = null, next: ClassTableImportModel = model()) =
        ClassTableImportViewModel.linkedImport(store, { open }, { next }, take(link)!!)!!

    @Test
    fun `the code as written, in lower case and from the web page's button reach the decoder as the same code`() {
        val expected = ClassTableShareCode.decode(code)

        links.forEach { link ->
            val text = take(link)
            assertNotNull(link, text)
            assertEquals(link, expected, ClassTableShareCode.decode(text!!))
        }
        // Passed on as it came: the decoder trims and upper-cases it itself.
        assertEquals(code, take(code))
        assertEquals(links[1], take(links[1]))
    }

    @Test
    fun `a share link a chat app tagged with a query or a fragment reads as the same code`() {
        val expected = ClassTableShareCode.decode(code)

        for (tail in listOf("?fbclid=IwAR0abc", "#top", "?utm_source=line&x=1#top")) {
            listOf(code, links[1]).forEach { link ->
                val text = take(link + tail)
                assertNotNull(link + tail, text)
                assertEquals(link + tail, expected, ClassTableShareCode.decode(text!!))
            }
        }
    }

    @Test
    fun `a link on the share host that the decoder does not take says the code is damaged`() = runTest {
        // The host with a port and no code: a whole code after it would read, as the end of any
        // link does (decision record 0023), so none is there.
        val link = "https://share.tigerduck.app:443/"
        assertNotNull(take(link))

        val opened = openLink(link)

        assertEquals(ClassTableImportModel.Prompt.Invalid, opened.state.value.prompt)
    }

    @Test
    fun `a link whose account signs out while the store is read opens nothing`() = runTest {
        val open = model()
        var signedIn = true
        val next = model()
        val result = ClassTableImportViewModel.linkedImport(
            store, { open }, { next }, take(links[0])!!,
            // Signed out by the time the store's read is done.
            stillCurrent = { signedIn.also { signedIn = false } && signedIn },
        )
        advanceUntilIdle()

        assertEquals(null, result)
        assertEquals(null, next.state.value.pending)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a link opens the confirm step a scanned code does, and saves nothing by itself`() = runTest {
        links.forEach { link ->
            val model = openLink(link)
            advanceUntilIdle()

            assertEquals(link, ClassTableShareCode.decode(code), model.state.value.pending)
            assertEquals(link, "B11300001", model.state.value.nickname)
        }
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a link that starts the app asks to update a person imported already, once the store is read`() = runTest {
        // Imported in an earlier run; this run's store has not read its file yet.
        ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { account }, Dispatchers.Unconfined)
            .add(ClassTableShareCode.decode(code), "Ming", mapOf("1151" to listOf(calculus)))

        val model = openLink(links[1])

        assertEquals("Ming", (model.state.value.prompt as? Prompt.Replace)?.job?.nickname)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `a share host URL that is not a share code shows the malformed message`() = runTest {
        listOf(
            "https://share.tigerduck.app/",
            // The host alone, as the manifest's filter claims it too.
            "https://share.tigerduck.app",
            "https://share.tigerduck.app?from=mail",
            "https://share.tigerduck.app/CLASSTABLE/",
            "https://share.tigerduck.app/CLASSTABLE/hello",
            code.dropLast(1) + "A",
            "tigerduck://share",
            "tigerduck://share/hello",
        ).forEach { link ->
            val model = openLink(link)

            assertEquals(link, Prompt.Invalid, model.state.value.prompt)
            assertNull(link, model.state.value.pending)
        }
    }

    @Test
    fun `a link to a newer app's code asks for an update`() = runTest {
        listOf(
            "https://share.tigerduck.app/FRIEND/1.B11300001",
            "tigerduck://share/FRIEND/1.B11300001",
            "HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/2.B11300001",
        ).forEach { link ->
            val model = openLink(link)

            assertEquals(link, Prompt.NeedsUpdate, model.state.value.prompt)
            assertNull(link, model.state.value.pending)
        }
    }

    @Test
    fun `other links are left for the rest of the app`() {
        listOf(
            "https://tigerduck.app/",
            "https://tigerduck.app/guide",
            // Where codes were written before the move to the share host: no longer one.
            "https://tigerduck.app/T/${rest(code).substringAfter('/')}",
            "https://example.com/${rest(code)}",
            "http://share.tigerduck.app/${rest(code)}",
            "https://share.tigerduck.app.example.com/${rest(code)}",
            "https://share.tigerduck.app@example.com/${rest(code)}",
            "tigerduck://classtable",
            "tigerduck://announcement/42",
            "tigerduck://Share/${rest(code)}",
        ).forEach { link ->
            val intent = linkIntent(link)

            assertNull(link, takeSharedClassTableCode(intent, restored = false))
            assertEquals(link, link, intent.dataString)
        }
    }

    @Test
    fun `a link before sign-in or during onboarding is held, and opens once both are done`() {
        val held = HeldShareLink()

        assertNull(held.arrived(code, mayOpen = false))
        assertNull("still signed out", held.release(mayOpen = false))
        assertEquals(code, held.release(mayOpen = true))
        assertNull("it opens once", held.release(mayOpen = true))

        assertEquals("one that may open does so at once", code, held.arrived(code, mayOpen = true))
        assertNull(held.release(mayOpen = true))
    }

    @Test
    fun `a newer link replaces a held one, and a sign-out drops it`() {
        val held = HeldShareLink()

        held.arrived(code, mayOpen = false)
        held.arrived(otherCode, mayOpen = false)
        assertEquals(otherCode, held.release(mayOpen = true))

        held.arrived(code, mayOpen = false)
        assertEquals("one that opens at once replaces it too", otherCode, held.arrived(otherCode, mayOpen = true))
        assertNull(held.release(mayOpen = true))

        held.arrived(code, mayOpen = false)
        held.drop()
        assertNull(held.release(mayOpen = true))
    }

    @Test
    fun `MainActivity holds a link that cannot open yet, and the sign-out drops it`() {
        val activity = source("MainActivity.kt")
        assertTrue("a link goes through the holder", "heldShareLink.arrived(" in activity)
        assertTrue("and is released once someone may import it", "heldShareLink.release(" in activity)
        assertTrue("whenever the sign-in or onboarding changes", "snapshotFlow { appState.showOnboarding }" in activity)
        // Only by the Activity on screen: another one, stopped in another task, would open its import there.
        val release = activity.indexOf("heldShareLink.release(")
        assertTrue(
            "released only while started",
            "repeatOnLifecycle(Lifecycle.State.STARTED)" in activity.substring(maxOf(0, release - 600), release),
        )
        assertTrue(".drop() at sign-out", "heldShareLink.drop()" in source("auth/AuthService.kt"))
        // In memory only: nothing for it to be written with.
        assertTrue(HeldShareLink::class.java.declaredConstructors.all { it.parameterCount == 0 })
    }

    @Test
    fun `a link opens once per delivery, and not again for a rebuilt Activity or from Recents`() {
        val intent = linkIntent(code)

        assertNotNull(takeSharedClassTableCode(intent, restored = false))
        assertNull(intent.data)
        assertNull(takeSharedClassTableCode(intent, restored = false))

        assertNull(take(code, restored = true))
        val fromRecents = linkIntent(code).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(takeSharedClassTableCode(fromRecents, restored = false))
        assertNull(fromRecents.data)
    }

    @Test
    fun `a second link replaces an import that is open, and nothing of the first is saved`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val first = openLink(code, next = model { _, _ -> answer.await() })
        first.importPending()
        advanceUntilIdle()
        assertTrue(first.state.value.isLookingUp)

        val second = openLink("tigerduck://share/${rest(otherCode)}", open = first)
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertNull("the earlier code is dropped", first.state.value.pending)
        assertFalse(first.state.value.isLookingUp)
        assertEquals("B11300002", second.state.value.pending?.studentId)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a second link replaces a prompt the first one raised`() = runTest {
        val first = openLink("https://share.tigerduck.app/CLASSTABLE/hello")
        assertEquals(Prompt.Invalid, first.state.value.prompt)

        val second = openLink(code, open = first)

        assertNull(first.state.value.prompt)
        assertNull(second.state.value.prompt)
        assertEquals("B11300001", second.state.value.pending?.studentId)
    }

    @Test
    fun `a QR app or a browser opens the share host's links and the web page's button in MainActivity`() {
        val packageManager = context.packageManager
        listOf(
            "https://share.tigerduck.app/${rest(code)}",
            // The whole host is claimed, so a newer app's type reaches the import too.
            "https://share.tigerduck.app/FRIEND/1.B00015999",
            "tigerduck://share/${rest(code)}",
        ).forEach { link ->
            // A QR app may leave out the BROWSABLE category a browser adds.
            listOf(Intent(Intent.ACTION_VIEW, Uri.parse(link)), linkIntent(link)).forEach { intent ->
                assertEquals(link, MainActivity::class.java.name, packageManager.resolveActivity(intent, 0)?.activityInfo?.name)
            }
        }
    }

    @Test
    fun `the main site, and the code's upper-case spelling, stay with the browser`() {
        val packageManager = context.packageManager
        listOf(
            "https://tigerduck.app/",
            "https://tigerduck.app/guide",
            "https://tigerduck.app/T/${rest(code).substringAfter('/')}",
            // Android compares the scheme and host case-sensitively, and only the lower-case host is
            // verified; the web page's button opens the app for a scanner that opens the browser.
            code,
        ).forEach { link ->
            val resolved = packageManager.resolveActivity(linkIntent(link), 0)?.activityInfo?.name
            assertNotEquals(link, MainActivity::class.java.name, resolved)
        }
    }
}
