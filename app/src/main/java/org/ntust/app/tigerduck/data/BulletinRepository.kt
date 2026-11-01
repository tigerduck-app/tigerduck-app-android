package org.ntust.app.tigerduck.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.network.model.BulletinDetail
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped in-memory cache so the detail screen can render the title and
 * meta strip the moment it opens (using the list summary) and skip the
 * `/bulletins/{id}` round trip for articles already visited this session.
 * Taxonomy is shared too so the detail VM doesn't refetch what the list VM
 * already has.
 */
@Singleton
class BulletinRepository @Inject constructor() {

    private val summaries = HashMap<Int, BulletinSummary>()
    // The list rows' own summaries, apart from what a detail later stores over them.
    private val listSummaries = HashMap<Int, String>()
    private val details = HashMap<Int, BulletinDetail>()

    @Volatile
    private var taxonomyValue: TaxonomyResponse? = null
    private val taxonomyMutex = Mutex()

    /**
     * Where the list stood after its last refresh in this process: when the
     * first page came back, which filter it was for, and the cursor of the
     * first page not yet in the on-disk cache.
     */
    data class ListSession(
        val fetchedAtMs: Long,
        val includeDeleted: Boolean,
        val nextCursor: Int?,
    )

    @Volatile
    private var listSession: ListSession? = null

    /** Records a refresh of the list's first page, at [nowMs] on a monotonic clock. */
    @Synchronized
    fun listRefreshed(nowMs: Long, includeDeleted: Boolean, nextCursor: Int?) {
        listSession = ListSession(nowMs, includeDeleted, nextCursor)
    }

    /**
     * Moves the cursor on once the pages before [nextCursor] are in the
     * on-disk cache. Saved first, moved second: a cursor ahead of the cache
     * would skip the pages in between for good.
     */
    @Synchronized
    fun listAdvanced(includeDeleted: Boolean, nextCursor: Int?) {
        val session = listSession ?: return
        if (session.includeDeleted == includeDeleted) {
            listSession = session.copy(nextCursor = nextCursor)
        }
    }

    /**
     * The list as the last refresh in this process left it, for the same
     * filter; null when the list has not been refreshed since the app
     * started. The list screen's view model is rebuilt on every visit, so
     * this is how a visit tells a launch from a return.
     */
    fun listSession(includeDeleted: Boolean): ListSession? =
        listSession?.takeIf { it.includeDeleted == includeDeleted }

    @Synchronized
    fun putSummaries(items: List<BulletinSummary>) {
        for (item in items) {
            summaries[item.id] = item
            val summary = item.summary?.takeIf { it.isNotBlank() }
            if (summary != null) listSummaries[item.id] = summary else listSummaries.remove(item.id)
        }
    }

    @Synchronized
    fun summary(id: Int): BulletinSummary? = summaries[id]

    @Synchronized
    fun putDetail(detail: BulletinDetail) {
        details[detail.id] = detail
        // A detail without a summary keeps the list row's, which its page falls back to; never an
        // earlier detail's, which the server has since cleared.
        val listSummary = listSummaries[detail.id]
        summaries[detail.id] = detail.toSummary().let {
            if (it.summary.isNullOrBlank() && listSummary != null) it.copy(summary = listSummary) else it
        }
    }

    @Synchronized
    fun detail(id: Int): BulletinDetail? = details[id]

    /** Synchronous read of an already-cached value; never triggers a fetch. */
    fun taxonomy(): TaxonomyResponse? = taxonomyValue

    /**
     * Cache-or-fetch with exclusive cold-start serialization: if [fetcher]
     * returns null (network failure), or an endpoint move ([forget]) came while
     * it ran, the cache stays empty, null is returned, and the next caller
     * retries. Double-checks inside the lock so a single fetcher fires across
     * concurrent ViewModels.
     */
    suspend fun getOrFetchTaxonomy(fetcher: suspend () -> TaxonomyResponse?): TaxonomyResponse? {
        taxonomyValue?.let { return it }
        return taxonomyMutex.withLock {
            taxonomyValue?.let { return@withLock it }
            val asked = generation
            val fetched = fetcher() ?: return@withLock null
            // An endpoint move during the fetch: the answer is the old backend's.
            synchronized(this) { fetched.takeIf { generation == asked }?.also { taxonomyValue = it } }
        }
    }

    /**
     * Moved by each [forget]. What was fetched for an earlier value is the old backend's, and
     * whoever fetched it drops it rather than keep it here, on disk or on screen.
     */
    @Volatile
    var generation: Int = 0
        private set

    /**
     * Lets go of everything here, for an API endpoint move: the bulletins, their list session and
     * the departments and categories all came from the backend the app no longer talks to.
     */
    @Synchronized
    fun forget() {
        generation++
        summaries.clear()
        listSummaries.clear()
        details.clear()
        listSession = null
        taxonomyValue = null
    }
}
