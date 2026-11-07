package org.ntust.app.tigerduck.browser

import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.X509Certificate

/**
 * When the password follows the student id: the mark the page answers, the certificate read then
 * and again before the fill, the pinned request, and the gates. The page is a fake that answers
 * as the scripts would ([BrowserSiteSignInScriptsTest] reads what they are made of), and the
 * pinned request is one too ([PinnedPageCertificateTest] makes real ones).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSiteSignInTest {

    private var signedIn = true
    private var usable = true
    private var generation = 7
    private var storedId: String? = STUDENT_ID
    private var storedPassword: String? = PASSWORD
    private val reads = mutableListOf<String>()

    private val pageLeaf = certificate()
    private val otherLeaf = certificate()

    /** What the pinned request meets, and what happens while it is out. */
    private var served: X509Certificate? = pageLeaf
    private var failure: Exception? = null
    private var checkTakesMs = 0L
    private var whileChecking: () -> Unit = {}
    private val requests = mutableListOf<String>()

    /** The WebView's page as a fake: it answers the mark with [answer], and the fill with true. */
    private inner class Page : ShownPage {
        override var url: String? = CARD_SYSTEM
        override var certificate: X509Certificate? = pageLeaf
        var answer: () -> String? = { """{"token":"$TOKEN","href":"$url"}""" }
        /** What happens between the mark's run in the page and its answer reaching the app. */
        var whileAnswering: () -> Unit = {}
        val marks = mutableListOf<String>()
        val fills = mutableListOf<String>()

        override suspend fun run(script: String): String? {
            if (!script.contains("crypto.getRandomValues")) {
                fills += script
                return "true"
            }
            marks += script
            val reply = answer()
            whileAnswering()
            return reply
        }
    }

    private var page = Page()

    private fun certificate() = HeldCertificate.Builder().addSubjectAlternativeName("cardsystem.ntust.edu.tw").build().certificate

    private fun newSignIn() = BrowserSiteSignIn(
        signedIn = { signedIn },
        studentId = { reads += "studentId"; storedId },
        password = { reads += "password"; storedPassword },
        usable = { usable },
        generation = { generation },
        pinnedLeaf = { url ->
            requests += url
            whileChecking()
            delay(checkTakesMs)
            failure?.let { throw it }
            served
        },
    )

    private var signIn = newSignIn()

    private suspend fun fill(url: String = CARD_SYSTEM, session: Int? = 7) = signIn.fill(url, session, page)

    private fun value(script: String, name: String): String? {
        val literal = Regex("""\b$name = ("(?:[^"\\]|\\.)*"|null)[,;]""").find(script)?.groupValues?.get(1)
        assertNotNull("$name in the script", literal)
        return Gson().fromJson(literal, String::class.java)
    }

    /** The page was marked, and its student id filled, but no password went to it. */
    private fun assertNoPassword(case: String = "") {
        assertEquals("$case: one mark", 1, page.marks.size)
        assertTrue("$case: no fill", page.fills.isEmpty())
        assertFalse("$case: the password is not even read", "password" in reads)
    }

    @Test
    fun `the mark goes at once, and the fill carries its token and the password once the leaf is the one a pinned request met`() = runTest {
        fill()

        assertEquals(1, page.marks.size)
        assertEquals(STUDENT_ID, value(page.marks.single(), "studentId"))
        assertEquals(listOf(CARD_SYSTEM), requests)
        assertEquals(1, page.fills.size)
        assertEquals(TOKEN, value(page.fills.single(), "token"))
        assertEquals(PASSWORD, value(page.fills.single(), "password"))
    }

    @Test
    fun `the pinned request is a HEAD of the page itself, not its query`() = runTest {
        page.url = "$CARD_SYSTEM?lang=en#top"
        fill(url = "$CARD_SYSTEM?lang=en#top")

        assertEquals(listOf(CARD_SYSTEM), requests)
        assertEquals(1, page.fills.size)
    }

    @Test
    fun `no token, a malformed one or an answer that is not a mark, no fill, and no request`() = runTest {
        val answers = listOf(
            """{"token":null,"href":"$CARD_SYSTEM"}""",
            """{"token":"abc","href":"$CARD_SYSTEM"}""",
            """{"href":"$CARD_SYSTEM"}""",
            BrowserSiteSignInScripts.FAILED,
            "null",
            null,
            "true",
        )
        answers.forEach { answer ->
            page = Page().also { it.answer = { answer } }
            reads.clear()
            fill()
            assertNoPassword(answer.toString())
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a mark made at another address than the one the WebView shows, no fill`() = runTest {
        listOf("https://cardsystem.ntust.edu.tw/std/index.php/user/agreement", "$CARD_SYSTEM#top", "https://example.com/").forEach { href ->
            page = Page().also { it.answer = { """{"token":"$TOKEN","href":"$href"}""" } }
            fill()
            assertNoPassword(href)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the certificate is read as the mark answers, not before`() = runTest {
        page.certificate = null
        page.whileAnswering = { page.certificate = pageLeaf }
        fill()
        assertEquals(1, page.fills.size)

        page = Page().also { it.whileAnswering = { it.certificate = otherLeaf } }
        reads.clear()
        fill()
        assertNoPassword("another leaf by the answer")
    }

    @Test
    fun `a certificate that changes while the check is out, no fill`() = runTest {
        whileChecking = { page.certificate = otherLeaf }
        fill()
        assertNoPassword()
    }

    @Test
    fun `a leaf other than the page's, a failed or slow request, or no certificate, no fill`() = runTest {
        served = otherLeaf
        fill()
        assertNoPassword("other leaf")

        page = Page()
        served = pageLeaf
        failure = IOException("pins not met")
        fill()
        assertNoPassword("failed")

        page = Page()
        failure = null
        served = null
        fill()
        assertNoPassword("no leaf")

        page = Page()
        served = pageLeaf
        checkTakesMs = 60_000
        val start = currentTime
        fill()
        assertNoPassword("slow")
        assertEquals(BrowserSiteSignIn.CHECK_TIMEOUT_MS, currentTime - start)

        page = Page().also { it.certificate = null }
        requests.clear()
        fill()
        assertNoPassword("no certificate")
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a page that moved on, a session that ended or a browser that closed while the check was out, no fill`() = runTest {
        val changes = listOf<() -> Unit>(
            { page.url = "https://cardsystem.ntust.edu.tw/std/index.php/user/agreement" },
            { page.url = null },
            { page.certificate = null },
            { generation = 8 },
            { signedIn = false },
            { usable = false },
        )
        changes.forEachIndexed { index, change ->
            signIn = newSignIn()
            page = Page()
            generation = 7
            signedIn = true
            usable = true
            reads.clear()
            whileChecking = change
            fill()
            assertNoPassword("change $index")
        }
    }

    @Test
    fun `a fill whose screen closes while the check is out gives no password`() = runTest {
        checkTakesMs = 1_000
        val job = launch { fill() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertNoPassword()
    }

    @Test
    fun `a second flow on the same page, whose mark finds the token there and answers none, leaves the first flow's fill alone`() = runTest {
        checkTakesMs = 1_000
        val first = launch { fill() }
        runCurrent()
        page.answer = { """{"token":null,"href":"$CARD_SYSTEM"}""" }
        val second = launch { fill() }
        runCurrent()
        assertTrue("the second flow ends at its mark", second.isCompleted)
        first.join()

        assertEquals(2, page.marks.size)
        assertEquals(1, requests.size)
        assertEquals(TOKEN, value(page.fills.single(), "token"))
        assertEquals(PASSWORD, value(page.fills.single(), "password"))
    }

    @Test
    fun `a leaf met before skips the request, but not the mark nor the checks`() = runTest {
        fill()
        page = Page()
        fill()

        assertEquals("one request in all", 1, requests.size)
        assertEquals(1, page.marks.size)
        assertEquals(TOKEN, value(page.fills.single(), "token"))

        page = Page().also { it.answer = { """{"token":null,"href":"$CARD_SYSTEM"}""" } }
        reads.clear()
        fill()
        assertNoPassword("no token, though the leaf was met")

        page = Page().also { it.whileAnswering = { it.url = "https://cardsystem.ntust.edu.tw/std/index.php/user/agreement" } }
        reads.clear()
        fill()
        assertNoPassword("moved on, though the leaf was met")

        page = Page().also { it.whileAnswering = { it.certificate = otherLeaf } }
        reads.clear()
        fill()
        assertNoPassword("another leaf, though one was met")
        assertEquals(2, requests.size)
    }

    @Test
    fun `a page leaf other than the one met before is checked again, and one that fails is forgotten`() = runTest {
        fill()

        page = Page().also { it.certificate = otherLeaf }
        served = otherLeaf
        fill()
        assertEquals("checked again", 2, requests.size)
        assertEquals(1, page.fills.size)

        page = Page().also { it.certificate = certificate() }
        served = pageLeaf
        fill()
        assertEquals(3, requests.size)
        assertTrue(page.fills.isEmpty())

        page = Page().also { it.certificate = otherLeaf }
        served = otherLeaf
        fill()
        assertEquals("the leaf met before was forgotten", 4, requests.size)
    }

    @Test
    fun `a leaf met under an earlier browser session is checked again`() = runTest {
        fill()
        generation = 8
        page = Page()
        fill(session = 8)
        assertEquals(2, requests.size)
        assertEquals(1, page.fills.size)
    }

    @Test
    fun `the suggestion box is marked for its student id alone, with no request and no fill`() = runTest {
        page.url = "https://suggestionbox.ntust.edu.tw/login"
        fill(url = "https://suggestionbox.ntust.edu.tw/login")

        assertEquals(1, page.marks.size)
        assertEquals("account", value(page.marks.single(), "studentIdField"))
        assertEquals(null, value(page.marks.single(), "passwordField"))
        assertTrue(page.fills.isEmpty())
        assertTrue(requests.isEmpty())
        assertFalse("password" in reads)
    }

    @Test
    fun `an unlisted page gets nothing, and neither the account nor the network is asked`() = runTest {
        listOf(
            "https://cardsystem.ntust.edu.tw/std/index.php/user/agreement",
            "http://cardsystem.ntust.edu.tw/std/index.php/user/login",
            "https://cardsystem.ntust.edu.tw:8443/std/index.php/user/login",
            "https://ssoam2.ntust.edu.tw/account/login",
            "https://example.com/login",
        ).forEach { fill(url = it) }
        assertTrue(page.marks.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the demo account, a signed-out app, an ended session or an unusable browser get nothing`() = runTest {
        signedIn = false
        fill()
        signedIn = true
        fill(session = 6)
        fill(session = null)
        usable = false
        fill()
        assertTrue(page.marks.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with no stored student id nothing is filled, and with no stored password no fill follows the mark`() = runTest {
        storedId = null
        fill()
        storedId = " "
        fill()
        assertTrue(page.marks.isEmpty())

        storedId = STUDENT_ID
        storedPassword = null
        fill()
        assertEquals(1, page.marks.size)
        assertTrue(page.fills.isEmpty())
    }

    private companion object {
        const val CARD_SYSTEM = "https://cardsystem.ntust.edu.tw/std/index.php/user/login"
        const val STUDENT_ID = "B10900000"
        const val PASSWORD = "fake-pass"
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
