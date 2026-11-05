package org.ntust.app.tigerduck.auth

/** How a send of the refresh token ended, for [AuthTokenManager]. */
internal sealed interface RefreshOutcome {
    // Not a data class, whose toString() would print the token.
    class Refreshed(val accessToken: String, val refreshToken: String, val expiresAt: Long) : RefreshOutcome

    /** No reply came back, so the backend may or may not have rotated the token. */
    data object NoReply : RefreshOutcome

    /**
     * A 5xx: the token is kept for a later refresh, and counted, as a
     * gateway's may come after the backend rotated.
     */
    data object Unavailable : RefreshOutcome

    /**
     * A 429, or a send that never left the device: the backend did not
     * use the token, which is kept for a later refresh without the send
     * counted.
     */
    data object Unused : RefreshOutcome

    /**
     * A send the disk would not count, which therefore never went out:
     * the token is kept, and the app signs in instead.
     */
    data object Uncounted : RefreshOutcome

    /** Anything else, a 401 among them: the app signs in again, as after every failed refresh before. */
    data object Refused : RefreshOutcome
}
