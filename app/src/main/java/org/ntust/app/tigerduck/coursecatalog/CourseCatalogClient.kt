package org.ntust.app.tigerduck.coursecatalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.clubs.ETaggedBody
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

/** What a conditional fetch of a catalog came to. */
sealed interface CourseCatalogAnswer {
    /** A 304: the stored copy still holds. */
    data object NotModified : CourseCatalogAnswer

    /** A new copy, with the ETag it came with. */
    data class Changed(val copy: ETaggedBody) : CourseCatalogAnswer
}

/**
 * The backend has no catalog for the term (404 `course_catalog_not_found`): it keeps the current
 * term and up to two after it. Not an `IOException`, so it never reads as a lost connection.
 */
class CourseCatalogUnavailableException : Exception("course catalog not found")

/**
 * Fetches term catalogs, `GET course-catalog/{semester}?language=zh|en`, behind the session, as the
 * iPhone's `CourseCatalogClient`. The backend sends a weak ETag and answers a stored one with a 304.
 */
@Singleton
class CourseCatalogClient @Inject constructor(
    private val authTokenManager: AuthTokenManager,
    private val prefs: AppPreferences,
    baseClient: OkHttpClient,
) {
    // Resolve per call so the debug API-endpoint override applies at once, as SyncApiClient does.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    // Past any HTTP cache, which could answer a revalidation itself and hide the 304; the sheet
    // keeps its own copy and sends its own If-None-Match.
    private val client = baseClient.newBuilder()
        .cache(null)
        .guardedBy(authTokenManager)
        .build()

    private val api = BackendServices(CourseCatalogApi::class.java, client)

    /**
     * The catalog of [semester] in [language], as a conditional GET of the copy [etag] came with.
     * Throws [CourseCatalogUnavailableException] for a term the backend has not stored, and sends
     * nothing without a session of [signIn]'s.
     */
    suspend fun fetch(semester: String, language: String, etag: String?, signIn: SignIn): CourseCatalogAnswer =
        withContext(Dispatchers.IO) {
            val authHeader = authTokenManager.requireAuthHeader(signIn)
            api.at(baseUrl).catalog(authHeader, semester, language, etag?.takeIf { it.isNotEmpty() })
                .execute().use { response ->
                    when {
                        response.code == HTTP_NOT_MODIFIED -> CourseCatalogAnswer.NotModified
                        response.code == HTTP_NOT_FOUND -> throw CourseCatalogUnavailableException()
                        !response.isSuccessful ->
                            throw PushApiException("course-catalog failed: HTTP ${response.code}", response.code)
                        else -> CourseCatalogAnswer.Changed(ETaggedBody(response.body.string(), response.header("ETag")))
                    }
                }
        }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_NOT_FOUND = 404
    }
}
