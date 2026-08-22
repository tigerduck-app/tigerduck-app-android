// Pure course-sync reconciliation, extracted from HomeViewModel.
//
// Deciding which courses the server has dropped is the part of sync that
// can destroy user data, and it has done so before: manual courses were
// once tombstoned merely for being absent from the server, which deleted
// courses the user had typed in by hand. The rule that prevents it —
// "server absence never tombstones a manual course" — is a single `if`
// that is easy to lose in a refactor and impossible to notice until a
// student's timetable is missing entries.
//
// It has no reason to be inside a ViewModel. Here it is a function of its
// inputs, and CourseSyncReconcilerTest holds the rules in place.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course

object CourseSyncReconciler {

    /**
     * The new deleted-course set, given what the server reports.
     *
     * Three rules, in order:
     *  1. A non-manual local course the server does not list was deleted
     *     elsewhere — tombstone it.
     *  2. An explicit tombstone from the server counts too.
     *  3. Anything the server *does* list is alive again, whatever we
     *     previously believed. This un-delete runs last so a course that
     *     reappears wins over both rules above.
     *
     * Manual courses are never tombstoned by rule 1. The user typed them in;
     * the server may simply not have them yet because an earlier upload
     * failed, and deleting on absence loses data with no way to recover it.
     */
    fun reconcileDeletions(
        localCourses: List<Course>,
        serverCourseNos: Set<String>,
        tombstonedNos: Set<String>,
        previouslyDeleted: Set<String>,
    ): Set<String> {
        val deleted = previouslyDeleted.toMutableSet()
        for (course in localCourses) {
            if (!course.isManual && course.courseNo !in serverCourseNos) {
                deleted.add(course.courseNo)
            }
        }
        deleted.addAll(tombstonedNos - serverCourseNos)
        deleted.removeAll(serverCourseNos)
        return deleted
    }

    /**
     * Courses the caller has decided it wants ([wanted] is normally
     * server-minus-local-minus-deleted), restricted to the current semester. Marked `isManual` because from this device's point of
     * view they did not come from an NTUST enrolment fetch, and a later
     * refresh must not wipe them.
     */
    fun coursesToMerge(
        serverCourses: List<ServerCourse>,
        wanted: Set<String>,
        semester: String,
    ): List<Course> = serverCourses
        .filter { it.semester == semester && it.courseNo in wanted }
        .distinctBy { it.courseNo }
        .map {
            Course(
                courseNo = it.courseNo,
                courseName = it.courseName,
                instructor = it.instructors.joinToString(", "),
                credits = it.credits,
                classroom = it.classroom,
                enrolledCount = it.enrolledCount,
                maxCount = it.maxCount,
                moodleIdNumber = it.moodleId,
                isManual = true,
                scheduleJson = it.scheduleJson,
                classroomMapJson = it.classroomMapJson,
            )
        }

    /**
     * Courses with server colours applied, or null when nothing changed —
     * the caller uses null to skip a cache write and a widget reload.
     *
     * An override matches on courseNo first and Moodle id second, because
     * manually-added courses may have no courseNo the server recognises.
     */
    fun applyColorOverrides(
        courses: List<Course>,
        overrides: List<CourseOverrideResult>,
        syncColors: Boolean,
    ): List<Course>? {
        if (!syncColors || courses.isEmpty()) return null
        var changed = false
        val updated = courses.map { course ->
            val override = overrides.find { it.courseNo == course.courseNo }
                ?: overrides.find { it.moodleCourseId == course.moodleIdNumber }
            val newHex = override?.colorHex ?: return@map course
            if (newHex != course.customColorHex) {
                changed = true
                course.copy(customColorHex = newHex)
            } else {
                course
            }
        }
        return if (changed) updated else null
    }

    /**
     * Custom names merged per (courseNo, locale), or null when nothing
     * changed. An empty name from the server means "cleared", so it removes
     * that locale rather than storing a blank; a course left with no locales
     * drops out of the map entirely instead of leaving an empty entry behind.
     */
    fun mergeCustomNames(
        existing: Map<String, Map<String, String>>,
        overrides: List<CourseOverrideResult>,
        syncNames: Boolean,
    ): Map<String, Map<String, String>>? {
        if (!syncNames) return null
        val merged = existing.mapValues { it.value.toMutableMap() }.toMutableMap()
        var changed = false
        for (o in overrides) {
            val no = o.courseNo ?: continue
            if (o.customNames.isEmpty()) continue
            val entry = merged[no]?.toMutableMap() ?: mutableMapOf()
            for ((locale, name) in o.customNames) {
                if (name.isEmpty()) entry.remove(locale) else entry[locale] = name
            }
            if (entry.isEmpty()) merged.remove(no) else merged[no] = entry
            changed = true
        }
        return if (changed) merged.mapValues { it.value.toMap() } else null
    }
}
