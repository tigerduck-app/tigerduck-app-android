package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusLedger
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusWriter
import org.ntust.app.tigerduck.data.moodle.SubmissionStatusAnswer
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.network.SubmissionStatusProbe
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Date

/** What the check does with the cache and the armed reminders around Moodle's answer. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AssignmentReminderCheckTest {

    private lateinit var context: Application
    private lateinit var dataCache: DataCache
    private lateinit var scheduler: AssignmentNotificationScheduler
    private lateinit var fixtures: DebugFixtureStore
    private lateinit var ledger: AssignmentStatusLedger
    private lateinit var writer: AssignmentStatusWriter
    private lateinit var check: AssignmentReminderCheck

    /** The monotonic clock the check reads before its request goes out. */
    private var monotonicNow = 1_000L

    private var storedToken: String? = "stored-token"

    /** The fake Moodle's answer to a status call, or its throw. */
    private var moodle: () -> String = { error("no answer set") }

    /** The assignid of every status call that went out. */
    private val asked = mutableListOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dataCache =
            DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        scheduler = AssignmentNotificationScheduler(context)
        // Answers in place of the network: the interceptor never calls proceed().
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val form = chain.request().body as FormBody
            asked += (0 until form.size).first { form.name(it) == "assignid" }.let(form::value)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(moodle().toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        fixtures = DebugFixtureStore(context)
        ledger = AssignmentStatusLedger { dataCache.userDataGeneration }
        // The pages' writer, which tells no one here: what the check itself tells is what is tested.
        writer = AssignmentStatusWriter(ledger, dataCache) {}
        check = AssignmentReminderCheck(
            dataCache, scheduler, SubmissionStatusProbe({ storedToken }, client), fixtures, AppPreferences(context),
            writer,
        ) { monotonicNow }
    }

    private fun dueInThreeDays(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(AppClock.nowMillis() + 3 * DAY_MS),
    )

    /** A status answer with the student's and the team's record, each left out when null. */
    private fun answer(own: String?, team: String? = null): String {
        fun record(status: String) = """{"status":"$status","timemodified":$SUBMITTED_AT_SECONDS}"""
        val records = listOfNotNull(
            own?.let { "\"submission\":${record(it)}," },
            team?.let { "\"teamsubmission\":${record(it)}," },
        ).joinToString("")
        return """{"lastattempt":{$records"submissionsenabled":true,"locked":false},"warnings":[]}"""
    }

    /** The assignment id of every armed reminder, one per alarm. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedIds(): List<String?> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map {
            shadowOf(it.operation).savedIntent
                .getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID)
        }

    private suspend fun armAll(vararg assignments: Assignment) {
        dataCache.saveAssignments(assignments.toList())
        scheduler.scheduleAll(
            dataCache.loadOutstandingAssignments(),
            setOf(AssignmentReminderOffset.HR24, AssignmentReminderOffset.HR2),
        )
    }

    @Test
    fun `a reminder still due shows and leaves the cache and the other reminders alone`() = runBlocking {
        // The draft's time, as the round that asked about it stored it.
        val assignment = dueInThreeDays("20977").copy(submittedAt = Date(SUBMITTED_AT_SECONDS * 1000))
        armAll(assignment)

        moodle = { answer(own = "new") }

        val verdict = check.verdict("20977", assignment.dueDate.time)

        assertEquals(ReminderVerdict.Show, verdict)
        assertEquals(listOf("20977"), asked)
        assertEquals(listOf(assignment), dataCache.loadAssignments())
        assertEquals(listOf("20977", "20977"), armedIds())
    }

    @Test
    fun `a reminder for an assignment never started shows and leaves the cache alone`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)

        moodle = { answer(own = null) }

        val verdict = check.verdict("20977", assignment.dueDate.time)

        assertEquals(ReminderVerdict.Show, verdict)
        assertEquals(listOf(assignment), dataCache.loadAssignments())
        assertEquals(listOf("20977", "20977"), armedIds())
    }

    @Test
    fun `a teammate's submission is recorded and the assignment's other reminders cancelled`() =
        runBlocking {
            val assignment = dueInThreeDays("20977")
            val other = dueInThreeDays("20978")
            armAll(assignment, other)

            moodle = { answer(own = null, team = "submitted") }

            val verdict = check.verdict("20977", assignment.dueDate.time)

            assertEquals(ReminderVerdict.Drop(ReminderDropReason.SUBMITTED_ON_MOODLE), verdict)
            assertEquals(
                listOf(
                    assignment.copy(isCompleted = true, submittedAt = Date(SUBMITTED_AT_SECONDS * 1000)),
                    other,
                ),
                dataCache.loadAssignments(),
            )
            // Armed again from the cache, for the offsets the user chose: the other assignment's alone.
            assertEquals(setOf("20978"), armedIds().toSet())
        }

    @Test
    fun `what the cache rules out asks Moodle nothing`() = runBlocking {
        val marked = dueInThreeDays("20977")
        val ofIgnoredCourse = dueInThreeDays("20978").copy(courseNo = "IGNORED")
        val moved = dueInThreeDays("20979")
        dataCache.saveAssignments(listOf(marked, ofIgnoredCourse, moved))
        dataCache.saveMarkedCompletedAssignments(setOf("20977"))
        dataCache.saveIgnoredCourses(setOf(CourseIgnores.key(dataCache.currentSemesterCode(), "IGNORED")))
        moodle = { answer(own = "new") }

        assertEquals(
            listOf(
                ReminderVerdict.Drop(ReminderDropReason.MARKED_DONE),
                ReminderVerdict.Drop(ReminderDropReason.COURSE_IGNORED),
                ReminderVerdict.Drop(ReminderDropReason.DUE_DATE_CHANGED),
                ReminderVerdict.Drop(ReminderDropReason.NOT_IN_CACHE),
            ),
            listOf(
                check.verdict("20977", marked.dueDate.time),
                check.verdict("20978", ofIgnoredCourse.dueDate.time),
                check.verdict("20979", moved.dueDate.time - DAY_MS),
                check.verdict("20980", null),
            ),
        )
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a change to the cache while Moodle answered drops the reminder`() = runBlocking {
        val marked = dueInThreeDays("20977")
        val ofIgnoredCourse = dueInThreeDays("20978").copy(courseNo = "IGNORED")
        val moved = dueInThreeDays("20979")
        dataCache.saveAssignments(listOf(marked, ofIgnoredCourse, moved))
        fun changingMeanwhile(change: suspend () -> Unit): () -> String = {
            runBlocking { change() }
            answer(own = "new")
        }

        moodle = changingMeanwhile { dataCache.saveMarkedCompletedAssignments(setOf("20977")) }
        val afterMark = check.verdict("20977", marked.dueDate.time)
        moodle = changingMeanwhile {
            dataCache.saveIgnoredCourses(setOf(CourseIgnores.key(dataCache.currentSemesterCode(), "IGNORED")))
        }
        val afterIgnore = check.verdict("20978", ofIgnoredCourse.dueDate.time)
        moodle = changingMeanwhile {
            dataCache.updateAssignments { all ->
                all.map { if (it == moved) it.copy(dueDate = Date(it.dueDate.time + DAY_MS)) else it }
            }
        }
        val afterMove = check.verdict("20979", moved.dueDate.time)

        assertEquals(
            listOf(
                ReminderVerdict.Drop(ReminderDropReason.MARKED_DONE),
                ReminderVerdict.Drop(ReminderDropReason.COURSE_IGNORED),
                ReminderVerdict.Drop(ReminderDropReason.DUE_DATE_CHANGED),
            ),
            listOf(afterMark, afterIgnore, afterMove),
        )
    }

    @Test
    fun `an id Moodle does not number is not asked about`() = runBlocking {
        val manual = dueInThreeDays("manual-1")
        dataCache.saveAssignments(listOf(manual))

        moodle = { answer(own = "new") }

        val verdict = check.verdict("manual-1", manual.dueDate.time)

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.NO_ANSWER), verdict)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `an answer that outlived a sign-out leaves the next account's cache and reminders alone`() =
        runBlocking {
            val assignment = dueInThreeDays("20977")
            dataCache.saveAssignments(listOf(assignment))
            moodle = {
                runBlocking {
                    dataCache.clearAllUserData()
                    // The next account, in the same course, with the assignment still to do.
                    armAll(assignment)
                }
                answer(own = "submitted")
            }

            val verdict = check.verdict("20977", assignment.dueDate.time)

            // The departed account's "handed in" decides nothing for the next one.
            assertEquals(ReminderVerdict.Drop(ReminderDropReason.SUPERSEDED), verdict)
            assertEquals(listOf(assignment), dataCache.loadAssignments())
            assertEquals(listOf("20977", "20977"), armedIds())
        }

    @Test
    fun `an answer that outlived a sign-out, with no account since, finds nothing to remind of`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        moodle = {
            runBlocking { dataCache.clearAllUserData() }
            answer(own = "new")
        }

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.NOT_IN_CACHE), check.verdict("20977", assignment.dueDate.time))
    }

    @Test
    fun `the demo account's reminder answers from its own data and asks Moodle nothing`() = runBlocking {
        // The demo account has no Moodle token: its assignments are the bundled file's.
        fixtures.demoMode = true
        storedToken = null
        val due = dueInThreeDays("900001")
        val handedIn = dueInThreeDays("900003").copy(isCompleted = true)
        dataCache.saveAssignments(listOf(due, handedIn))

        assertEquals(ReminderVerdict.Show, check.verdict("900001", due.dueDate.time))
        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.SUBMITTED_IN_CACHE),
            check.verdict("900003", handedIn.dueDate.time),
        )
        assertEquals(emptyList<String>(), asked)
    }

    // --- no answer from Moodle --------------------------------------------

    @Test
    fun `a rejected token drops the reminder`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        dataCache.saveAssignments(listOf(assignment))
        moodle = {
            """{"exception":"moodle_exception","errorcode":"invalidtoken",""" +
                """"message":"Invalid token - token not found"}"""
        }

        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.TOKEN_REJECTED),
            check.verdict("20977", assignment.dueDate.time),
        )
    }

    @Test
    fun `an expired token's first answer, an access exception, drops the reminder as rejected`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        dataCache.saveAssignments(listOf(assignment))
        moodle = {
            """{"exception":"webservice_access_exception","errorcode":"accessexception",""" +
                """"message":"Access control exception"}"""
        }

        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.TOKEN_REJECTED),
            check.verdict("20977", assignment.dueDate.time),
        )
    }

    @Test
    fun `no stored token drops the reminder without a request`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        dataCache.saveAssignments(listOf(assignment))
        storedToken = null

        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.NO_TOKEN),
            check.verdict("20977", assignment.dueDate.time),
        )
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `no connection drops the reminder`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        dataCache.saveAssignments(listOf(assignment))
        moodle = { throw IOException("unreachable") }

        assertEquals(
            ReminderVerdict.Drop(ReminderDropReason.NO_ANSWER),
            check.verdict("20977", assignment.dueDate.time),
        )
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val MINUTE_MS = 60 * 1000L
        const val SUBMITTED_AT_SECONDS = 1_760_400_000L
    }

    @Test
    fun `a reminder armed for an extension shows, not dropped as a changed or extended due date`() = runBlocking {
        // Whole seconds, as Moodle gives it.
        val extension = (AppClock.nowMillis() + 5 * DAY_MS) / 1000 * 1000
        val assignment = dueInThreeDays("20977").copy(extensionDueDate = Date(extension))
        armAll(assignment)

        moodle = {
            """{"lastattempt":{"submissionsenabled":true,"locked":false,"extensionduedate":${extension / 1000}},"warnings":[]}"""
        }

        assertEquals(ReminderVerdict.Show, check.verdict("20977", extension))
    }

    /** A status answer with nothing handed in and the student's extension, epoch milliseconds. */
    private fun extendedTo(ms: Long?) =
        """{"lastattempt":{"submissionsenabled":true,"locked":false,"extensionduedate":${(ms ?: 0L) / 1000}},"warnings":[]}"""

    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedDueAts(): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map {
            shadowOf(it.operation).savedIntent.getLongExtra(AssignmentNotificationReceiver.EXTRA_DUE_AT, 0L)
        }

    @Test
    fun `an extension the reminder learns of is kept, and the reminders armed again for it`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        val extension = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000

        moodle = { extendedTo(extension) }
        val verdict = check.verdict("20977", assignment.dueDate.time)

        assertTrue("armed for the date before", verdict is ReminderVerdict.Drop)
        assertEquals(Date(extension), dataCache.loadAssignments().single().extensionDueDate)
        assertTrue(armedDueAts().isNotEmpty())
        assertTrue("every reminder now for the extension", armedDueAts().all { it == extension })
    }

    @Test
    fun `an extension taken back leaves the cache, and the reminder armed for it does not show`() = runBlocking {
        val extension = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000
        val assignment = dueInThreeDays("20977").copy(extensionDueDate = Date(extension))
        armAll(assignment)

        moodle = { extendedTo(null) }
        val verdict = check.verdict("20977", extension)

        assertTrue(verdict is ReminderVerdict.Drop)
        assertEquals(null, dataCache.loadAssignments().single().extensionDueDate)
        assertTrue("armed again for the assignment's own date", armedDueAts().all { it == assignment.dueDate.time })
    }

    @Test
    fun `a submission and an extension found together are both kept`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        val extension = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000

        moodle = {
            """{"lastattempt":{"submission":{"status":"submitted","timemodified":$SUBMITTED_AT_SECONDS},""" +
                """"submissionsenabled":true,"locked":false,"extensionduedate":${extension / 1000}},"warnings":[]}"""
        }
        val verdict = check.verdict("20977", assignment.dueDate.time)

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.SUBMITTED_ON_MOODLE), verdict)
        assertEquals(
            // Handed in within the extension: on time, which the next round, skipping it, never re-asks.
            assignment.copy(isCompleted = true, submittedAt = Date(SUBMITTED_AT_SECONDS * 1000), extensionDueDate = Date(extension)),
            dataCache.loadAssignments().single(),
        )
        assertEquals(emptyList<String?>(), armedIds())
    }

    @Test
    fun `a page's answer asked for after the reminder's call is not overwritten by it`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        val fromPage = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000
        val fromReminder = (AppClock.nowMillis() + 5 * DAY_MS) / 1000 * 1000

        moodle = {
            // The assignment's page asks after the reminder's call went out, and answers first.
            runBlocking {
                writer.take(
                    SubmissionStatusAnswer("20977", false, null, monotonicNow + 1, dataCache.userDataGeneration, fromPage),
                )
            }
            extendedTo(fromReminder)
        }
        check.verdict("20977", assignment.dueDate.time)

        assertEquals(Date(fromPage), dataCache.loadAssignments().single().extensionDueDate)
    }

    @Test
    fun `a round asked for before the reminder's call takes what the call answered`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        val extension = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000
        val generation = dataCache.userDataGeneration
        val roundAskedAt = monotonicNow - 1

        moodle = { extendedTo(extension) }
        check.verdict("20977", assignment.dueDate.time)
        // The round writes after the call, with what Moodle told it before: no extension.
        val written = ledger.reconcile(
            listOf(assignment),
            mapOf("20977" to SubmissionStatusAnswer("20977", false, null, roundAskedAt, generation)),
            generation,
        )

        assertEquals(Date(extension), written.single().extensionDueDate)
    }

    @Test
    fun `a reminder armed without its date does not show once an extension moves it`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)
        val extension = (AppClock.nowMillis() + 6 * DAY_MS) / 1000 * 1000

        moodle = { extendedTo(extension) }

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.DUE_DATE_CHANGED), check.verdict("20977", null))
    }

    @Test
    fun `an older call's answer the page has since overtaken does not decide the reminder`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)

        moodle = {
            // The page asks after the reminder's call went out and hears the work was reopened, then
            // the call's older answer arrives: handed in.
            runBlocking {
                writer.take(SubmissionStatusAnswer("20977", false, null, monotonicNow + 1, dataCache.userDataGeneration))
            }
            answer(own = "submitted")
        }
        val verdict = check.verdict("20977", assignment.dueDate.time)

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.SUPERSEDED), verdict)
        assertEquals(false, dataCache.loadAssignments().single().isCompleted)
    }

    /** The offsets of [assignmentId]'s armed reminders. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedOffsetsOf(assignmentId: String): List<String?> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .filter { it.getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID) == assignmentId }
            .map { it.getStringExtra(AssignmentNotificationReceiver.EXTRA_OFFSET) }

    /** The due date each of [assignmentId]'s armed reminders carries. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedDueAtsOf(assignmentId: String): List<Long> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .filter { it.getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID) == assignmentId }
            .map { it.getLongExtra(AssignmentNotificationReceiver.EXTRA_DUE_AT, 0L) }

    @Test
    fun `learning one assignment's extension leaves another's reminder that is due but not delivered`() = runBlocking {
        val start = AppClock.nowMillis()
        val extended = dueInThreeDays("20977")
        // Its two-hour reminder is armed for an hour from now; its 24-hour one is long past.
        val other = dueInThreeDays("20978").copy(dueDate = Date(start + 3 * HOUR_MS))
        armAll(extended, other)
        assertEquals(listOf(AssignmentReminderOffset.HR2.rawValue), armedOffsetsOf("20978"))
        val extension = (start + 6 * DAY_MS) / 1000 * 1000
        try {
            // Half an hour past the other's reminder, which Doze or an inexact alarm has not
            // delivered yet: it still waits in the alarm manager.
            AppClock.setOverride(ClockOverride(start + 90 * MINUTE_MS, frozen = true, savedAtRealMillis = 0L))
            moodle = { extendedTo(extension) }

            check.verdict("20977", extended.dueDate.time)

            assertTrue(
                "the other assignment's reminder waiting to go off",
                AssignmentReminderOffset.HR2.rawValue in armedOffsetsOf("20978"),
            )
            assertTrue("this one's reminders armed again", armedOffsetsOf("20977").isNotEmpty())
            assertTrue("this one's reminders moved to the extension", armedDueAtsOf("20977").all { it == extension })
        } finally {
            AppClock.setOverride(null)
        }
    }
}

