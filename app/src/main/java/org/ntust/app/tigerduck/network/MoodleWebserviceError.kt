package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException

sealed class MoodleWebserviceError(message: String) : Exception(message) {
    class InvalidToken : MoodleWebserviceError("Moodle token invalid")

    /**
     * Moodle's `accessexception`: the token may not call this function. Moodle answers an expired
     * token this way once, deleting it, and `invalidtoken` after; a function the site does not
     * allow answers this way every time. Kept apart from [InvalidToken] so the second case never
     * starts a renewal, which is a full SSO sign-in.
     */
    class AccessDenied : MoodleWebserviceError("Moodle access exception")
    class InvalidCredentials : MoodleWebserviceError("Moodle credentials rejected")
    class MissingStoredCredentials : MoodleWebserviceError("No stored NTUST credentials")
    data class SsoLoginRejected(val reason: String?) :
        MoodleWebserviceError("NTUST SSO rejected Moodle authorization: ${reason ?: "unknown"}")

    /**
     * NTUST's SSO showed its sign-in page with no form to post to, such as a lockout notice, so
     * no password went out. Apart from [SsoLoginRejected], which latches the stored password.
     */
    data class SsoLoginFormUnavailable(val reason: String?) :
        MoodleWebserviceError("NTUST SSO sign-in form unavailable: ${reason ?: "unknown"}")

    class WebserviceDisabled : MoodleWebserviceError("Moodle webservice disabled")

    /**
     * Moodle is in maintenance mode: its `sitemaintenance` error, or the 503 with `Retry-After`
     * its CLI maintenance page answers every request with during an upgrade.
     */
    class SiteMaintenance : MoodleWebserviceError("Moodle under maintenance")
    data class TransientNetwork(val underlying: String) :
        MoodleWebserviceError("Moodle network error: $underlying")

    data class MalformedResponse(val detail: String) :
        MoodleWebserviceError("Malformed Moodle response: $detail")

    data class HttpStatus(val code: Int) : MoodleWebserviceError("Moodle HTTP status $code")

    companion object {
        private val gson = Gson()

        /**
         * Attempt to parse a Moodle REST error body. Returns null when the
         * body is valid data, not an error envelope.
         */
        fun fromJsonBody(body: String): MoodleWebserviceError? {
            val parsed = try {
                gson.fromJson(body, ErrorBody::class.java)
            } catch (_: Exception) {
                return null
            } ?: return null
            val code = parsed.errorcode ?: return null
            return when (code) {
                "invalidtoken" -> InvalidToken()
                "accessexception" -> AccessDenied()
                "invalidlogin" -> InvalidCredentials()
                "enablewsdescription", "servicenotloaded" -> WebserviceDisabled()
                "sitemaintenance" -> SiteMaintenance()
                else -> MalformedResponse(code)
            }
        }

        /**
         * Whether [error] is the device failing to reach Moodle at all, as opposed to Moodle
         * answering with an error. The screens draw the two differently.
         */
        fun isNetwork(error: Throwable?): Boolean =
            error is TransientNetwork || error is IOException

        private data class ErrorBody(
            @SerializedName("errorcode") val errorcode: String? = null,
        )
    }
}
