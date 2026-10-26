package org.ntust.app.tigerduck.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One assignment's submission status from one call with the stored wstoken,
 * for a reminder about to show. It never signs in or renews the token: an SSO
 * sign-in there is one nobody asked for (issue #212), so it is handed the
 * stored token to read and nothing that could renew it.
 */
@Singleton
class SubmissionStatusProbe internal constructor(
    private val storedToken: () -> String?,
    private val client: OkHttpClient,
) {
    @Inject
    constructor(tokenService: MoodleTokenService, sessionManager: NtustSessionManager) : this(
        tokenService::currentToken,
        // It has to end inside the reminder receiver's 8-second budget, which
        // the shared client's 15-second timeouts would outlast.
        sessionManager.client.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
    )

    /**
     * The status of assignment [assignId], or null when there is no token, or
     * it changed while the call ran: the answer would be about an account that
     * signed out. No user id, which Moodle reads as the token's own account, so
     * no site-info call goes first. Throws for anything but an answer, and
     * [MoodleWebserviceError.InvalidToken] when the token is rejected.
     */
    suspend fun status(assignId: Int): MoodleSubmissionStatusEnvelope? = withContext(Dispatchers.IO) {
        val token = storedToken()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val status = MoodleService.requestSubmissionStatus(
            client, token, assignId, userId = null,
        )
        status.takeIf { storedToken() == token }
    }

    private companion object {
        const val CALL_TIMEOUT_SECONDS = 6L
    }
}
