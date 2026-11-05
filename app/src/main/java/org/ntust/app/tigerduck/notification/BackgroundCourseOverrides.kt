package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.classtable.CourseNameOverrides
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.StampedMerges

/**
 * A pull's pass of a snapshot's course colors and names over the term it caches, which the
 * synchronizer's colors and names step runs in the background (`OverridesReconciler`), and in
 * Home's pass too, over Home's term. An object of its own, so its tests run it over a real cache.
 */
internal object BackgroundCourseOverrides {

    /**
     * The pull's merges of the colors and names ([stamped], LWW D12) over [semester]'s cached
     * courses, with the names stamped onto them in [locale]: the widgets read the cached courses,
     * not the names file.
     *
     * Applied to the courses and the names as they stand, each under its lock
     * ([DataCache.updateCoursesIfChanged], [DataCache.updateCourseCustomNames]),
     * so a course added or renamed while this runs is not undone by a copy read
     * before; and with nothing changed, nothing is written. With no course
     * cached nothing is applied, names included, as in Home. Returns the
     * courses and the colours moved off a duplicate, for the caller to send, or
     * null when there was nothing to write or send.
     */
    suspend fun apply(
        dataCache: DataCache,
        semester: String,
        syncColors: Boolean,
        syncNames: Boolean,
        locale: String,
        signIn: SignIn,
        stamped: StampedMerges,
    ): CourseSyncReconciler.ColorOutcome? {
        var applied: CourseSyncReconciler.ColorOutcome? = null
        val written = dataCache.updateCoursesIfChanged(semester, signIn) { cached ->
            if (cached.isEmpty()) return@updateCoursesIfChanged cached
            val names = if (syncNames) dataCache.updateCourseCustomNames(signIn) { current -> stamped.names(current) ?: current } else null
            val named = if (names != null) CourseNameOverrides.stamp(cached, names, locale) else cached
            val outcome = if (syncColors) stamped.colors(named) else null
            val updated = outcome?.courses ?: named
            applied = CourseSyncReconciler.ColorOutcome(updated, outcome?.moved.orEmpty())
            updated
        }
        // A colour moved off a duplicate still goes up when the list came out as it was.
        return applied?.takeIf { written != null || it.moved.isNotEmpty() }
    }
}
