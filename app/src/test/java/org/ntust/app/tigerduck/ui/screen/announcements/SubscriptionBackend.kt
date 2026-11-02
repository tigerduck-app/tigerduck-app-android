package org.ntust.app.tigerduck.ui.screen.announcements

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The backend's bulletin subscription routes, answered in place of it by the first interceptor of
 * the client: each student's rules on this device, told apart by the token a request carries, kept
 * as `server/routes/bulletins_v3.py` on tigerduck-backend `main` keeps them. On `/v3` the per-rule
 * POST, PATCH and DELETE; on `/v4` the list's GET and its PUT, which keeps a rule by its
 * `client_id` (`docs/api-v4.md`, "Subscriptions"), and no per-rule route.
 *
 * Lenient where these tests need no refusal: the real `/v3` PATCH also asks for a `base_revision`,
 * which the app has never sent. A test refuses a request itself with [refuseNext] or a [Held]
 * one's release.
 */
class SubscriptionBackend {
    class Sent(val method: String, val path: String, val authorization: String?, val body: String?) {
        val json: JSONObject get() = JSONObject(requireNotNull(body) { "$this has no body" })

        override fun toString() = "$method $path"
    }

    /** A request kept waiting at the backend until [release]; [arrived] once it is there. */
    class Held {
        val arrived = CountDownLatch(1)
        private val go = CountDownLatch(1)

        @Volatile
        var refusal: Pair<Int, String>? = null
            private set

        /** Lets the request through: answered as the backend would, or refused with [refusal]. */
        fun release(refusal: Pair<Int, String>? = null) {
            this.refusal = refusal
            go.countDown()
        }

        fun awaitArrival() = check(arrived.await(10, TimeUnit.SECONDS)) { "the held request never came" }

        internal fun awaitRelease() = check(go.await(10, TimeUnit.SECONDS)) { "the held request was never released" }
    }

    private class Row(val id: Int, val clientId: String, var rule: JSONObject, var revision: Int)

    // By access token; guarded by this.
    private val rows = HashMap<String, MutableList<Row>>()
    private var nextId = 1
    private var nextClientId = 1
    private val holds = ConcurrentHashMap<String, ConcurrentLinkedQueue<Held>>()
    private val refusals = ConcurrentHashMap<String, ConcurrentLinkedQueue<Pair<Int, String>>>()

    val sent = CopyOnWriteArrayList<Sent>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

    /** Gives [token]'s student on this device a rule for each of [names], enabled, in order. */
    fun seed(token: String, vararg names: String) = synchronized(this) {
        for (name in names) {
            val rule = JSONObject().put("name", name).put("orgs", JSONArray()).put("tags", JSONArray(listOf("scholarship")))
                .put("mode", "AND").put("enabled", true)
            rows.getOrPut(token) { mutableListOf() } += Row(nextId++, "00000000-0000-4000-8000-%012d".format(nextClientId++), rule, 1)
        }
    }

    /** The next request to [route] (`PUT /v4/bulletin-subscriptions`) waits until the test releases it. */
    fun hold(route: String): Held = Held().also { holds.getOrPut(route) { ConcurrentLinkedQueue() }.add(it) }

    /** The next request to [route] is refused with [code] and [body], the backend unchanged. */
    fun refuseNext(route: String, code: Int, body: String) {
        refusals.getOrPut(route) { ConcurrentLinkedQueue() }.add(code to body)
    }

    /** Every request but the taxonomy's, in order: `GET /v3/bulletin-subscriptions`. */
    fun routes(): List<String> = sent.map { it.toString() }.filterNot { it.endsWith("/bulletins/taxonomy") }

    fun sent(route: String): List<Sent> = sent.filter { it.toString() == route }

    /** [token]'s rules as the backend holds them: name and whether each is enabled. */
    fun held(token: String): List<Pair<String?, Boolean>> = synchronized(this) {
        rows[token].orEmpty().map { it.rule.optString("name").ifEmpty { null } to it.rule.getBoolean("enabled") }
    }

