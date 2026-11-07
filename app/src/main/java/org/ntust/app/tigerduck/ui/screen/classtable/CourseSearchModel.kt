package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogFiles
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogStore
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogUnavailableException
import org.ntust.app.tigerduck.coursecatalog.CourseSearchOptions
import org.ntust.app.tigerduck.network.model.CourseSearchResult

/**
 * The Add course sheet's search and filters, over the term's catalog from the TigerDuck backend.
 * The stored catalog is searched at once, and the backend is asked once per sheet whether it
 * changed; nothing on this path asks the school. As the iPhone's `CourseSearchModel`.
 *
 * Its state is read and written on [scope]'s thread, the main one in the sheet; the search itself
 * runs on [compute].
 */
class CourseSearchModel(
    scope: CoroutineScope,
    files: CourseCatalogFiles?,
    label: suspend (rows: List<CourseSearchResult>, language: String) -> List<CourseSearchResult> =
        { rows, _ -> rows },
    io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val catalog = CourseCatalogStore(scope, files, label, io)

    var filters by mutableStateOf(CourseSearchFilters())

    /** Rows per catalog language, none repeated. A language that found nothing has no entry. */
    var results by mutableStateOf<Map<String, List<CourseSearchResult>>>(emptyMap())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var hasSearched by mutableStateOf(false)
        private set
    var failure by mutableStateOf<Throwable?>(null)
        private set
    private var retries by mutableIntStateOf(0)

    val options: CourseSearchOptions get() = catalog.options

    /** Part of the sheet's search key: a change runs the search again with the same query. */
    val searchAgainCount: Int get() = retries + catalog.staleSearchGeneration

    /** Searches the catalog, once it is loaded, off the main thread. A blank query only clears. */
    suspend fun search(query: String, semester: String, fetch: CourseCatalogStore.Fetch) {
        val trimmed = query.trim()
        hasSearched = trimmed.isNotEmpty()
        if (trimmed.isEmpty()) results = emptyMap()
        if (catalog.catalogs.isEmpty()) {
            failure = null
            isSearching = trimmed.isNotEmpty()
        }
        catalog.load(semester, fetch)
        currentCoroutineContext().ensureActive()
        if (trimmed.isEmpty()) {
            failure = null
            isSearching = false
            return
        }
        if (catalog.catalogs.isEmpty()) {
            results = emptyMap()
            failure = catalog.loadFailure ?: CourseCatalogUnavailableException()
            isSearching = false
            return
        }
        val courses = catalog.catalogs.mapValues { it.value.courses }
        val searched = filters.searched
        val found = withContext(compute) { CourseSearch.search(trimmed, courses, searched) }
        results = found
        failure = null
        isSearching = false
    }

    /**
     * Enter in the search field. It searches again only to retry a failed search or a catalog
     * language that failed to load, as one that ran or is running would find the same rows.
     */
    fun submit() {
        if (failure == null && !catalog.lacksLanguage) return
        catalog.allowRevalidation()
        retries++
    }

    /**
     * The row of [courseNo] in [language]'s catalog, as the app shows it; null when that catalog
     * lacks it, and the caller then keeps the row it was found with.
     */
    fun row(courseNo: String, language: String): CourseSearchResult? = catalog.catalogs[language]?.row(courseNo)
}
