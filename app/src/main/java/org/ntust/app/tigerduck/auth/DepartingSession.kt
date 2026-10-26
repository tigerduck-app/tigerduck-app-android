package org.ntust.app.tigerduck.auth

/**
 * The backend session of an account that signed out, taken before [AuthTokenManager.logout]
 * wiped it. Only the sign-out's device unregister goes out with it.
 *
 * Not a data class: its toString() would print the tokens.
 */
class DepartingSession(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] expires, epoch ms. */
    val accessExpiresAt: Long,
    /**
     * The backend that issued the tokens, as it was resolved at sign-out. They go back to it and
     * nowhere else: the API endpoint setting can name another host by the time they are sent.
     */
    val baseUrl: String,
    /** The student it is for; null for a session stored before that was recorded. */
    val studentId: String? = null,
    /**
     * How many of the session's own sends of [refreshToken] may have reached the backend without
     * this device getting the pair they were answered with, at sign-out: those that got no reply
     * or a 5xx, and one still on its way then. The backend takes a third send of the token for
     * reuse, so the debt keeps this count and never lets the token go out past two.
     */
    val sendsWithoutReply: Int = 0,
)

/** What [AuthTokenManager.refreshDeparting] got back. */
sealed interface DepartingRefresh {
    /** Not a data class, for the same reason as [DepartingSession]. */
    class Refreshed(val session: DepartingSession) : DepartingRefresh

    /** Turned down: the session is over on the server, and nothing can go out as it any more. */
    data object Refused : DepartingRefresh

    /**
     * A 5xx: worth trying again later with the same token, but counted as a send, as a gateway's
     * 5xx may come after the backend rotated the token.
     */
    data object Unavailable : DepartingRefresh

    /**
     * A 429, or a send that never left the phone: the backend did not use the token, so the send
     * is not counted; worth trying again later with the same token.
     */
    data object Unused : DepartingRefresh

    /**
     * Sent, and no reply came back: the backend may have rotated the token. Sent again at most
     * once (see [AuthTokenManager.refreshDeparting]); a third send would be taken for reuse.
     */
    data object NoReply : DepartingRefresh
}
