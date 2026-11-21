package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val LEAF = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun opened(url: String) = CertificateRefusal(url, LEAF).pageToOpen

/** What opening a refused page at the student's risk loads (tigerduck-app#958). */
class CertificateRefusalTest {

    @Test
    fun `a host the app signs in to opens only at its front page`() {
        assertEquals("https://moodle2.ntust.edu.tw/", opened("https://moodle2.ntust.edu.tw/course/view.php?id=7#section-2"))
        assertEquals("https://ssoam2.ntust.edu.tw/", opened("https://ssoam2.ntust.edu.tw/connect/authorize?client_id=x&code_challenge=y"))
        assertEquals("https://stuinfosys.ntust.edu.tw/", opened("https://user:pw@STUINFOSYS.ntust.edu.tw.:443/Authenticate/Account/EnrollStudent/ethicscourse"))
        assertEquals("https://sa.ntust.edu.tw/", opened("https://sa.ntust.edu.tw/Dorm/;jsessionid=abc?x=1"))
    }

    @Test
    fun `any other host keeps its page, without the fragment and the path parameters`() {
        assertEquals("https://www.ntust.edu.tw/p/news.php?page=2", opened("https://www.ntust.edu.tw/p/news.php?page=2#top"))
        assertEquals("https://suggestionbox.ntust.edu.tw/a/b/c", opened("https://suggestionbox.ntust.edu.tw/a;jsessionid=1/b;x=y;z/c"))
        assertEquals("https://example.com/", opened("https://example.com"))
        assertEquals("https://example.com/dir/", opened("https://example.com/dir/"))
    }

    @Test
    fun `its port stays, and a user name and password go`() {
        assertEquals("https://example.com:8443/a", opened("https://user:secret@example.com:8443/a"))
    }

    @Test
    fun `a query item whose name holds no credential stays, as it was written`() {
        assertEquals("https://example.com/a?page=2&lang=zh%2DTW&q=a+b&flag", opened("https://example.com/a?page=2&lang=zh%2DTW&q=a+b&flag"))
    }

    @Test
    fun `a query left empty goes`() {
        assertEquals("https://example.com/a", opened("https://example.com/a?token=x&code=y"))
        assertEquals("https://example.com/a", opened("https://example.com/a?"))
    }

    @Test
    fun `a name is read decoded and in any case`() {
        assertEquals("https://example.com/a?id=1", opened("https://example.com/a?id=1&%74oken=x&Access%5FToken=y&RelayState=z&SAMLResponse=w"))
        assertEquals("https://example.com/a?id=1", opened("https://example.com/a?id=1&id_token_hint=x&X-Api-Key=y&PHPSESSID=z"))
    }

    @Test
    fun `an address that does not read opens nothing`() {
        assertNull(opened("not a url"))
        assertNull(CertificateRefusal("mailto:a@example.com", LEAF).host)
    }

    @Test
    fun `its host is named as the pin matcher names it`() {
        assertEquals("moodle2.ntust.edu.tw", CertificateRefusal("https://MOODLE2.ntust.edu.tw./x", LEAF).host)
    }

    @Test
    fun `a leaf is named by the SHA-256 of its DER, in lower-case hex`() {
        // SHA-256 of the empty input.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CertificateRefusal.leafOf(ByteArray(0)))
    }
}

/** Each name the issue lists drops the query item that holds it (tigerduck-app#958). */
@RunWith(Parameterized::class)
class CertificateRefusalCredentialNameTest(private val word: String) {

    @Test
    fun `a query item whose name holds it goes, in any case and inside a longer name`() {
        val upper = word.uppercase()
        val url = "https://example.com/a?keep=1&$word=x&my_${upper}_value=y&${word}s=z&last=2"
        assertEquals("https://example.com/a?keep=1&last=2", opened(url))
    }

    @Test
    fun `its value alone is no reason to drop an item`() {
        assertEquals("https://example.com/a?q=$word", opened("https://example.com/a?q=$word"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun words(): List<String> = listOf(
            "token", "code", "ticket", "key", "saml", "assertion", "secret", "pass", "session", "sid", "hint", "jwt",
            "auth", "verifier", "state", "nonce", "sig", "credential",
        ).also { assertEquals("the issue's list, and only it", it, CertificateRefusal.CREDENTIAL_WORDS) }
    }
}
