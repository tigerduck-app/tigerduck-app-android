package org.ntust.app.tigerduck.browser

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.FAILED
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.Marked
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.fill
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.literal
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.mark
import org.ntust.app.tigerduck.browser.BrowserSiteSignInScripts.marked
import org.ntust.app.tigerduck.browser.sites.SitePage
import org.ntust.app.tigerduck.browser.sites.SiteSignInForm

/**
 * What the two scripts are made of, and how the first one's answer is read. Their work in a page
 * needs a JS engine the JVM has none of: these hold what text can show, the values above all.
 */
class BrowserSiteSignInScriptsTest {

    private val page = SitePage("cardsystem.ntust.edu.tw", "/std/index.php/user/login")
    private val form = SiteSignInForm(studentId = "id", password = "password")

    /** The value [name] stands for in [script], read back as the page reads its literal. */
    private fun value(script: String, name: String): String? {
        val literal = Regex("""\b$name = ("(?:[^"\\]|\\.)*"|null)[,;]""").find(script)?.groupValues?.get(1)
        assertNotNull("$name in the script", literal)
        return Gson().fromJson(literal, String::class.java)
    }

    @Test
    fun `the mark carries the page, the fields and the student id, and no password`() {
        val script = mark(page, form, STUDENT_ID)

        assertEquals("https://cardsystem.ntust.edu.tw", value(script, "origin"))
        assertEquals("/std/index.php/user/login", value(script, "path"))
        assertEquals("id", value(script, "studentIdField"))
        assertEquals(STUDENT_ID, value(script, "studentId"))
        assertEquals("password", value(script, "passwordField"))
        assertFalse(Regex("""\bpassword = """).containsMatchIn(script))
    }

    @Test
    fun `the fill carries the token beside the password`() {
        val script = fill(page, form, STUDENT_ID, PASSWORD, TOKEN)

        assertEquals(TOKEN, value(script, "token"))
        assertEquals(PASSWORD, value(script, "password"))
        assertEquals(STUDENT_ID, value(script, "studentId"))
        assertEquals("password", value(script, "passwordField"))
    }

    @Test
    fun `the mark makes a 128-bit token on a property of TigerDuck's own that no page script can change`() {
        val script = mark(page, form, STUDENT_ID)

        assertTrue(BrowserSiteSignInScripts.TOKEN_PROPERTY.startsWith("__tigerduck"))
        assertTrue(script.contains(literal(BrowserSiteSignInScripts.TOKEN_PROPERTY)))
        assertTrue(script.contains("crypto.getRandomValues(new Uint8Array(16))"))
        assertTrue(script.contains("value: made, writable: false, configurable: false, enumerable: false"))
    }

    @Test
    fun `the mark answers no token where the property is there already, and never reads one back`() {
        val script = mark(page, form, STUDENT_ID)

        assertTrue(script.contains("passwordField === null || Object.getOwnPropertyDescriptor(window, key) ? null :"))
        assertFalse("a value a page could have set is never answered", script.contains(".value;") || script.contains("kept"))
    }

    @Test
    fun `each script checks the page's address before anything else, and the fill its token before the form`() {
        listOf(mark(page, form, STUDENT_ID), fill(page, form, STUDENT_ID, PASSWORD, TOKEN)).forEach { script ->
            val check = script.indexOf("if (location.origin !== origin || location.pathname !== path) return")
            assertTrue("the address is checked", check >= 0)
            assertTrue("before anything is read or written", check < script.indexOf("document."))
        }
        val fill = fill(page, form, STUDENT_ID, PASSWORD, TOKEN)
        assertTrue("the token, before the form is read", fill.indexOf("kept.value !== token") < fill.indexOf("fields()"))
    }

    @Test
    fun `the scripts submit nothing, and write values only through one helper`() {
        listOf(mark(page, form, STUDENT_ID), fill(page, form, STUDENT_ID, PASSWORD, TOKEN)).forEach { script ->
            listOf("submit", "click", "focus", "VerifyCode", "verify", "querySelector", "fetch", "XMLHttpRequest", "console", "cookie", "localStorage")
                .forEach { assertFalse(it, script.contains(it)) }
            assertEquals("one value write", 1, Regex("""\.value = """).findAll(script).count())
            assertTrue(script.startsWith("(function() {"))
            assertTrue(script.trimEnd().endsWith("})();"))
            assertTrue(script.contains("} catch (error) {\n    return 'failed';"))
        }
        assertEquals("\"failed\"", FAILED)
    }

    @Test
    fun `the mark's answer is read for a well-made token and the address it was made at`() {
        assertEquals(Marked(TOKEN, "https://a.test/p"), marked("""{"token":"$TOKEN","href":"https://a.test/p"}"""))
        assertEquals(Marked(null, "https://a.test/p"), marked("""{"token":null,"href":"https://a.test/p"}"""))
        listOf("ABCDEF0123456789abcdef0123456789", "abc", "${TOKEN}0", "${TOKEN.drop(1)}g", " $TOKEN").forEach {
            assertNull(it, marked("""{"token":"$it","href":"https://a.test/p"}""")?.token)
        }
        listOf(null, "null", FAILED, "true", "[]", "{}", """{"token":"$TOKEN"}""", """{"token":"$TOKEN","href":7}""", "{").forEach {
            assertNull(it, marked(it))
        }
    }

    @Test
    fun `every value is a JSON string literal that nothing in it can end`() {
        val evilId = "B10900000\"; alert(1); //"
        val script = fill(page, form, evilId, EVIL, TOKEN)

        assertEquals(EVIL, value(script, "password"))
        assertEquals(evilId, value(script, "studentId"))
        listOf("</script>", "\u2028", "\u2029", "\r", "'\"", "00\"; alert").forEach { assertFalse(it, script.contains(it)) }
        assertTrue("ASCII alone", script.all { it.code in 0x20..0x7e || it == '\n' })
    }

    @Test
    fun `a literal reads back as its value, in ASCII alone, with no quote, angle bracket or line separator`() {
        listOf(EVIL, "", "B10900000", "\u0000\u001f\u007f\u00e9\ud83e\udd86", "\\u2028").forEach { text ->
            val literal = literal(text)
            assertEquals(text, Gson().fromJson(literal, String::class.java))
            assertTrue(literal, literal.startsWith("\"") && literal.endsWith("\""))
            val inside = literal.substring(1, literal.length - 1)
            assertTrue(literal, inside.all { it.code in 0x20..0x7e })
            assertFalse("$literal ends early", endsEarly(inside))
            listOf("'", "<", ">", "\n", "\r").forEach { assertFalse("$literal holds $it", inside.contains(it)) }
        }
        assertEquals("null", literal(null))
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
        const val STUDENT_ID = "B10900000"
        const val PASSWORD = "fake-pass"
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val EVIL = "fake-pass'\"\\</script>\u2028\u2029\n\r"
    }
}
