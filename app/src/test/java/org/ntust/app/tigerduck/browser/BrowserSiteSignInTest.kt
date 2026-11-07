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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.X509Certificate

/**
 * Which pages get the stored account, when the password follows the student id, and how both go
 * into the script that fills a form. The pinned request is a fake here ([PinnedPageCertificateTest]
 * makes real ones), and the script's work in a page needs a WebView, which the JVM has none of:
 * what it is made of is read here.
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

    /** What the WebView shows now. */
    private var shownUrl: String? = CARD_SYSTEM
    private var shownCertificate: X509Certificate? = pageLeaf

    /** What the pinned request meets, and what happens while it is out. */
    private var served: X509Certificate? = pageLeaf
    private var failure: Exception? = null
    private var checkTakesMs = 0L
    private var whileOut: () -> Unit = {}
    private val requests = mutableListOf<String>()

    private val scripts = mutableListOf<String>()

    private fun certificate() = HeldCertificate.Builder().addSubjectAlternativeName("cardsystem.ntust.edu.tw").build().certificate

    private fun newSignIn() = BrowserSiteSignIn(
        signedIn = { signedIn },
        studentId = { reads += "studentId"; storedId },
        password = { reads += "password"; storedPassword },
        usable = { usable },
        generation = { generation },
        pinnedLeaf = { url ->
            requests += url
            whileOut()
            delay(checkTakesMs)
            failure?.let { throw it }
            served
        },
    )

    private var signIn = newSignIn()

    private suspend fun fill(url: String = CARD_SYSTEM, session: Int? = 7) =
        signIn.fill(url, session, shownUrl = { shownUrl }, shownCertificate = { shownCertificate }) { scripts += it }

    /** The value [name] stands for in [script], read back as the page reads its literal. */
    private fun value(script: String, name: String): String? {
        val literal = Regex("""\b$name = ("(?:[^"\\]|\\.)*"|null)[,;]""").find(script)?.groupValues?.get(1)
        assertNotNull("$name in the script", literal)
        return Gson().fromJson(literal, String::class.java)
    }

    private fun assertStudentIdAlone() {
        assertEquals("one fill", 1, scripts.size)
        assertEquals(STUDENT_ID, value(scripts.single(), "studentId"))
        assertNull(value(scripts.single(), "password"))
        assertFalse("the password is not even read", "password" in reads)
    }

    @Test
    fun `the student id goes at once, and the password once the page's leaf is the one a pinned request met`() = runTest {
        fill()

        assertEquals(listOf(CARD_SYSTEM), requests)
        assertEquals(2, scripts.size)
        val (first, second) = scripts
        assertEquals("https://cardsystem.ntust.edu.tw", value(first, "origin"))
        assertEquals("/std/index.php/user/login", value(first, "path"))
        assertEquals("id", value(first, "studentIdField"))
        assertEquals(STUDENT_ID, value(first, "studentId"))
        assertEquals("password", value(first, "passwordField"))
        assertNull(value(first, "password"))
        assertEquals(STUDENT_ID, value(second, "studentId"))
        assertEquals("password", value(second, "passwordField"))
        assertEquals(PASSWORD, value(second, "password"))
    }

    @Test
    fun `the pinned request is a HEAD of the page itself, not its query`() = runTest {
        shownUrl = "$CARD_SYSTEM?lang=en#top"
        fill(url = "$CARD_SYSTEM?lang=en#top")

        assertEquals(listOf(CARD_SYSTEM), requests)
        assertEquals(PASSWORD, value(scripts.last(), "password"))
    }

    @Test
    fun `a leaf other than the page's gets the student id alone`() = runTest {
        served = otherLeaf
        fill()
        assertStudentIdAlone()
    }

    @Test
    fun `a pinned request that fails, meets no leaf or takes too long gets the student id alone`() = runTest {
        failure = IOException("pins not met")
        fill()
        assertStudentIdAlone()

        scripts.clear()
        failure = null
        served = null
        fill()
        assertStudentIdAlone()

        scripts.clear()
        served = pageLeaf
        checkTakesMs = 60_000
        val start = currentTime
        fill()
        assertStudentIdAlone()
        assertEquals(BrowserSiteSignIn.CHECK_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun `a page with no certificate gets the student id alone, and no request`() = runTest {
        shownCertificate = null
        fill()
        assertStudentIdAlone()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a page that moved on, a session that ended or a browser that closed while the check was out gets no password`() = runTest {
        val changes = listOf<() -> Unit>(
            { shownUrl = "https://cardsystem.ntust.edu.tw/std/index.php/user/agreement" },
            { shownUrl = null },
            { shownCertificate = otherLeaf },
            { shownCertificate = null },
            { generation = 8 },
            { signedIn = false },
            { usable = false },
        )
        changes.forEachIndexed { index, change ->
            signIn = newSignIn()
            shownUrl = CARD_SYSTEM
            shownCertificate = pageLeaf
            generation = 7
            signedIn = true
            usable = true
            scripts.clear()
            reads.clear()
            whileOut = change
            fill()
            assertEquals("change $index: the student id alone", 1, scripts.size)
            assertNull("change $index", value(scripts.single(), "password"))
            assertFalse("change $index", "password" in reads)
        }
    }

    @Test
    fun `a fill whose screen closes while the check is out gives no password`() = runTest {
        checkTakesMs = 1_000
        val job = launch { fill() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(1, scripts.size)
        assertNull(value(scripts.single(), "password"))
    }

    @Test
    fun `a leaf met before skips the request, and the password goes at once`() = runTest {
        fill()
        scripts.clear()
        fill()

        assertEquals("one request in all", 1, requests.size)
        assertEquals("one fill, with the password", 1, scripts.size)
        assertEquals(PASSWORD, value(scripts.single(), "password"))
    }

    @Test
    fun `a page leaf other than the one met before is checked again, and one that fails is forgotten`() = runTest {
        fill()

        shownCertificate = otherLeaf
        served = otherLeaf
        scripts.clear()
        fill()
        assertEquals("checked again", 2, requests.size)
        assertEquals(PASSWORD, value(scripts.last(), "password"))

        val third = certificate()
        shownCertificate = third
        served = pageLeaf
        scripts.clear()
        fill()
        assertEquals(3, requests.size)
        assertEquals(1, scripts.size)

        shownCertificate = otherLeaf
        served = otherLeaf
        fill()
        assertEquals("the leaf met before was forgotten", 4, requests.size)
    }

    @Test
    fun `a leaf met under an earlier browser session is checked again`() = runTest {
        fill()
        generation = 8
        fill(session = 8)
        assertEquals(2, requests.size)
    }

    @Test
    fun `the suggestion box gets the student id alone, with no request and no password field`() = runTest {
        shownUrl = "https://suggestionbox.ntust.edu.tw/login"
        fill(url = "https://suggestionbox.ntust.edu.tw/login")

        assertEquals(1, scripts.size)
        assertEquals("account", value(scripts.single(), "studentIdField"))
        assertNull(value(scripts.single(), "passwordField"))
        assertNull(value(scripts.single(), "password"))
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
        assertTrue(scripts.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the demo account and a signed-out app fill nothing`() = runTest {
        signedIn = false
        fill()
        assertTrue(scripts.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a page loaded under a browser session that has ended, or a browser that cannot be used, fills nothing`() = runTest {
        fill(session = 6)
        fill(session = null)
        usable = false
        fill()
        assertTrue(scripts.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with no stored student id nothing is filled`() = runTest {
        storedId = null
        fill()
        storedId = " "
        fill()
        assertTrue(scripts.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with no stored password the student id alone is filled`() = runTest {
        storedPassword = null
        fill()
        assertEquals(1, scripts.size)
        assertNull(value(scripts.single(), "password"))
    }

    @Test
    fun `every value is a JSON string literal that nothing in it can end`() = runTest {
        storedId = "B10900000\"; alert(1); //"
        storedPassword = EVIL
        fill()
        val script = scripts.last()

        assertEquals(EVIL, value(script, "password"))
        assertEquals(storedId, value(script, "studentId"))
        listOf("</script>", "\u2028", "\u2029", "\r", "'\"", "00\"; alert").forEach {
            assertFalse(it, script.contains(it))
        }
        assertTrue("ASCII alone", script.all { it.code in 0x20..0x7e || it == '\n' })
    }

    @Test
    fun `a literal reads back as its value, in ASCII alone, with no quote, angle bracket or line separator`() {
        listOf(EVIL, "", "B10900000", "\u0000\u001f\u007f\u00e9\ud83e\udd86", "\\u2028").forEach { text ->
            val literal = BrowserSiteSignIn.literal(text)
            assertEquals(text, Gson().fromJson(literal, String::class.java))
            assertTrue(literal, literal.startsWith("\"") && literal.endsWith("\""))
            val inside = literal.substring(1, literal.length - 1)
            assertTrue(literal, inside.all { it.code in 0x20..0x7e })
            assertFalse("$literal ends early", endsEarly(inside))
            listOf("'", "<", ">", "\n", "\r").forEach { assertFalse("$literal holds $it", inside.contains(it)) }
        }
        assertEquals("null", BrowserSiteSignIn.literal(null))
    }

    @Test
    fun `the script checks the page's address as it fills, and fills only empty fields of one form posting home`() = runTest {
        fill()
        val script = scripts.last()
        val check = script.indexOf("if (location.origin !== origin || location.pathname !== path) return false;")

        assertTrue("the address is checked", check >= 0)
        assertTrue("before anything is read or written", check < script.indexOf("document."))
        assertTrue(script.contains("element instanceof HTMLInputElement && element.form !== null"))
        assertTrue(script.contains("secret.type !== 'password' || secret.form !== id.form"))
        assertTrue(script.contains("if (new URL(id.form.action).origin !== origin) return false;"))
        assertTrue("the password only beside the stored id", script.contains("secret && password !== null && (!id.value || id.value === studentId)"))
        assertTrue("never over what the student typed", script.contains("if (element.value) continue;"))
        assertTrue(script.contains("element.dispatchEvent(new Event('input', { bubbles: true }));"))
        assertTrue(script.contains("element.dispatchEvent(new Event('change', { bubbles: true }));"))
    }

    @Test
    fun `the script submits nothing and touches no other field`() = runTest {
        fill()
        scripts.forEach { script ->
            listOf("submit", "click", "focus", "VerifyCode", "verify", "querySelector", "fetch", "XMLHttpRequest", "console", "cookie", "localStorage")
                .forEach { assertFalse(it, script.contains(it)) }
            assertEquals("one value write", 1, Regex("""\.value = """).findAll(script).count())
        }
    }

    @Test
    fun `the script is one expression whose failure reads as failed, with no value in it`() = runTest {
        fill()
        val script = scripts.last()

        assertTrue(script.startsWith("(function() {"))
        assertTrue(script.trimEnd().endsWith("})();"))
        assertTrue(script.contains("} catch (error) {\n    return 'failed';"))
        assertEquals("\"failed\"", BrowserSiteSignIn.FAILED)
    }

    /** Whether a quote no backslash escapes stands inside a literal. */
    private fun endsEarly(inside: String): Boolean {
        var i = 0
        while (i < inside.length) {
            when (inside[i]) {
                '\\' -> i += 2
                '"' -> return true
                else -> i++
            }
        }
        return false
    }

    private companion object {
        const val CARD_SYSTEM = "https://cardsystem.ntust.edu.tw/std/index.php/user/login"
        const val STUDENT_ID = "B10900000"
        const val PASSWORD = "fake-pass"
        const val EVIL = "fake-pass'\"\\</script>\u2028\u2029\n\r"
    }
}
