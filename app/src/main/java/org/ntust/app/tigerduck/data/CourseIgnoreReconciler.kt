package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse

/**
 * What a full sync does to this device's ignored courses ([CourseIgnores]).
 * Pure, so the rules that decide whether a tap survives a sync are tested
 * without a network or a cache; [CourseIgnoreSync] does the I/O.
 */
object CourseIgnoreReconciler {

    /**
     * What a full sync says: the terms it has course rows for, the courses
     * ignored in them, the courses it lists, and the ones whose setting a
     * device has sent, either way ([decided]).
     */
    data class ServerIgnores(
        val semesters: Set<String>,
        val keys: Set<String>,
        val listed: Set<String>,
        val decided: Set<String>,
    )

    /**
     * Null when the overrides are there but none carries
     * `ignore_assignments`: an older backend, whose silence says nothing
     * about what is ignored. No overrides at all is what the backend leaves
     * once it drops them with the course rows, so that reads as no settings.
     *
     * Terms this device reset after the snapshot was fetched
     * ([resetSemesters]) are left out: the snapshot still holds them from
     * before the reset. A course another device deleted and added back
     * ([recreated]) counts as decided: its setting went with the delete.
     */
    fun serverIgnores(
        overrides: List<CourseOverrideResult>,
        serverCourses: List<ServerCourse>,
        resetSemesters: Set<String> = emptySet(),
        recreated: Set<String> = emptySet(),
    ): ServerIgnores? {
        if (overrides.isNotEmpty() && overrides.none { it.ignoreAssignments != null }) return null
        val applies = { key: String -> CourseIgnores.semesterOf(key) !in resetSemesters }
        val semesters = serverCourses.mapNotNullTo(mutableSetOf()) { it.semester.ifBlank { null } } -
            resetSemesters
        val listed = serverCourses.asSequence()
            .mapNotNull { keyOf(it.semester, it.courseNo) }
            .filterTo(mutableSetOf(), applies)
        val keys = overrides.asSequence()
            .filter { it.ignoreAssignments == true }
            .mapNotNull { keyOf(it.semester, it.courseNo) }
            .filterTo(mutableSetOf()) { CourseIgnores.semesterOf(it) in semesters }
        val decided = overrides.asSequence()
            .filter { it.ignoreAssignments == true || it.ignoreAssignmentsUpdatedAt != null }
            .mapNotNull { keyOf(it.semester, it.courseNo) }
            .filterTo(mutableSetOf(), applies) + recreated.filter(applies)
        return ServerIgnores(semesters, keys, listed, decided)
    }

    /** The server row id of each course [serverCourses] lists with one, by course key. */
    fun listedRows(serverCourses: List<ServerCourse>): Map<String, Long> =
        serverCourses.mapNotNull { c -> keyOf(c.semester, c.courseNo)?.let { key -> c.id?.let { key to it } } }
            .toMap()

    /**
     * The courses of [listed] some device deleted and added back since this
     * device recorded [seen]: each has a new row id while another row of its
     * term kept its own. The delete took the course's setting with it, so
     * this device's old one is not sent again; the server's, none, counts as
     * decided. A term whose rows are all new was reset: by a device, by Use
     * Local, which resets each term it uploads and keeps this device's
     * settings there, or by the backend dropping every course row while no
     * device synced courses. Its settings are sent again as before
     * ([toResend]), whether or not another term kept its rows: the device
     * that reset it may lack this device's setting, and has nothing to send.
     * A course with no recorded id counts as unchanged. Tombstones cannot
     * tell a re-add: its upload deletes the ones it names (`force_keys`).
     * The same rule as iOS's `AppState.recreatedCourses`
     * (tigerduck-app/tigerduck-app#367, tigerduck-app-android#182).
     *
     * What it cannot tell: a term whose only course was deleted and added
     * back, which no other row of the term vouches for, so this device's
     * setting goes up again.
     */
    fun recreated(listed: Map<String, Long>, seen: Map<String, Long>): Set<String> {
        val survivingTerms = listed
            .filter { (key, id) -> seen[key] == id }
            .keys
            .mapTo(mutableSetOf()) { CourseIgnores.semesterOf(it) }
        return listed.filterTo(mutableMapOf()) { (key, id) ->
            seen[key]?.let { it != id } == true && CourseIgnores.semesterOf(key) in survivingTerms
        }.keys
    }

    private fun keyOf(semester: String?, courseNo: String?): String? {
        val term = semester?.takeIf { it.isNotBlank() } ?: return null
        val no = courseNo?.takeIf { it.isNotBlank() } ?: return null
        return CourseIgnores.key(term, no)
    }

    /**
     * This device's ignored courses that [server] lists with no device's
     * setting, which a sync keeps and sends again: the backend drops the
     * settings with the course rows while no device syncs courses, and the
     * rows come back without them. [protectedKeys] are on their way already.
     */
    fun toResend(local: Set<String>, server: ServerIgnores, protectedKeys: Set<String>): Set<String> =
        local.filterTo(mutableSetOf()) {
            it in server.listed && it !in server.decided && it !in protectedKeys
        }

    /**
     * This device's set after a full sync, or null to leave it as it is.
     *
     * Null when [applies] is false, which is course or assignment sync being
     * off or a re-enable conflict still waiting for the user, and when
     * [server] is null. Otherwise, in every term the snapshot has course rows
     * for, the server decides, except for [protectedKeys] (changes the
     * backend has not confirmed, or made after the snapshot was fetched) and
     * [toResend], which keep this device's value. Terms with no rows are left
     * alone, since the backend has nothing to say about them.
     */
    fun afterSync(
        local: Set<String>,
        server: ServerIgnores?,
        protectedKeys: Set<String>,
        applies: Boolean,
    ): Set<String>? {
        if (!applies || server == null) return null
        val keptKeys = protectedKeys + toResend(local, server, protectedKeys)
        val kept = local.filter {
            CourseIgnores.semesterOf(it) !in server.semesters || it in keptKeys
        }
        val adopted = server.keys.filter { it !in protectedKeys }
        return (kept + adopted).toSet()
    }

    /**
     * This device's set when the user takes the backend's, or null when
     * [server] is: [server]'s in every term it covers, lost settings included.
     */
    fun adopt(local: Set<String>, server: ServerIgnores?): Set<String>? {
        if (server == null) return null
        return local.filterTo(mutableSetOf()) { CourseIgnores.semesterOf(it) !in server.semesters } + server.keys
    }

    /**
     * Whether [local] matches [server] where a sync would apply it. A null
     * [server] cannot disagree.
     */
    fun agrees(local: Set<String>, server: ServerIgnores?): Boolean =
        afterSync(local, server, emptySet(), applies = true)?.let { it == local } ?: true
}
