package org.ntust.app.tigerduck.network.backend

import okhttp3.Response
import org.json.JSONObject
import org.ntust.app.tigerduck.push.PushApiException
import java.io.IOException

/**
 * Why the backend refused a request: its status, and the code its body names.
 *
 * `/v4` answers every error in one envelope, `{"error": "course_not_found", "message": …}`, and its
 * clients branch on `error` alone (`docs/api-v4.md` on tigerduck-backend, "Error envelope"). `/v3`
 * says a route's code in `detail`, `{"detail": "course_not_found"}`, and elsewhere prose, a list of
 * validation errors or plain text; its change feed and settings conflict already carry `error`.
 * So the code is `error`, else `detail`, each only when it is one word of `[a-z_]`, else null.
 * Prose is never a code, so a proxy's page or a sentence says nothing a reader can branch on,
 * whichever version answered.
 */
data class BackendError(val status: Int, val code: String?) {

    /**
     * The change feed no longer keeps the changes after the revision it was asked for, and only
     * a full sync catches up: a 409, as `/v4` keeps every 410 for "update".
     */
    val isRevisionExpired: Boolean get() = status == 409 && code == SYNC_REVISION_EXPIRED

    /**
     * The backend retires this app, as iOS's `APIVersionGate.retires`: a 410 that says the API or
     * the app version is retired, or one that names no code at all, such as prose or a page cut
     * short. Never a code this build does not know.
     */
    val retiresApp: Boolean get() = status == 410 && (code == null || code in RETIRING)

    /**
     * The backend refused the session the request went with. Every 401 is: `/v4`'s
     * `session_revoked` and `session_user_mismatch` ask for a sign-in, and its
     * `invalid_access_token` for a refresh, and [org.ntust.app.tigerduck.auth.AuthTokenManager.signInAgain]
     * tells which by the token the backend refused.
     */
    val refusesSession: Boolean get() = status == 401

    companion object {
        const val SYNC_REVISION_EXPIRED = "sync_revision_expired"

        private val RETIRING = setOf("api_retired", "app_version_retired")
        private val WORD = Regex("[a-z_]+")

        // An error's body is a few short fields; a longer one names no code anyone branches on.
        private const val PEEKED_BYTES = 4_096L

        /** [response]'s status and code. Its body is peeked, so the caller can still read it. */
        fun of(response: Response): BackendError =
            BackendError(response.code, codeIn(runCatching { response.peekBody(PEEKED_BYTES).string() }.getOrNull()))

        /** The code [body] names, by the rule above; null for a body that is no JSON object. */
        internal fun codeIn(body: String?): String? {
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            return word(json, "error") ?: word(json, "detail")
        }

        private fun word(json: JSONObject, key: String): String? = (json.opt(key) as? String)?.takeIf(WORD::matches)
    }
}

/** A refusal from the backend, read by [backendError] rather than from its message. */
class BackendException(val error: BackendError, message: String) : IOException(message)

/**
 * The backend's refusal this failure carries: a [BackendException]'s, or a [PushApiException]'s
 * status, whose code it does not keep. Null for any other failure, whatever its message says.
 */
fun Throwable.backendError(): BackendError? = when (this) {
    is BackendException -> error
    is PushApiException -> statusCode?.let { BackendError(it, null) }
    else -> null
}
