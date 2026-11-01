package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.ui.screen.classtable.CourseNameOverrides
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler

/**
 * The background sync's pass of a snapshot's course colours and names over the
 * term it caches. Out of [BackgroundSyncWorker], which a JVM test cannot build,
 * so its tests run it over a real cache.
 */
internal object BackgroundCourseOverrides {

    /**
     * Home's colour and name passes ([CourseSyncReconciler.applyColorOverrides],
     * [CourseSyncReconciler.applyNameOverrides]) over [semester]'s cached
     * courses, with the names stamped onto them in [locale]: the widgets read
     * the cached courses, not the names file. [held] reads what the snapshot
     * must not overwrite.
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
        overrides: List<CourseOverrideResult>,
        held: suspend () -> Set<String>,
        syncColors: Boolean,
        syncNames: Boolean,
        locale: String,
    ): CourseSyncReconciler.ColorOutcome? {
        var applied: CourseSyncReconciler.ColorOutcome? = null
        val written = dataCache.updateCoursesIfChanged(semester) { cached ->
            if (cached.isEmpty()) return@updateCoursesIfChanged cached
            val names = CourseSyncReconciler.applyNameOverrides(dataCache, overrides, syncNames, held)
            val named = if (names != null) CourseNameOverrides.stamp(cached, names, locale) else cached
            val outcome = CourseSyncReconciler.applyColorOverrides(named, overrides, syncColors, held())
            val updated = outcome?.courses ?: named
            applied = CourseSyncReconciler.ColorOutcome(updated, outcome?.moved.orEmpty())
            updated
        }
        // A colour moved off a duplicate still goes up when the list came out as it was.
        return applied?.takeIf { written != null || it.moved.isNotEmpty() }
    }
}
