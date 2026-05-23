package org.ntust.app.tigerduck.shared

/**
 * Wire types for `api.lib.ntust.edu.tw/v1`. Defined in `:shared` so phone and
 * watch send/receive the exact same JSON shape — a rename on one side would
 * otherwise silently desync.
 */
object LibraryApi {
    const val BASE_URL = "https://api.lib.ntust.edu.tw/v1"
    const val PATH_LOGIN = "/passport/login"
    const val PATH_GENERATE_QR = "/virtual-code/generate"

    /** Default rotation cadence enforced server-side; QR refreshes every N s. */
    const val QR_VALID_SECONDS = 30

    /**
     * Redact tokens, passports, passwords from `?key=value` style payloads so
     * OkHttp's body log doesn't leak secrets. Shared so phone and watch use
     * identical rules.
     */
    private val SENSITIVE_PARAM_REGEX =
        Regex("""\b(wstoken|passport|token|code|state|password|Password)=([^&\s"]+)""")

    fun redactSensitive(message: String): String =
        SENSITIVE_PARAM_REGEX.replace(message) { m -> "${m.groupValues[1]}=***" }
}

data class LibraryLoginRequest(
    val username: String,
    val password: String,
    val language: String = "zh",
)

data class LibraryLoginResponse(
    val data: LibraryLoginData?,
    val error: LibraryApiError? = null,
)

data class LibraryLoginData(
    val username: String,
    val token: String,
    val expirationTimeStamp: Long,
)

data class LibraryQRRequest(
    val token: String,
    val language: String = "zh",
)

data class LibraryQRResponse(
    val data: String?,
    val error: LibraryApiError? = null,
)

data class LibraryApiError(
    val code: Int,
    val message: String,
)

sealed class LibraryServiceError : Exception() {
    class CredentialsNotFound : LibraryServiceError()
    data class LoginFailed(val msg: String) : LibraryServiceError() {
        override val message: String get() = msg
    }

    data class QRGenerationFailed(val msg: String) : LibraryServiceError() {
        override val message: String get() = msg
    }
}
