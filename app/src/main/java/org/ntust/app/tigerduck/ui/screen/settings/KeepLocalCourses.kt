package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.CourseDeleteKind
import org.ntust.app.tigerduck.data.schoolLists
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.push.CourseRef
import org.ntust.app.tigerduck.push.CourseUploadPayload
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.TermResets

/**
 * The course requests Use Local sends, so a test can stand in for the backend. Its steps are named
 * for what Use Local does: the backend client's writes they make are counted once, where they are
 * made (`sync-write`).
 */
internal interface CourseSyncBackend {
    /** `DELETE /sync/courses?semester=`: a reset of one term, never of every term. */
    suspend fun resetTerm(semester: String)

    /** `POST /sync/courses/upload` of the term's courses. */
    suspend fun uploadTerm(courses: List<Course>, semester: String)

    /** `DELETE /sync/courses/{course_key}`: [courseNo] in [semester], hidden here, hidden on every device. */
    suspend fun hideCourse(semester: String, courseNo: String)

    /**
     * `PATCH /sync/courses/{course_key}/override` with a color, or one locale's name, of the
     * course [courseNo] of [semester], stamped at its send: after every request Use Local sent
     * before it. The backend settles every field by its stamp, so a term's upload carries no
     * colors, and its reset clears every color and name stamped at or before it: each goes this
     * way, after the reset. [moodleId] is the id the course outbox knows it by, for a name that
     * must wait.
     */
    suspend fun patchStampedOverride(
        semester: String,
        courseNo: String,
        moodleId: String,
        colorHex: String? = null,
        name: String? = null,
        locale: String? = null,
    )

    /**
     * The course [courseNo] of [semester] as the phone holds it now, read right before each
     * override goes: its color and its names may have changed while Use Local's earlier
     * requests were out, and a course removed or hidden meanwhile holds none. Null from a backend
     * that keeps no copy of the phone's own (a test's): the values Use Local read at its start
     * stand. A change landing after the read, while the request renews its session, still goes
     * as read.
     */
    suspend fun currentChoices(semester: String, courseNo: String): LocalChoices?

    /**
     * Use Local read [courses], [semester]'s list as the phone held it, before it told each copy
     * merged down by the check's rows (`useLocalCourses`): [uploadTerm] goes by the phone's own
     * value of a copy whose provenance a write changed since (compare-and-set). A backend that keeps
     * no copy of the phone's own (a test's) needs none.
     */
    fun readFor(semester: String, courses: List<Course>) = Unit
}

/** A course's own choices on the phone: its custom color, and its custom name by locale. */
internal data class LocalChoices(val colorHex: String?, val names: Map<String, String>)

/**
 * Course sync went off while Use Local was sending. Nothing is asked then:
 * turning course sync back on marks the question again.
 */
internal class CourseSyncOffException : IllegalStateException("course sync is off")

/**
 * [CourseSyncBackend] over [PushApiClient], whose course requests return
 * quietly unsent with cloud or course sync off. Use Local's fail instead, so
 * it stops rather than follow a term reset with an upload that never went.
 * Every request is stamped at its send, in the order Use Local makes it, so
 * each is later than the reset before it (D10, X5).
 */
