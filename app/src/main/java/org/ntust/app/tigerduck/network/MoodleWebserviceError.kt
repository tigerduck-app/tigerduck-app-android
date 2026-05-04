package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

sealed class MoodleWebserviceError(message: String) : Exception(message) {
    class InvalidToken : MoodleWebserviceError("Moodle token invalid")
    class InvalidCredentials : MoodleWebserviceError("Moodle credentials rejected")
    class MissingStoredCredentials : MoodleWebserviceError("No stored NTUST credentials")
    data class SsoLoginRejected(val reason: String?) :
        MoodleWebserviceError("NTUST SSO rejected Moodle authorization: ${reason ?: "unknown"}")

    class WebserviceDisabled : MoodleWebserviceError("Moodle webservice disabled")
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
                "invalidtoken", "accessexception" -> InvalidToken()
                "invalidlogin" -> InvalidCredentials()
                "enablewsdescription", "servicenotloaded" -> WebserviceDisabled()
                else -> MalformedResponse(code)
            }
        }

        private data class ErrorBody(
            @SerializedName("errorcode") val errorcode: String? = null,
        )
    }
}
