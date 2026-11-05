package org.ntust.app.tigerduck.auth

/** The header a request starting now gets, or null with none to give: a sign-in read, then the header. */
internal suspend fun AuthTokenManager.authHeader(): String? = try {
    requireAuthHeader(session.signIns.current)
} catch (_: NotSignedInException) {
    null
}