internal class PushCourseSyncBackend(
    private val api: PushApiClient,
    prefs: AppPreferences,
    private val overrides: PendingCourseOverrides,
    private val signIn: SignIn,
    /**
     * A version of the course outbox reserved before Use Local read the names it sends
     * ([PendingCourseOverrides.reserveVersion]): a name queued at it does not replace a rename
     * the student made since.
     */
    private val namesVersion: Long,
    /** The phone's courses and custom names, which [currentChoices] reads. */
    private val dataCache: DataCache,
    /** Where a term's reset goes, as every reset does: see [resetTerm]. */
    private val termResets: TermResets,
) : CourseSyncBackend {
    private val policies = SyncPolicies(prefs)

    private fun ensureSends() {
        if (!policies.current.uploadsCourses) throw CourseSyncOffException()
    }

    override suspend fun resetTerm(semester: String) {
        ensureSends()
        // Under the adds' send lock, the term's school uploads waiting for the first pull let go
        // once it lands: one sent after it would go over the local list as the term's roster.
        termResets.run(semester) { api.deleteAllCoursesAnswered(semester, signIn, resetAtMs = null).takeIf { it.sent } }
    }

    override suspend fun uploadTerm(courses: List<Course>, semester: String) {
        ensureSends()
        // A course added by hand goes as an add made now, after the term's reset: as the
        // oldest, as a roster names it, the reset would keep it out. Not a copy of a school row
        // merged down, which the upload lists as the school's. A copy still not told after Use
        // Local told each by the check's rows (useLocalCourses) goes as one too: the snapshot has
        // no row of it, and the student chose this phone's list; every other upload leaves it out.
        // A copy whose provenance the phone changed since Use Local read it, a school fetch's commit
        // mostly, goes as the phone holds it now (compare-and-set, Codex on round 6).
        val sent = asHeldWhereChanged(courses, semester)
        val addedAt = sent.filter { CourseUploadPayload.isAddedByHand(it) || CourseUploadPayload.isUntoldCopy(it) }
            .associate { it.courseNo to api.stampMs() }
        keepTold(sent, semester, addedAt.keys)
        // Null: switched off between the check and the request.
        api.uploadCoursesAnswered(sent, semester, signIn, addedAt = addedAt) ?: throw CourseSyncOffException()
    }

    // What each copy merged down said of its row when Use Local read its term ([readFor]), by term, then number.
    private val readProvenance = java.util.concurrent.ConcurrentHashMap<String, Map<String, Boolean?>>()

    override fun readFor(semester: String, courses: List<Course>) {
        readProvenance[semester] = courses.associate { it.courseNo to it.isUserAddedOnServer }
    }

    // Whether [course], as the phone holds it, says what it said when Use Local read its term: true
    // when Use Local read none of [semester] ([readFor]).
    private fun unchangedSinceRead(course: Course, semester: String): Boolean {
        val read = readProvenance[semester] ?: return true
        return course.courseNo in read && read[course.courseNo] == course.isUserAddedOnServer
    }

    // [courses], each copy whose provenance a write changed since Use Local read it with the phone's value.
    private suspend fun asHeldWhereChanged(courses: List<Course>, semester: String): List<Course> {
        if (readProvenance[semester] == null) return courses
        val held = dataCache.loadCourses(semester).associateBy { it.courseNo }
        return courses.map { course ->
            val now = held[course.courseNo]
            if (now == null || unchangedSinceRead(now, semester)) course else course.copy(isUserAddedOnServer = now.isUserAddedOnServer)
        }
    }

    // What Use Local told each copy merged down ([courses], as useLocalCourses told them), and each
    // copy it adds now ([added]), added by hand, kept on the phone before the upload (Codex on
    // round 5): the next upload, before any pull, goes by it, not by a stale value. Only over the
    // value Use Local read: one a write changed since stands (compare-and-set, Codex on round 6).
    private suspend fun keepTold(courses: List<Course>, semester: String, added: Set<String>) {
        val told = courses.filter { it.isManual && !it.moodleIdNumber.isNullOrEmpty() }
            .associate { it.courseNo to (it.courseNo in added || it.isUserAddedOnServer == true) }
        if (told.isEmpty()) return
        dataCache.updateCoursesIfChanged(semester, signIn) { current ->
            current.map { course ->
                val value = told[course.courseNo]?.takeIf { course.isManual && !course.moodleIdNumber.isNullOrEmpty() }
                if (value == null || value == course.isUserAddedOnServer || !unchangedSinceRead(course, semester)) course
                else course.copy(isUserAddedOnServer = value)
            }
        }
    }

    override suspend fun hideCourse(semester: String, courseNo: String) {
        ensureSends()
        // Named as the iPhone's Use Local names it: a hide of a course the school lists, none of any
        // other, for its row to decide (tigerduck-app-android#698). Never a removal, not even of a
        // course added here: Use Local has just uploaded each hidden course as the school's, which
        // a removal would keep on every other device.
        val kind = CourseDeleteKind.of(hiddenHere = true, addedHere = false, listedBySchool = dataCache.schoolLists(semester, courseNo))
        api.deleteCourseAnswered(CourseUploadPayload.key(semester, courseNo), signIn, hiddenAt = null, kind = kind)
    }

    override suspend fun patchStampedOverride(
        semester: String,
        courseNo: String,
        moodleId: String,
        colorHex: String?,
        name: String?,
        locale: String?,
    ) {
        ensureSends()
        // A name waits for the names re-enable check: sent now, it would take the server's name
        // before the student chose which to keep. Read right before the request, after what
        // suspends. A color keeps its own rule.
        val allowed = { name == null || policies.current.sends(SyncCategory.COURSE_NAMES) }
        val target = CourseRef(courseKey = "client:$semester:$courseNo")
        val answer = try {
            api.patchCourseOverrideAnswered(target, signIn, colorHex, name, locale, allowed)
        } catch (e: BackendException) {
            // A course the upload did not put back, a later hide elsewhere keeping it out, holds
            // no color or name to set; asked again, Use Local would meet the same.
            if (e.error != BackendError(HTTP_NOT_FOUND, COURSE_NOT_FOUND)) throw e
            return
        }
        // A name that did not go waits in the course outbox, as every name does: kept, held
        // while the names check is open, and sent once the student's answer lifts the hold. The
        // reset took the server's copy, and nothing else would put it back. Queued at the version
        // reserved before the name was read, so a rename made since stays the one waiting. With
        // the course's term and number, which a derived Moodle id names no cached course by, so
        // the outbox goes by the course's key, and tells a course still here from one gone.
        if (name != null && !answer.sent) {
            // Course sync can have gone off while the request was made, which the outbox's own
            // rule for names does not read: stopped, as every other step.
            ensureSends()
            val change = PendingCourseOverrides.Change(moodleId, customName = name, locale = locale, semester = semester, courseNo = courseNo)
            overrides.queue(listOf(change), namesVersion, signIn)
        }
    }

    override suspend fun currentChoices(semester: String, courseNo: String): LocalChoices {
        val hidden = CourseTombstoneKeys.isHidden(courseNo, semester, dataCache.loadDeletedCourseNos())
        // As Settings reads them for Use Local: none while name sync is off.
        val names = if (policies.current.syncs(SyncCategory.COURSE_NAMES)) dataCache.loadCourseCustomNames()[courseNo].orEmpty() else emptyMap()
        // Read last, so the color is the last thing read before its request.
        val course = dataCache.loadCourses(semester).firstOrNull { it.courseNo == courseNo }
        // Removed or hidden meanwhile, the course holds nothing to send, its names neither: they
        // are stored apart and outlast it, but its waiting overrides went with it.
        if (course == null || hidden) return LocalChoices(colorHex = null, names = emptyMap())
        return LocalChoices(course.customColorHex, names.filterValues { it.isNotEmpty() })
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val COURSE_NOT_FOUND = "course_not_found"
    }
}

