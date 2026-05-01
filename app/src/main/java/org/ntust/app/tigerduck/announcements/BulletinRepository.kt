package org.ntust.app.tigerduck.announcements

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
    private val details = HashMap<Int, BulletinDetail>()
    @Volatile private var taxonomyValue: TaxonomyResponse? = null

    @Synchronized
    fun putSummaries(items: List<BulletinSummary>) {
        for (item in items) summaries[item.id] = item
    }

    @Synchronized
    fun summary(id: Int): BulletinSummary? = summaries[id]

    @Synchronized
    fun putDetail(detail: BulletinDetail) {
        details[detail.id] = detail
        summaries[detail.id] = detail.toSummary()
    }

    @Synchronized
    fun detail(id: Int): BulletinDetail? = details[id]

    fun taxonomy(): TaxonomyResponse? = taxonomyValue

    fun setTaxonomy(taxonomy: TaxonomyResponse) {
        taxonomyValue = taxonomy
    }
}
