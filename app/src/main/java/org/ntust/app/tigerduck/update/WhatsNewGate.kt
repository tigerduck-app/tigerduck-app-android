package org.ntust.app.tigerduck.update

import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * Pure gating logic for the paged "What's new" sheet.
 *
 * After an upgrade the sheet shows the feature pages of every version the
 * user hasn't seen yet, oldest first, then the summary page of the running
 * version. A fresh install (last-seen versionCode is the
 * [AppPreferences.WHATS_NEW_UNSET] sentinel, onboarding not yet done) shows
 * nothing — a brand-new user hasn't missed anything.
 */
object WhatsNewGate {

    fun shouldShow(lastSeenVersionCode: Int, currentVersionCode: Int): Boolean {
        if (lastSeenVersionCode == AppPreferences.WHATS_NEW_UNSET) return false
        // Replay sentinel is handled by plan() before the gate is consulted,
        // but guard here too so the gate stays correct if a new call site (or
        // a reordering) forgets about the sentinel.
        if (lastSeenVersionCode == AppPreferences.WHATS_NEW_REPLAY) return false
        return lastSeenVersionCode < currentVersionCode
    }

    /** What [plan] decided for this launch. */
    sealed interface Plan {
        /** Nothing to show: record the running versionCode as seen now. */
        data object RecordOnly : Plan

        /**
         * Leave everything untouched — the replay sentinel on a config-change
         * recreation, which must fire on the next process start instead.
         */
        data object Defer : Plan

        /**
         * Show the feature pages registered for [pageVersions] (oldest first),
         * then the summary of [summaryVersion] if it isn't null. [replay]
         * means the user asked to see it again, so the pages' "only if this
         * applies" checks are skipped.
         */
        data class Show(
            val pageVersions: List<Int>,
            val summaryVersion: Int?,
            val replay: Boolean,
        ) : Plan
    }

    /**
     * Decides what the sheet shows on this launch.
     *
     * The version sets are suppliers, asked for only on the branches that
     * need them: the common launch — already on the version last seen —
     * decides without building the catalog or reading `whatsnew.json`.
     *
     * @param pageVersions versionCodes with feature pages in the catalog.
     * @param summaryVersions versionCodes with a usable summary in the
     *   user's locale.
     * @param freshStart false on a config-change recreation, where the debug
     *   replay sentinel must not be consumed.
     */
    fun plan(
        lastSeen: Int,
        current: Int,
        hasCompletedOnboarding: Boolean,
        freshStart: Boolean,
        pageVersions: () -> Set<Int>,
        summaryVersions: () -> Set<Int>,
    ): Plan {
        // Replay ("What's new" in Settings, or the debug trigger): the newest
        // registered version, even one newer than this build — whatsnew.json
        // is usually written ahead of the version bump.
        if (lastSeen == AppPreferences.WHATS_NEW_REPLAY) {
            if (!freshStart) return Plan.Defer
            return replay(pageVersions(), summaryVersions()) ?: Plan.RecordOnly
        }

        // No versionCode on record. A genuine fresh install shows nothing. A
        // user upgrading from a build that predates the pref also has no
        // record, but has completed onboarding: show only the running
        // version, never the whole history.
        if (lastSeen == AppPreferences.WHATS_NEW_UNSET) {
            if (!hasCompletedOnboarding) return Plan.RecordOnly
            return show(listOf(current).filter { it in pageVersions() }, current, summaryVersions())
        }

        if (!shouldShow(lastSeen, current)) return Plan.RecordOnly
        val skipped = pageVersions().filter { it in (lastSeen + 1)..current }.sorted()
        return show(skipped, current, summaryVersions())
    }

    /**
     * The last-seen versionCode to store once this launch is handled: the
     * running one, unless the record is already newer (a downgrade), so a
     * later re-upgrade doesn't show pages the user has already seen. The
     * [AppPreferences.WHATS_NEW_UNSET] and [AppPreferences.WHATS_NEW_REPLAY]
     * sentinels sit below every real versionCode, so they give way to it.
     */
    fun recordedAfter(lastSeen: Int, current: Int): Int = maxOf(lastSeen, current)

    /**
     * The newest registered version's pages and summary, ignoring the
     * running build's versionCode. Backs the Settings "What's new" row and
     * the replay sentinel. Null when nothing is registered at all.
     */
    fun replay(pageVersions: Set<Int>, summaryVersions: Set<Int>): Plan.Show? {
        val latest = (pageVersions + summaryVersions).maxOrNull() ?: return null
        return Plan.Show(
            pageVersions = listOfNotNull(latest.takeIf { it in pageVersions }),
            summaryVersion = latest.takeIf { it in summaryVersions },
            replay = true,
        )
    }

    /**
     * Flattens the pages of [versions] in order, keeping a page only when
     * [keep] says so. Generic so the rule is testable without building
     * Compose page objects.
     */
    fun <P> collectPages(
        versions: List<Int>,
        catalog: Map<Int, List<P>>,
        keep: (P) -> Boolean,
    ): List<P> = versions.flatMap { catalog[it].orEmpty() }.filter(keep)

    private fun show(pageVersions: List<Int>, current: Int, summaryVersions: Set<Int>): Plan {
        val summaryVersion = current.takeIf { it in summaryVersions }
        if (pageVersions.isEmpty() && summaryVersion == null) return Plan.RecordOnly
        return Plan.Show(pageVersions, summaryVersion, replay = false)
    }
}