/**
 * Use Local for courses, after course sync is switched back on, term by term
 * (tigerduck-app/tigerduck-app#235 on iOS, tigerduck-app-android#153):
 *
 * 1. Reset a term this phone caches courses for. Every tombstone in it
 *    becomes this phone's reset tombstone.
 * 2. Upload the term's cached courses, hand-typed ones included. The upload
 *    releases those tombstones.
 * 3. Delete every course hidden here in the term. A single-course delete
 *    leaves a tombstone that binds every device, this one included, so the
 *    roster a later refresh uploads cannot bring the course back. The
 *    backend writes it only for a row it has, so a hidden course the cache
 *    lacks goes up in step 2 as a stub named by its course number. A term
 *    with hidden courses and none cached gets steps 2 and 3 without the
 *    reset.
 *
 * [terms] maps each semester to its cached courses; [hiding] is the
 * deleted-course store, where a legacy bare entry hides its course in every
 * term. [customNames] (by course number, then locale; empty with name sync
 * off) go up again for every course of a term that was reset, which deletes
 * them on the server along with the course rows. A term whose portal rows
 * the cache has lost ([portalCached] false, see `DataCache.hasPortalCourses`)
 * is not reset: its hand-typed courses go up and its hidden ones are deleted. Not a full reset: that erases every term's tombstones and stamps
 * `courses_reset_at`, which this phone's next sync then took for a reset made
 * elsewhere, dropping its hidden and hand-typed courses.
 *
 * Each request is stamped
 * at its send, in this order, and the upload carries no colours: every custom
 * colour of a term Use Local uploads goes up after its deletes, beside its
 * names for a term reset, as an override of its own, so later than the reset
 * (X5). Its hand-typed courses go as adds made now.
 *
 * Every request is awaited and a failure is thrown, for the caller to ask
 * again. Once [isCurrent] turns false, as at a sign-out, nothing more is sent
 * and it throws [CancellationException]. [onReset] hears of each term reset
 * once its upload has landed.
 */
