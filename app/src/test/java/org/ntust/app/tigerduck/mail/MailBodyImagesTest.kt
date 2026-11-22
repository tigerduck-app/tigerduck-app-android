package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.store.MailCache
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tapped image of a mail's body, as on the iPhone (tigerduck-app#1047): from a `data:` address,
 * or from the web only once remote images are let in; and only a picture its own bytes say it is.
 */
class MailBodyImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var cache: MailCache

    /** What the fake server answers a fetch with; every request it was asked, by URL. */
    private var answer: (String) -> Answer = { Answer(404, ByteArray(0), null) }
    private val asked = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            synchronized(asked) { asked += url }
            val reply = answer(url)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(reply.status)
                .message("answer")
                .body(reply.body)
                .build()
        }
        .build()

    private val images by lazy { MailBodyImages(client, cache, Dispatchers.IO) }

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("mail"))
    }

    private fun open(source: String, remoteAllowed: Boolean = false, stillCurrent: () -> Boolean = { true }) =
        runBlocking { images.open(source, remoteAllowed, stillCurrent) }

    @Test
    fun `a data image is decoded in the app, named for the kind its bytes are`() {
        val opened = open(dataAddress(PNG))!!
        assertEquals("image/png", opened.mimeType)
        assertEquals("image.png", opened.file.name)
        assertArrayEquals(PNG, opened.file.readBytes())
        assertTrue("written where the FileProvider serves an attachment", opened.file.parentFile!!.parentFile == cache.attachmentsDir)
    }

    @Test
    fun `a PNG sent as a JPEG opens as the PNG it is`() {
        val opened = open(dataAddress(PNG, type = "image/jpeg"))!!
        assertEquals("image/png", opened.mimeType)
        assertEquals("image.png", opened.file.name)
    }

    @Test
    fun `markup sent as a picture never opens`() {
        assertNull(open(dataAddress(SVG)))
        assertNull(open(dataAddress(HTML)))
        // The sanitizer keeps no SVG data address either.
        assertNull(open(dataAddress(SVG, type = "image/svg+xml")))
        assertNoFiles()
    }

    @Test
    fun `a remote image is fetched only once the reader has let them in`() {
        answer = { Answer(200, PNG, "image/png") }
        assertNull(open("https://tracker.example/p.png"))
        assertNull(open("http://tracker.example/p.png"))
        assertEquals(emptyList<String>(), asked)

        val opened = open("https://tracker.example/%E8%AA%B2%E8%A1%A8.jpg", remoteAllowed = true)!!
        assertEquals("課表.png", opened.file.name)
        assertEquals(listOf("https://tracker.example/%E8%AA%B2%E8%A1%A8.jpg"), asked)
    }

    @Test
    fun `only a web address is fetched, remote images or not`() {
        answer = { Answer(200, PNG, "image/png") }
        assertNull(open("file:///etc/hosts", remoteAllowed = true))
        assertNull(open("javascript:alert(1)", remoteAllowed = true))
        assertNull(open("content://x/y.png", remoteAllowed = true))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a remote image is read up to the inline image limit and no further`() {
        val fits = PNG + ByteArray(MailLimits.INLINE_IMAGE_BYTES.toInt() - PNG.size)
        answer = { Answer(200, fits, "image/png", length = -1) }
        assertEquals(fits.size.toLong(), open("https://a.example/p.png", remoteAllowed = true)!!.file.length())

        answer = { Answer(200, fits + 0, "image/png", length = -1) }
        assertNull(open("https://a.example/q.png", remoteAllowed = true))
    }

    @Test
    fun `the body is not read after an error, another type or a length past the limit`() {
        val tooLong = MailLimits.INLINE_IMAGE_BYTES + 1
        listOf(
            Answer(404, PNG, "image/png"),
            Answer(200, PNG, "text/html"),
            Answer(200, PNG, "image/svg+xml"),
            Answer(200, PNG, null),
            Answer(200, PNG, "image/png", length = tooLong),
        ).forEach { reply ->
            answer = { reply }
            assertNull(open("https://a.example/p.png", remoteAllowed = true))
            assertFalse("read the body of $reply", reply.body.read)
        }
    }

    @Test
    fun `markup fetched as a picture never opens`() {
        answer = { Answer(200, SVG, "image/png") }
        assertNull(open("https://a.example/p.png", remoteAllowed = true))
        assertNoFiles()
    }

    @Test
    fun `nothing is written once the sign-in it was tapped under has gone`() {
        assertNull(open(dataAddress(PNG), stillCurrent = { false }))
        assertNoFiles()
    }

    /**
     * A sign-out's clear that lands just after the check said the sign-in stands waits for the
     * write it allowed, and then removes it: the departing student's image is never left behind.
     */
    @Test
    fun `a sign-out racing the write still leaves nothing`() {
        val cleared = CountDownLatch(1)
        var clearing: Thread? = null

        open(dataAddress(PNG), stillCurrent = {
            clearing = thread { cache.clearAll(); cleared.countDown() }
            // Given the time to finish first if it could; held behind the write, it cannot.
            cleared.await(300, TimeUnit.MILLISECONDS)
            true
        })
        clearing!!.join(5_000)

        assertNoFiles()
    }

    @Test
    fun `a data address is decoded as a browser reads one`() {
        val encoded = Base64.getEncoder().encodeToString(PNG)
        assertArrayEquals(PNG, open("data:image/png;base64,${encoded.chunked(8).joinToString("\n")}")!!.file.readBytes())
        assertArrayEquals(PNG, open("data:image/png;base64,${encoded.replace("+", "%2B").replace("/", "%2F")}")!!.file.readBytes())
        assertNull(open("data:image/png,${String(PNG, Charsets.ISO_8859_1)}"))
    }

    private fun assertNoFiles() {
        assertTrue(cache.attachmentsDir.walkTopDown().none { it.isFile })
    }

    /** A fetch's answer, whose body says whether it was read. */
    private class Answer(val status: Int, bytes: ByteArray, type: String?, length: Long = bytes.size.toLong()) {
        val body = WatchedBody(bytes, type?.toMediaType(), length)

        override fun toString() = "$status ${body.contentType()} ${body.contentLength()}"
    }

    private class WatchedBody(bytes: ByteArray, private val type: MediaType?, private val length: Long) : ResponseBody() {
        @Volatile var read = false
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                read = true
                return super.read(sink, byteCount)
            }
        }.buffer()

        override fun contentType() = type
        override fun contentLength() = length
        override fun source(): BufferedSource = source
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
        val SVG = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()
        val HTML = "<html><body><script>alert(1)</script></body></html>".toByteArray()

        fun dataAddress(bytes: ByteArray, type: String = "image/png") = "data:$type;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }
}
