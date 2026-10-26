package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Moodle's answers are read off the thread that asked: HTML parsing a page of posts is not for the main thread. */
class LiveMoodleCourseApiTest {

    private class Recording : CoroutineDispatcher() {
        var used = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            used++
            block.run()
        }
    }

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val function = chain.request().url.queryParameter("wsfunction")
        val body = when (function) {
            "mod_forum_get_forums_by_courses" ->
                """[{"id":1,"course":5,"type":"news","name":"<b>News</b>","cmid":9,"numdiscussions":2}]"""
            else -> "{}"
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val tokens = object : MoodleWebservice.TokenSource {
        override fun stored() = "t1"
        override suspend fun renew(rejected: String?) = "t1"
    }

    @Test
    fun `an answer is read on the background dispatcher`() = runTest {
        val parse = Recording()
        val api = LiveMoodleCourseApi(MoodleWebservice({ client }, tokens), parse)

        val forums = api.forums(listOf(5))

        assertEquals("News", forums.single().name)
        assertTrue(parse.used > 0)
    }

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `a file Moodle lists as document unknown downloads, whatever type it is served as`() = runTest {
        val files = OkHttpClient.Builder().addInterceptor { chain ->
            val siteInfo = chain.request().url.queryParameter("wsfunction") == "core_webservice_get_site_info"
            val (body, type) = if (siteInfo) {
                """{"userid":7,"functions":[],"userprivateaccesskey":"key"}""" to "application/json"
            } else {
                "PK zip bytes" to "application/octet-stream"
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }.build()
        val api = LiveMoodleCourseApi(MoodleWebservice({ files }, tokens), Recording())
        val file = org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
            name = "slides.key", size = 12, mimeType = "document/unknown", modified = 1L, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/9/slides.key",
        )
        val target = tmp.newFile()

        api.download(file, target, maxBytes = 1_000) {}

        assertEquals("PK zip bytes", target.readText())
    }

    private class Recorded(val function: String?, val body: String)

    private fun recordingClient(
        calls: MutableList<Recorded>,
        file: Pair<String, String> = "bytes" to "application/octet-stream",
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val function = request.url.queryParameter("wsfunction")
        val sent = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        calls += Recorded(function, sent)
        val (body, type) = when (function) {
            "core_webservice_get_site_info" -> """{"userid":7,"functions":[],"userprivateaccesskey":"key"}""" to "application/json"
            "mod_forum_get_discussion_posts" -> """{"posts":[{"id":1,"subject":"S","message":"M"}]}""" to "application/json"
            "core_course_get_contents" -> "[]" to "application/json"
            null -> file
            else -> """{"status":true}""" to "application/json"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }.build()

    @Test
    fun `a thread is asked for oldest first, filtered as the Moodle app asks`() = runTest {
        val calls = mutableListOf<Recorded>()
        val api = LiveMoodleCourseApi(MoodleWebservice({ recordingClient(calls) }, tokens), Recording())

        assertEquals(1, api.posts(40).single().id)
        val body = calls.single { it.function == "mod_forum_get_discussion_posts" }.body
        assertEquals("wstoken=t1&discussionid=40&sortby=created&sortdirection=ASC&moodlewssettingfilter=true", body)
    }

    @Test
    fun `opening a material logs its module's own view with its instance, and an unknown module none`() = runTest {
        val calls = mutableListOf<Recorded>()
        val api = LiveMoodleCourseApi(MoodleWebservice({ recordingClient(calls) }, tokens), Recording())
        fun material(module: String) = org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial(
            id = 101, instance = 7, module = module, name = "M", files = emptyList(), link = null,
        )

        api.markMaterialViewed(material("resource")) { true }
        api.markMaterialViewed(material("folder")) { true }
        api.markMaterialViewed(material("url")) { true }
        api.markMaterialViewed(material("quiz")) { true }
        api.markMaterialViewed(material("resource")) { false }

        assertEquals(
            listOf(
                "mod_resource_view_resource" to "wstoken=t1&resourceid=7",
                "mod_folder_view_folder" to "wstoken=t1&folderid=7",
                "mod_url_view_url" to "wstoken=t1&urlid=7",
            ),
            calls.map { it.function to it.body },
        )
    }

    @Test
    fun `a file with no listed type refuses a page in its place, and reports its progress`() = runTest {
        val untyped = org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
            name = "notes.key", size = 5, mimeType = null, modified = 1L, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_resource/content/1/notes.key",
        )
        val page = LiveMoodleCourseApi(
            MoodleWebservice({ recordingClient(mutableListOf(), "<html>Sign in</html>" to "text/html") }, tokens),
            Recording(),
        )
        val refused = runCatching { page.download(untyped, tmp.newFile(), maxBytes = 1_000) {} }.exceptionOrNull()
        assertTrue("got $refused", refused is org.ntust.app.tigerduck.network.MoodleWebserviceError.MalformedResponse)

        val shares = mutableListOf<Float>()
        val file = LiveMoodleCourseApi(MoodleWebservice({ recordingClient(mutableListOf()) }, tokens), Recording())
        val target = tmp.newFile()
        file.download(untyped, target, maxBytes = 1_000) { shares += it }
        assertEquals("bytes", target.readText())
        assertEquals(1f, shares.last())

        // A web page by its own name is taken as one.
        val html = untyped.copy(name = "notes.html", url = untyped.url.replace("notes.key", "notes.html"))
        page.download(html, tmp.newFile(), maxBytes = 1_000) {}
    }
}