internal suspend fun keepLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    customNames: Map<String, Map<String, String>> = emptyMap(),
    portalCached: (semester: String) -> Boolean = { true },
    isCurrent: () -> Boolean = { true },
    onReset: (semester: String) -> Unit = {},
) {
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("signed out during Use Local")
    }
    for ((semester, courses) in terms) {
        val hiddenNos = CourseTombstoneKeys.hiddenIn(semester, hiding)
        val cachedNos = courses.mapTo(mutableSetOf()) { it.courseNo }
        val stubs = (hiddenNos - cachedNos).sorted().map { Course(courseNo = it, courseName = it) }
        if (courses.isEmpty() && stubs.isEmpty()) continue
        // Without the term's portal rows here, a reset would leave the server
        // with the hand-typed ones alone, and every other device without the
        // term's enrolled courses.
        val reset = courses.isNotEmpty() && portalCached(semester)
        if (reset) {
            ensureCurrent()
            backend.resetTerm(semester)
        }
        ensureCurrent()
        backend.uploadTerm(courses + stubs, semester)
        if (reset) onReset(semester)
        for (courseNo in hiddenNos.sorted()) {
            ensureCurrent()
            backend.hideCourse(semester, courseNo)
        }
        // A reset takes the term's override rows, names included, with its
        // course rows, so a reset term's names go up again. After the
        // deletes, so a name that fails to go up leaves no hidden course on
        // the server.
        val kept = courses.filter { it.courseNo !in hiddenNos }.sortedBy { it.courseNo }
        for (course in kept) {
            val names = if (reset) customNames[course.courseNo].orEmpty().toSortedMap().filterValues { it.isNotEmpty() } else emptyMap()
            // The id the upload stored the row under, as the backend
            // derives it; see CourseUploadPayload.
            val moodleId = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: "$semester${course.courseNo}"
            // No color rides the upload, a reset or none: every one goes as an override.
            val read = LocalChoices(course.customColorHex, names)
            sendStampedOverrides(semester, course.courseNo, moodleId, read, withNames = reset, backend, ::ensureCurrent)
        }
    }
}

/**
 * A course's color and, with [withNames] (a term reset), its names, after its term's upload,
 * which carries no colors: each an override of its own, stamped at its send,
 * so later than the reset Use Local sent before it (D10; X5). Each goes as the phone holds it right
 * before its request ([CourseSyncBackend.currentChoices]), not as Use Local [read] it: changed
 * meanwhile, it goes as it is now, as the student's own send may have gone before the reset that
 * took it; cleared, not at all. Sent as read, stamped later, it would win over the newer edit.
 */
private suspend fun sendStampedOverrides(
    semester: String,
    courseNo: String,
    moodleId: String,
    read: LocalChoices,
    withNames: Boolean,
    backend: CourseSyncBackend,
    ensureCurrent: () -> Unit,
) {
    suspend fun now(): LocalChoices = backend.currentChoices(semester, courseNo) ?: read
    ensureCurrent()
    now().colorHex?.let { backend.patchStampedOverride(semester, courseNo, moodleId, colorHex = it) }
    if (!withNames) return
    for (locale in now().names.keys.sorted()) {
        ensureCurrent()
        val name = now().names[locale]?.takeIf { it.isNotEmpty() } ?: continue
        backend.patchStampedOverride(semester, courseNo, moodleId, name = name, locale = locale)
    }
}
