// User-supplied course-name overrides, keyed courseNo -> locale -> name.
//
// Two levels because renaming is per-language: a user who writes "資結" in
// Chinese and "Data Structures" in English gets both, and switching the app
// language switches which one shows. A single-level map would make one of
// those two renames silently overwrite the other.
//
// Held as immutable maps and rebuilt on every edit, so the value written to
// DataCache is exactly the value the UI is showing. The mutable-in-place
// version this replaced could persist a map that had already diverged from
// the course list it was stamped onto.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.shared.Course

/** courseNo -> locale ("zh"/"en") -> user-supplied display name. */
typealias CustomNameMap = Map<String, Map<String, String>>

object CourseNameOverrides {

    /**
     * Stamp [Course.customCourseName] for [locale] onto each course.
     *
     * A course with no entry for this locale is returned untouched — it keeps
     * whatever override it already carried, which may be null. That matters
     * on the merge path, where the caller has already resolved the override
     * and must not have it cleared again.
     */
    fun resolve(courses: List<Course>, names: CustomNameMap, locale: String): List<Course> {
        if (names.isEmpty()) return courses
        return courses.map { course ->
            val name = names[course.courseNo]?.get(locale)
            if (name != null) course.copy(customCourseName = name) else course
        }
    }

    fun overrideFor(names: CustomNameMap, courseNo: String, locale: String): String? =
        names[courseNo]?.get(locale)

    /**
     * Set or clear one course's override for one locale.
     *
     * Passing a null [name] clears it, and a course left with no overrides in
     * any locale is dropped from the map entirely. The pruning is what keeps
     * an "everything reverted" state from persisting as a map full of empty
     * entries, which [resolve] would then walk on every course list rebuild.
     */
    fun withOverride(
        names: CustomNameMap,
        courseNo: String,
        locale: String,
        name: String?,
    ): CustomNameMap {
        val forCourse = names[courseNo].orEmpty()
        val updated = if (name != null) forCourse + (locale to name) else forCourse - locale
        return if (updated.isEmpty()) names - courseNo else names + (courseNo to updated)
    }
}
