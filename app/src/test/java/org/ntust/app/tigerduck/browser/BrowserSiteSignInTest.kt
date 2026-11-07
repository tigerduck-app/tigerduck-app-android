package org.ntust.app.tigerduck.browser

import com.google.gson.Gson
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * Which pages get the stored account, and how it goes into the script that fills their form. The
 * script's work in a page needs a WebView, which the JVM has none of; what it is made of is read
 * here.
 */
class BrowserSiteSignInTest {

    private var signedIn = true
    private var usable = true
    private var generation = 7
    private var storedId: String? = STUDENT_ID
    private var storedPassword: String? = PASSWORD
    private var pinsMet = true
    private val reads = mutableListOf<String>()
    private val pinChecks = mutableListOf<Pair<String, List<X509Certificate>>>()
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("cardsystem.ntust.edu.tw").build().certificate

    private val signIn = BrowserSiteSignIn(
        signedIn = { signedIn },
        studentId = { reads += "studentId"; storedId },
        password = { reads += "password"; storedPassword },
        usable = { usable },
        generation = { generation },
        meetsPins = { host, served -> pinChecks += host to served; pinsMet },
    )

    private fun script(url: String = CARD_SYSTEM, certificate: X509Certificate? = this.certificate, session: Int? = 7) =
        signIn.script(url, certificate, session)

    private fun filled(url: String = CARD_SYSTEM, certificate: X509Certificate? = this.certificate) =
        checkNotNull(script(url, certificate)) { "nothing filled on $url" }

    /** The value [name] stands for in [script], read back as the page reads its literal. */
    private fun value(script: String, name: String): String? {
        val literal = Regex("""\b$name = ("(?:[^"\\]|\\.)*"|null)[,;]""").find(script)?.groupValues?.get(1)
        assertNotNull("$name in the script", literal)
        return Gson().fromJson(literal, String::class.java)
    }

    @Test
    fun `a listed page gets the stored student id and password, beside its origin, path and fields`() {
        val script = filled()

        assertEquals("https://cardsystem.ntust.edu.tw", value(script, "origin"))
        assertEquals("/std/index.php/user/login", value(script, "path"))
        assertEquals("id", value(script, "studentIdField"))
        assertEquals(STUDENT_ID, value(script, "studentId"))
        assertEquals("password", value(script, "passwordField"))
        assertEquals(PASSWORD, value(script, "password"))
        assertEquals(listOf("cardsystem.ntust.edu.tw" to listOf(certificate)), pinChecks)
    }

    @Test
    fun `an unlisted page gets nothing, and the account is not read for it`() {
        listOf(
            "https://cardsystem.ntust.edu.tw/std/index.php/user/agreement",
            "http://cardsystem.ntust.edu.tw/std/index.php/user/login",
            "https://cardsystem.ntust.edu.tw:8443/std/index.php/user/login",
            "https://ssoam2.ntust.edu.tw/account/login",
            "https://example.com/login",
        ).forEach { assertNull(it, script(url = it)) }
        assertTrue(reads.isEmpty())
        assertTrue(pinChecks.isEmpty())
    }

    @Test
    fun `the demo account and a signed-out app fill nothing`() {
        signedIn = false
        assertNull(script())
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `a page loaded under a sign-in that has ended, or a browser that cannot be used, fills nothing`() {
        assertNull("another sign-in since", script(session = 6))
        assertNull("no session loaded", script(session = null))
        usable = false
        assertNull("a clearing owed", script())
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `with no stored student id nothing is filled`() {
        storedId = null
        assertNull(script())
        storedId = " "
        assertNull(script())
        assertFalse(reads.contains("password"))
    }

    @Test
    fun `a page whose certificate does not meet the pins gets the student id alone`() {
        pinsMet = false
        val script = filled()

        assertEquals(STUDENT_ID, value(script, "studentId"))
        assertEquals("password", value(script, "passwordField"))
        assertNull(value(script, "password"))
        assertFalse("the password is not even read", reads.contains("password"))
        assertFalse(script.contains("fake-pass"))
    }

    @Test
    fun `a page with no certificate gets the student id alone, and no pin check is made`() {
        val script = filled(certificate = null)

        assertNull(value(script, "password"))
        assertTrue(pinChecks.isEmpty())
        assertFalse(reads.contains("password"))
    }

    @Test
    fun `the suggestion box gets the student id alone, with no pin check and no password field`() {
        val script = filled(url = "https://suggestionbox.ntust.edu.tw/login")

        assertEquals("account", value(script, "studentIdField"))
        assertNull(value(script, "passwordField"))
        assertNull(value(script, "password"))
        assertTrue(pinChecks.isEmpty())
        assertFalse(reads.contains("password"))
    }

    @Test
    fun `with no stored password the student id alone is filled`() {
        storedPassword = null
        assertNull(value(filled(), "password"))
    }

    @Test
    fun `every value is a JSON string literal that nothing in it can end`() {
        storedId = "B10900000\"; alert(1); //"
        storedPassword = EVIL
        val script = filled()

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
    fun `the script checks the page's address as it fills, and fills only empty fields of one form posting home`() {
        val script = filled()
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
    fun `the script submits nothing and touches no other field`() {
        val script = filled()

        listOf("submit", "click", "focus", "VerifyCode", "verify", "querySelector", "fetch", "XMLHttpRequest", "console", "cookie", "localStorage")
            .forEach { assertFalse(it, script.contains(it)) }
        assertEquals("one value write", 1, Regex("""\.value = """).findAll(script).count())
    }

    @Test
    fun `the script is one expression whose failure reads as failed, with no value in it`() {
        val script = filled()

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
