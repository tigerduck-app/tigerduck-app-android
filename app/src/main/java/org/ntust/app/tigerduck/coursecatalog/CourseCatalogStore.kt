package org.ntust.app.tigerduck.coursecatalog

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.util.logSummary

/**
 * One Add course sheet's catalogs of the term, in both languages: the stored copies first, then
 * one question to the backend whether they changed. As the iPhone's `CourseCatalogStore`.
 *
 * Its state is read and written on [scope]'s thread; files are read, decoded and written on [io].
 * The read and the revalidation run in [scope], so a search cancelled for a newer keystroke leaves
 * them going for the next. [label] shows each language's rows as the app does (the English
 * abbreviations); the search still matches what the backend sent.
 */
class CourseCatalogStore(
    private val scope: CoroutineScope,
    private val files: CourseCatalogFiles?,
    private val label: suspend (rows: List<CourseSearchResult>, language: String) -> List<CourseSearchResult> =
        { rows, _ -> rows },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A conditional fetch of one language's catalog; see [CourseCatalogClient.fetch]. */
    fun interface Fetch {
        suspend fun fetch(semester: String, language: String, etag: String?): CourseCatalogAnswer
    }

    var options by mutableStateOf(CourseSearchOptions())
        private set

    /** Counts the times the backend's answer replaced a catalog already searched. */
    var staleSearchGeneration by mutableIntStateOf(0)
        private set

    var catalogs: Map<String, CourseCatalog> = emptyMap()
        private set

    /** Why no catalog could be had; read only while [catalogs] is empty. */
    var loadFailure: Throwable? = null
        private set

    /** Whether the last revalidation left a language without a catalog. */
    var lacksLanguage = false
        private set

    private var storedETags: Map<String, String> = emptyMap()
    private var storedRead: Deferred<Unit>? = null
    private var revalidation: Deferred<Unit>? = null

    /** Lets the next [load] ask the backend again, for Enter after a failure. */
    fun allowRevalidation() {
        lacksLanguage = false
        revalidation = null
    }

    /**
     * Reads the stored catalogs, then asks the backend once whether they changed. Waits for the
     * backend only while no catalog is stored.
     */
    suspend fun load(semester: String, fetch: Fetch) {
        val read = storedRead ?: scope.async { readStored(semester) }.also { storedRead = it }
        read.await()
        val check = revalidation ?: scope.async { revalidate(semester, fetch) }.also { revalidation = it }
        if (catalogs.isEmpty()) check.await()
    }

    private suspend fun readStored(semester: String) {
        val stored = withContext(io) {
            coroutineScope {
                LANGUAGES.map { language ->
                    async {
                        val copy = files?.load(semester, language) ?: return@async null
                        try {
                            Triple(language, decode(copy.body, language), copy.etag)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "stored $language catalog unreadable: ${e.logSummary()}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        if (stored.isEmpty()) return
        catalogs = stored.associate { (language, catalog, _) -> language to catalog }
        storedETags = stored.mapNotNull { (language, _, etag) -> etag?.let { language to it } }.toMap()
        options = CourseSearchOptions.of(catalogs)
    }

    // Both answers are taken in together, so a search never sees one language's new catalog
    // beside the other's old one.
    private suspend fun revalidate(semester: String, fetch: Fetch) {
        val hadCatalog = catalogs.isNotEmpty()
        val answers = coroutineScope {
            LANGUAGES.map { language ->
                val etag = storedETags[language]
                async { language to fetchOne(semester, language, etag, fetch) }
            }.awaitAll()
        }
        val fresh = answers.mapNotNull { (language, result) -> result.getOrNull()?.let { language to it } }
        catalogs = catalogs + fresh.map { (language, copy) -> language to copy.first }
        storedETags = storedETags - fresh.map { it.first }.toSet() +
            fresh.mapNotNull { (language, copy) -> copy.second?.let { language to it } }
        if (fresh.isNotEmpty()) {
            options = CourseSearchOptions.of(catalogs)
            if (hadCatalog) staleSearchGeneration++
        }
        if (catalogs.isEmpty()) loadFailure = answers.firstNotNullOfOrNull { it.second.exceptionOrNull() }
        lacksLanguage = catalogs.size < LANGUAGES.size
    }

    /** The new catalog and its ETag, null for a 304, or the failure. */
    private suspend fun fetchOne(
        semester: String,
        language: String,
        etag: String?,
        fetch: Fetch,
    ): Result<Pair<CourseCatalog, String?>?> = try {
        when (val answer = fetch.fetch(semester, language, etag)) {
            CourseCatalogAnswer.NotModified -> Result.success(null)
            is CourseCatalogAnswer.Changed -> withContext(io) {
                // Decoded before it is stored, so a copy this build cannot read is never kept.
                val catalog = decode(answer.copy.body, language)
                files?.save(answer.copy, semester, language)
                Result.success(catalog to answer.copy.etag)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "fetching the $language catalog failed: ${e.logSummary()}")
        Result.failure(e)
    }

    private suspend fun decode(body: String, language: String): CourseCatalog {
        val catalog = CourseCatalog.decode(body)
        return catalog.showing(label(catalog.courses.map { it.row }, language))
    }

    companion object {
        private const val TAG = "CourseCatalog"

        val LANGUAGES = listOf("zh", "en")
    }
}