    /** The `client_id` of each of [token]'s rules, in order. */
    fun clientIds(token: String): List<String> = synchronized(this) { rows[token].orEmpty().map { it.clientId } }

    private fun answer(request: Request): Response {
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        val path = request.url.encodedPath
        val route = "${request.method} $path"
        sent += Sent(request.method, path, request.header("Authorization"), body)
        val held = holds[route]?.poll()
        if (held != null) {
            held.arrived.countDown()
            held.awaitRelease()
        }
        val (code, reply) = held?.refusal ?: refusals[route]?.poll() ?: route(request, path, body)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun route(request: Request, path: String, body: String?): Pair<Int, String> = synchronized(this) {
        if (path.endsWith("/bulletins/taxonomy")) return TAXONOMY
        val token = request.header("Authorization")?.removePrefix("Bearer ") ?: return 401 to """{"detail":"missing_bearer_token"}"""
        val mine = rows.getOrPut(token) { mutableListOf() }
        val v4 = path.startsWith("/v4/")
        val id = path.substringAfter("/bulletin-subscriptions/", "").toIntOrNull()
        when {
            request.method == "GET" -> 200 to JSONObject().put("items", JSONArray(mine.map { it.json(v4) })).toString()
            v4 && request.method == "PUT" && id == null -> putList(mine, JSONObject(requireNotNull(body)))
            v4 -> 405 to """{"error":"method_not_allowed","message":"Method Not Allowed"}"""
            request.method == "POST" -> {
                val row = Row(nextId++, "00000000-0000-4000-8000-%012d".format(nextClientId++), JSONObject(requireNotNull(body)), 1)
                mine += row
                200 to row.json(v4 = false).toString()
            }
            request.method == "PATCH" -> {
                val row = mine.firstOrNull { it.id == id } ?: return NOT_FOUND
                val patch = JSONObject(requireNotNull(body))
                patch.keys().forEach { row.rule.put(it, patch.get(it)) }
                row.revision++
                200 to row.json(v4 = false).toString()
            }
            request.method == "DELETE" -> {
                if (!mine.removeIf { it.id == id }) return NOT_FOUND
                204 to ""
            }
            else -> 405 to """{"detail":"Method Not Allowed"}"""
        }
    }

    // As `replace_subscriptions_v4`: a rule whose client_id is live is updated in place, keeping its
    // id, a new one is inserted, the rest go; the rules come back in the order sent.
    private fun putList(mine: MutableList<Row>, body: JSONObject): Pair<Int, String> {
        val rules = body.getJSONArray("rules").let { array -> (0 until array.length()).map(array::getJSONObject) }
        val clientIds = rules.map { it.optString("client_id") }
        if (clientIds.any(String::isEmpty) || clientIds.toSet().size < clientIds.size) {
            return 422 to """{"error":"invalid_request","message":"The request did not validate.","fields":[]}"""
        }
        val live = mine.associateBy { it.clientId }
        val next = rules.map { rule ->
            val values = JSONObject(rule.toString()).apply { remove("client_id") }
            val row = live[rule.getString("client_id")]
            when {
                row == null -> Row(nextId++, rule.getString("client_id"), values, 1)
                row.rule.toString() == values.toString() -> row
                else -> row.also {
                    it.rule = values
                    it.revision++
                }
            }
        }
        mine.clear()
        mine += next
        return 200 to JSONObject().put("items", JSONArray(next.map { it.json(v4 = true) })).toString()
    }

    private fun Row.json(v4: Boolean) = JSONObject(rule.toString())
        .put("id", id)
        .put("revision", revision)
        .put("deleted_at", JSONObject.NULL)
        .apply { if (v4) put("client_id", clientId) }

    private companion object {
        val TAXONOMY = 200 to
            """{"orgs":[{"id":"office","label":"Office"}],"tags":[{"id":"scholarship","label":"Scholarship"}],"default_tags":["scholarship"]}"""
        val NOT_FOUND = 404 to """{"detail":"subscription not found"}"""
    }
}
