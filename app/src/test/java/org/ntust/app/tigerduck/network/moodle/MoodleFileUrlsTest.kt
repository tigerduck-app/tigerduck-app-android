package org.ntust.app.tigerduck.network.moodle

import okhttp3.FormBody
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MoodleFileUrlsTest {

    private val file = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1"

    private fun form(request: Request): List<Pair<String, String>> {
        val body = request.body as FormBody
        return (0 until body.size).map { body.name(it) to body.value(it) }
    }

    @Test
    fun `with the private access key the file goes under tokenpluginfile`() {
        val request = MoodleFileUrls.downloadRequest(file, accessKey = "abc", token = "tok")!!

        assertEquals("GET", request.method)
        assertEquals(
            "https://moodle2.ntust.edu.tw/tokenpluginfile.php/abc/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1",
            request.url.toString(),
        )
    }

    @Test
    fun `without the key the token goes in the form to the web-service path, never in the URL`() {
        val request = MoodleFileUrls.downloadRequest(file, accessKey = null, token = "tok")!!

        assertEquals("POST", request.method)
        assertEquals(
            "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1",
            request.url.toString(),
        )
        assertEquals(listOf("token" to "tok"), form(request))
    }

    @Test
    fun `a token already in the link is not sent along`() {
        val request = MoodleFileUrls.downloadRequest(
            "https://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf?token=old",
            accessKey = "",
            token = "tok",
        )!!

        assertEquals("https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/a.pdf", request.url.toString())
        assertFalse("old" in request.url.toString())
        assertEquals(listOf("token" to "tok"), form(request))
    }

    @Test
    fun `no key ever goes to another site or path`() {
        assertNull(MoodleFileUrls.downloadRequest("https://example.com/pluginfile.php/1/a.pdf", "abc", "tok"))
        assertNull(MoodleFileUrls.downloadRequest("http://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf", "abc", "tok"))
        assertNull(MoodleFileUrls.downloadRequest("https://moodle2.ntust.edu.tw/course/view.php?id=1", "abc", "tok"))
    }
}
