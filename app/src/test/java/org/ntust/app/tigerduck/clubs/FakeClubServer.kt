package org.ntust.app.tigerduck.clubs

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The club API, faked at the client's edge: each request is recorded and answered from the queue
 * of its path, so nothing reaches clubs.ntust.edu.tw. [Answer.landedAt] stands for a redirect that
 * ended on another URL.
 */
class FakeClubServer {
    data class Answer(
        val code: Int = 200,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val landedAt: String? = null,
        val failure: IOException? = null,
    )

    val requests = CopyOnWriteArrayList<Request>()
    private val answers = HashMap<String, ArrayDeque<Answer>>()

    fun enqueue(path: String, answer: Answer) = synchronized(answers) { answers.getOrPut(path) { ArrayDeque() }.addLast(answer) }

    fun requests(path: String): List<Request> = requests.filter { it.url.encodedPath.endsWith("/$path") }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> answer(chain.request()) }).build()

    val api = ClubApiClient(client, ClubApiClient.BASE_URL.toHttpUrl())

    private fun answer(request: Request): Response {
        requests += request
        val path = request.url.pathSegments.last()
        val answer = synchronized(answers) { answers[path]?.removeFirstOrNull() } ?: throw IOException("nothing queued for $path")
        answer.failure?.let { throw it }
        val landed = answer.landedAt?.let { request.newBuilder().url(it.toHttpUrl()).build() } ?: request
        return Response.Builder()
            .request(landed)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message("")
            .apply { answer.headers.forEach { (name, value) -> header(name, value) } }
            .body(answer.body.toResponseBody())
            .build()
    }

    companion object {
        fun envelope(rows: String) = """{"success": true, "data": [$rows], "error": null, "meta": null}"""

        fun club(id: Int, name: String, en: String? = null, attribute: String? = "學藝性") =
            """{"id": $id, "name": "$name", "en_name": ${en?.let { "\"$it\"" }}, "attribute": ${attribute?.let { "\"$it\"" }}, "tagline": null}"""

        fun activity(id: Int, club: Int, day: String, from: String, to: String, name: String = "Practice", type: String = "活動") =
            """{"id": $id, "name": "$name", "club_id": $club, "club_name": "測試社團$club", "date": "$day", "end_date": "$day",
                "start_time": "$from:00", "end_time": "$to:00", "location": "TR-000", "type": "$type", "content": ""}"""
    }
}
