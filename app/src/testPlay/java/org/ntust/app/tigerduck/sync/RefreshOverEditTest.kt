package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Race 5 (refactor-v4 D14, R5 to R8; spec §5.3): a write computed from what was read before an
 * `await` lands over what changed meanwhile. A school fetch and its lookups against the edits made
 * during them, over the real stores and queues. Made-up accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RefreshOverEditTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private lateinit var policies: SyncPolicies
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var outbox: Outbox

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
        policies = SyncPolicies(w.appPrefs)
        overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs, policies)
        outbox = Outbox(setOf(overrides))
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private val locale get() = AppLanguageManager.resolvedCourseApiLanguage(w.appPrefs.appLanguage)

    /** [courses] as the term, on disk and on Home, whose overrides step runs over a term it shows. */
    private suspend fun show(vararg courses: Course) {
        w.dataCache.saveCourses(courses.toList(), term, signIn)
        state.allCourses.value = courses.toList()
    }

    private suspend fun stored(): List<Course> = w.dataCache.loadCourses(term)

    // --- A school fetch over the edits made during its lookups (R5, R7) ---

    /** Course selection lists [CS1] and [CS2]; [duringLookups] runs once, as the first lookup goes out. */
    private fun termSync(courseLocale: () -> String = { locale }, duringLookups: suspend () -> Unit): TermCourseSync {
        var once = false
        val sources = object : TermCourseSync.Sources {
            override fun servesSelection(semester: String) = semester == term
            override suspend fun selection(studentId: String, password: String, fresh: Boolean) = listOf(CS1, CS2)
            override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()
            override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course {
                if (!once) {
                    once = true
                    duringLookups()
                }
                return Course(courseNo = courseNo, courseName = "School $courseNo", moodleIdNumber = "$semester$courseNo")
            }
        }
        return TermCourseSync(
            w.dataCache, sources,
            uploader = { _, _, _, _, _ -> },
            waitingAdds = { emptySet() },
            uploading = { block -> block() },
            courseLocale = courseLocale,
            uploadFailed = {},
        )
    }

    /** R5: the fetch folds onto the term as it stands when it commits, not as its lookups found it. */
    @Test
    fun `a fetch keeps a course added and a course deleted while its lookups ran`() = runBlocking {
        show(Course(courseNo = CS1, courseName = "School $CS1", moodleIdNumber = "$term$CS1"), Course(courseNo = CS2, courseName = "School $CS2", moodleIdNumber = "$term$CS2"))
        val sync = termSync {
            // The class table's add, then its delete, as they write: the hide first, then the list.
            val hand = Course(courseNo = HAND, courseName = "Added here", isManual = true)
            w.dataCache.addCourseBack(HAND, term, signIn)
            w.dataCache.saveCourses(stored() + hand, term, signIn)
            val deleted = stored().single { it.courseNo == CS2 }
            w.dataCache.hideDeletedCourse(CS2, term, deleted, signIn)
            w.dataCache.saveCourses(stored().filter { it.courseNo != CS2 }, term, signIn)
        }

        sync.sync(STUDENT, "made-up-password", term, signIn = signIn)

        assertEquals(setOf(CS1, HAND), stored().mapTo(mutableSetOf()) { it.courseNo })
    }

    /** R7: the names a fetch commits are those of the language set as it commits. */
    @Test
    fun `a fetch saves the custom names in the language set when it commits, not when it began`() = runBlocking {
        w.dataCache.saveCourseCustomNames(mapOf(CS1 to mapOf("zh" to "中文名", "en" to "English Name")), signIn)
        var language = "zh"
        val sync = termSync(courseLocale = { language }) { language = "en" }

        sync.sync(STUDENT, "made-up-password", term, signIn = signIn)

        assertEquals("English Name", stored().single { it.courseNo == CS1 }.customCourseName)
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val HAND = "XH0000008"
    }
}
