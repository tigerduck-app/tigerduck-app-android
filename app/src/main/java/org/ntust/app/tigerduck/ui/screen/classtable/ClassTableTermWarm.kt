// Filling the cache of every term the picker offers, once, so a term picked
// later shows at once instead of an empty grid until its fetch returns
// (tigerduck-app-android#265). The iPhone's `warmAllSemesterCaches` fans the
// terms out together; here they go one at a time, since nobody is waiting on
// them and the school servers see every lookup.

package org.ntust.app.tigerduck.ui.screen.classtable

internal class ClassTableTermWarm(
    /** The picker's terms, newest first. */
    private val terms: () -> List<String>,
    /** Whether a term has courses cached. One that has is not fetched again. */
    private val isCached: suspend (String) -> Boolean,
    /** Whether a term may be fetched right now: signed in, online, not the demo account. */
    private val mayFetch: () -> Boolean,
    /** The term on screen, whose own fetch handles it. */
    private val shownTerm: () -> String,
) {
    private var started = false

    // Per term, how many picks, foreground fetches and resets of it have
    // begun; see supersede. Touched on the main thread only, as the rest of
    // the view model's state is.
    private val generations = HashMap<String, Int>()

    /**
     * A pick, a foreground fetch or a reset of [term] began. A warm of it
     * still in its lookups would otherwise land its older rows over what that
     * leaves — the wipe, or the fresh roster — so its `isCurrent` says no
     * from here on; see [run].
     */
    fun supersede(term: String) {
        generations[term] = (generations[term] ?: 0) + 1
    }

    /**
     * Fetches each term with no cache, newest first and one at a time,
     * through [fetch], which says whether the school answered. The first term
     * it did not answer for ends the run: the rest would fail the same way,
     * and each failed lookup is a request the server still takes. Each term
     * is checked again as its turn comes, so one picked or cached while an
     * earlier term ran is left to its own fetch.
     *
     * [fetch] is handed `isCurrent`, which says whether nothing has
     * [supersede]d the term since its warm began, to be asked right before
     * anything of the term is saved or uploaded.
     *
     * Once per sign-in: a later call returns at once, until [reset].
     */
    suspend fun run(fetch: suspend (term: String, isCurrent: () -> Boolean) -> Boolean) =
        run(stillSignedIn = { true }, shared = { Unit }) { term, _, isCurrent -> fetch(term, isCurrent) }

    /**
     * [run], with what [shared] answers once for every term (the class
     * table's transcript), asked for as the first term still needing a fetch
     * comes up, for the account [stillSignedIn] names. An answer that
     * outlives a sign-out reaches no term, and each term's `isCurrent` says
     * no once the account has left.
     *
     * The answer takes seconds, and a term can be picked, fetched or cached
     * meanwhile: its `isCurrent` counts from before the wait, and a term no
     * longer wanting a fetch once it ends is left to its own.
     *
     * With no answer, null, each term is still handed to [fetch], which picks
     * its source: course selection's term needs none. The run is then not
     * used up, so a later fetch warms the terms that needed it.
     *
     * As iOS `AppServiceBridge.warmTerms` (tigerduck-app#890).
     */
    suspend fun <A : Any> run(
        stillSignedIn: () -> Boolean,
        shared: suspend () -> A?,
        fetch: suspend (term: String, shared: A?, isCurrent: () -> Boolean) -> Boolean,
    ) {
        if (started) return
        started = true
        var asked = false
        var answers: A? = null
        for (term in terms()) {
            if (!mayFetch()) break
            if (!stillSignedIn()) return
            if (!wantsFetch(term)) continue
            // Before anything of the term suspends, the wait for the answers
            // included: a pick or a fetch of it meanwhile makes this stale.
            val generation = generations[term] ?: 0
            val isCurrent = { (generations[term] ?: 0) == generation && stillSignedIn() }
            if (!asked) {
                answers = shared()
                asked = true
                if (!stillSignedIn()) return
                if (!isCurrent() || !wantsFetch(term)) continue
            }
            if (!fetch(term, answers, isCurrent)) break
        }
        if (asked && answers == null) started = false
    }

    // Neither on screen, whose own fetch handles it, nor cached already.
    private suspend fun wantsFetch(term: String): Boolean = term != shownTerm() && !isCached(term)

    /** A sign-out: the next account's first fetch warms its terms. */
    fun reset() {
        started = false
    }
}
