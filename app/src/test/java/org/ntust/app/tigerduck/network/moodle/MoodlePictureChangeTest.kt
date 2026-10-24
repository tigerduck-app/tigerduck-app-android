package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError

/**
 * Changing the account's profile picture (#446, as the iPhone's tigerduck-app#802): the picture
 * goes up to a draft area through `webservice/upload.php`, then `core_user_update_picture` makes
 * it the profile picture. A refusal of either step, or an answer that nothing changed, is an error.
 */
class MoodlePictureChangeTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private val requests = mutableListOf<Request>()

    /** What upload.php answers; a draft area with item 4242 unless a test sets another. */
    private var uploadAnswer = """[{"component":"user","filearea":"draft","itemid":4242,"filepath":"/","filename":"picture.jpg"}]"""

    /** What core_user_update_picture answers. */
    private var updateAnswer = """{"success":true,"profileimageurl":"https://moodle2.ntust.edu.tw/pluginfile.php/1/user/icon/f1?rev=9","warnings":[]}"""

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val body = if (request.url.encodedPath == "/webservice/upload.php") uploadAnswer else updateAnswer
        Response.Builder()
            .request(request)
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

    private val api = LiveMoodleCourseApi(MoodleWebservice({ client }, tokens), Dispatchers.Unconfined)

    /** A multipart request's text fields, by name. */
    private fun fields(body: MultipartBody): Map<String, String> = body.parts.mapNotNull { part ->
        val name = part.headers?.get("Content-Disposition")?.let { Regex("name=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        if (name == null || part.body.contentType() != null) return@mapNotNull null
        name to Buffer().also { part.body.writeTo(it) }.readUtf8()
    }.toMap()

    @Test
    fun `the picture goes up as a draft and then becomes the profile picture`() = runTest {
        api.changePicture(jpeg)

        assertEquals(2, requests.size)
        val upload = requests[0]
        assertEquals("https://moodle2.ntust.edu.tw/webservice/upload.php", upload.url.toString())
        val form = upload.body as MultipartBody
        assertEquals(mapOf("token" to "t1", "filearea" to "draft", "itemid" to "0"), fields(form))
        val file = form.parts.single { it.body.contentType() != null }
        assertEquals("image/jpeg".toMediaType(), file.body.contentType())
        assertArrayEquals(jpeg, Buffer().also { file.body.writeTo(it) }.readByteArray())

        val update = requests[1]
        assertEquals(MoodleCourseApi.UPDATE_PICTURE_FUNCTION, update.url.queryParameter("wsfunction"))
        val params = (update.body as FormBody).let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }
        assertEquals(mapOf("draftitemid" to "4242", "delete" to "0"), params)
    }

    @Test
    fun `an answer that the picture did not change is an error`() {
        updateAnswer = """{"success":false,"warnings":[{"warningcode":"x","message":"no"}]}"""

        assertThrows(MoodleWebserviceError::class.java) { kotlinx.coroutines.runBlocking { api.changePicture(jpeg) } }
    }

    @Test
    fun `an upload Moodle refuses is its error, and the picture is not set`() {
        uploadAnswer = """{"error":"File upload failed","errorcode":"upload_error_ini_size"}"""

        assertThrows(MoodleWebserviceError::class.java) { kotlinx.coroutines.runBlocking { api.changePicture(jpeg) } }
        assertEquals(1, requests.size)
    }

    @Test
    fun `an upload answer without a draft area is an error`() {
        uploadAnswer = "[]"

        assertThrows(MoodleWebserviceError::class.java) { kotlinx.coroutines.runBlocking { api.changePicture(jpeg) } }
        assertEquals(1, requests.size)
    }
}
