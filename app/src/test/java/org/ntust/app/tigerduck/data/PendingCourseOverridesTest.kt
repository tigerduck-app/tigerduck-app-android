@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.Course
import java.io.File
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A colour or a name set on the class table whose PATCH failed ([PendingCourseOverrides]), over
 * real files (tigerduck-app-android#380). It used to go up once: made offline, the change stayed
 * on this phone alone, never to reach another device, and lost at sign-out.
 */
class PendingCourseOverridesTest {

    private lateinit var cache: DataCache
    private lateinit var overrides: PendingCourseOverrides

    private data class Patch(
        val moodleId: String,
        val colorHex: String?,
        val customName: String?,
        val locale: String?,
        val account: Int = 1,
    )

    private val patches = mutableListOf<Patch>()

    // The edit time each PATCH carried as `hlc` (before the clock's microsecond scale), by PATCH.
    private val stamps = mutableListOf<Long?>()
    private lateinit var context: JvmContext
    private var answer: suspend () -> Boolean = { true }

    // The switches, by kind (true for colours): whether changes are this phone's own or sync at
    // all, and whether they go out now or wait for a re-enable check's answer.
    private var records: (Boolean) -> Boolean = { true }
    private var sends: (Boolean) -> Boolean = { true }

    // AuthTokenManager.signInGeneration, and what happens before the request's header is made.
    private var signIn = 1
    private var beforeHeader: suspend () -> Unit = {}

    private var now = 1_000L

    private val term = "1151"
    private val calculus = "1151MA1010002"

    @Before
    fun setUp() {
        context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        // As PushApiClient.patchCourseOverride: pinned to a sign-in, the header is that account's or
        // none, and the hold is read once more after the header is made, right before the request.
        overrides = PendingCourseOverrides(
            cache,
            { moodleId, colorHex, customName, locale, pinned, stillAllowed, madeAtMs ->
                beforeHeader()
                if (pinned != signIn) throw NotSignedInException()
                if (!stillAllowed()) return@PendingCourseOverrides false
                patches += Patch(moodleId, colorHex, customName, locale, account = signIn)
                stamps += madeAtMs
                answer()
            },
            records = { records(it) },
            sends = { sends(it) },
            signInGeneration = { signIn },
            clock = { now++ },
        )
    }

    @Test
    fun `an edit sent after a newer one carries the time it was made`() = runTest {
        val physics = "1151PH1010002"
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        answer = { true }
        overrides.send(physics, customName = "Phys", locale = "en")
        assertEquals("the row keeps the time it was made", stamps.first(), cache.loadPendingCourseOverrides().single().madeAtMs)
        overrides.retry()

        // Calculus's colour (T1) failed, physics's name (T2) landed, and the retry sent T1 again.
        assertEquals(listOf(calculus, physics, calculus), patches.map { it.moodleId })
        val (first, second, retried) = stamps
        assertTrue("T1 is kept: $stamps", first != null && first < second!!)
        assertEquals(first, retried)
    }

    @Test
    fun `a row stored by the build before has no time, and goes out without one`() = runTest {
        File(context.filesDir, "TigerDuckData/pending_course_overrides.json").writeText(
            """[{"moodleId":"$calculus","colorHex":"#FF0000","changedAtMs":1000,"attempts":0,"seq":5}]""",
        )
        assertEquals(null, cache.loadPendingCourseOverrides().single().madeAtMs)

        overrides.retry()

        assertEquals(listOf<Long?>(null), stamps)
        assertEquals(1, patches.size)
    }

    private fun refused(code: Int): suspend () -> Boolean = { throw PushApiException("patchCourseOverride failed: HTTP $code", code) }

    @Test
    fun `a sync's forget of a course it took out keeps a change made since it began to`() = runTest {
        sends = { false }
        overrides.send(calculus, colorHex = "#FF0000")
        // The sync's commit reserves a version before it writes the term's courses; the course is
        // put back and named meanwhile, before its forget.
        val before = overrides.reserveVersion()
        overrides.send(calculus, customName = "Calc", locale = "en")

        overrides.forget(listOf(calculus), madeBefore = before)

        assertEquals(listOf<String?>("Calc"), cache.loadPendingCourseOverrides().map { it.customName })
    }

    @Test
    fun `a colour whose PATCH fails is kept, and the next sync sends it again and lets it go once it lands`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())
        assertEquals(listOf("#FF0000"), cache.loadPendingCourseOverrides().map { it.colorHex })

        answer = { true }
        overrides.retry()

        assertEquals(Patch(calculus, "#FF0000", null, null), patches.last())
        assertFalse(overrides.hasPending())
        assertEquals(emptyList<PendingCourseOverride>(), cache.loadPendingCourseOverrides())
    }

    @Test
    fun `a name whose PATCH fails on the server waits for the next sync, however often`() = runTest {
        answer = refused(500)
        overrides.send(calculus, customName = "Calc", locale = "en")
        repeat(6) { overrides.retry() }

        assertEquals(7, patches.size)
        assertEquals(listOf("Calc"), cache.loadPendingCourseOverrides().map { it.customName })
    }

    @Test
    fun `a later edit of the same colour or name replaces the one waiting, and the others wait beside it`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")
        overrides.send(calculus, customName = "微積分", locale = "zh-TW")
        overrides.send(calculus, colorHex = "#00FF00")
        overrides.send(calculus, customName = "", locale = "en")

        val waiting = cache.loadPendingCourseOverrides()
        assertEquals(waiting.toString(), 3, waiting.size)
        assertEquals("#00FF00", waiting.single { it.colorHex != null }.colorHex)
        assertEquals("", waiting.single { it.locale == "en" }.customName)
        assertEquals("微積分", waiting.single { it.locale == "zh-TW" }.customName)

        // The older colour and the older name never go up.
        patches.clear()
        answer = { true }
        overrides.retry()
        assertEquals(
            setOf(
                Patch(calculus, "#00FF00", null, null),
                Patch(calculus, null, "", "en"),
                Patch(calculus, null, "微積分", "zh-TW"),
            ),
            patches.toSet(),
        )
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a PATCH that lands after a newer edit of the same colour leaves the newer one waiting`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")

        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            true
        }
        val retry = launch { overrides.retry() }
        sending.await()
        // Picked again while the red was on its way, and offline again by the time this one goes.
        answer = { throw IOException("offline") }
        val again = launch { overrides.send(calculus, colorHex = "#0000FF") }
        runCurrent()
        landed.complete(Unit)
        retry.join()
        again.join()

        assertEquals(listOf("#0000FF"), cache.loadPendingCourseOverrides().map { it.colorHex })
    }

    @Test
    fun `an edit made while its switch is off is this phone's own, and replaces one waiting`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")

        // Colours stop syncing; a new pick goes nowhere, and the red waiting would go up over it later.
        records = { color -> !color }
        overrides.send(calculus, colorHex = "#0000FF")

        assertEquals(2, patches.size)
        assertEquals(listOf("Calc"), cache.loadPendingCourseOverrides().map { it.customName })
    }

    @Test
    fun `a change waiting when its switch goes off, or while its re-enable check is pending, is kept and not sent`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")

        // The colours re-enable check waits for the user: the name goes, the colour waits.
        sends = { color -> !color }
        answer = { true }
        patches.clear()
        overrides.retry()
        assertEquals(listOf(Patch(calculus, null, "Calc", "en")), patches)
        assertEquals(listOf("#FF0000"), cache.loadPendingCourseOverrides().map { it.colorHex })

        sends = { true }
        overrides.retry()
        assertEquals(Patch(calculus, "#FF0000", null, null), patches.last())
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a hold put on the kind while its PATCH is being prepared stops the request`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        patches.clear()

        // Sync off and on again while the request's header was being made: the colours re-enable
        // check is pending by the time the request would go, and the user's answer decides the red.
        answer = { true }
        beforeHeader = {
            beforeHeader = {}
            sends = { color -> !color }
        }
        overrides.retry()

        assertEquals(emptyList<Patch>(), patches)
        assertEquals(listOf("#FF0000"), cache.loadPendingCourseOverrides().map { it.colorHex })
    }

    @Test
    fun `a reset's colours are versioned as it is queued, so a pick made while they go up outbids its own`() = runTest {
        val physics = "1151PH1010003"
        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            true
        }
        // A reset of every colour: queued whole, then sent one by one; the first request hangs.
        val reset = launch {
            overrides.queue(
                listOf(
                    PendingCourseOverrides.Change(calculus, colorHex = "#FF0000"),
                    PendingCourseOverrides.Change(physics, colorHex = "#00FF00"),
                ),
                overrides.reserveVersion(),
            )
            overrides.retry()
        }
        sending.await()
        // Picked a colour for the second course meanwhile, offline again by the time it goes.
        answer = { throw IOException("offline") }
        val pick = launch { overrides.send(physics, colorHex = "#0000FF") }
        runCurrent()
        landed.complete(Unit)
        reset.join()
        pick.join()

        assertEquals(listOf(physics to "#0000FF"), cache.loadPendingCourseOverrides().map { it.moodleId to it.colorHex })
        assertEquals("the reset's older green never went up", emptyList<Patch>(), patches.filter { it.colorHex == "#00FF00" })
    }

    @Test
    fun `a batch's version is reserved before its snapshot is read, so an edit made while it is read outbids it`() = runTest {
        val physics = "1151PH1010003"
        answer = { throw IOException("offline") }
        // Use Local reserves, then reads the cached colours; the user picks blue for physics while
        // it reads, and the batch, built from colours that predate the pick, reaches the outbox after it.
        val version = overrides.reserveVersion()
        overrides.send(physics, colorHex = "#0000FF")
        overrides.queue(
            listOf(
                PendingCourseOverrides.Change(calculus, colorHex = "#FF0000"),
                PendingCourseOverrides.Change(physics, colorHex = "#00FF00"),
            ),
            version,
        )

        assertEquals(
            mapOf(calculus to "#FF0000", physics to "#0000FF"),
            cache.loadPendingCourseOverrides().associate { it.moodleId to it.colorHex },
        )
    }

    @Test
    fun `a batch reserved before an edit that has since landed does not put the older colour back`() = runTest {
        // The reset reserves and reads its snapshot; the pick made meanwhile goes up at once and
        // leaves the file, so nothing waiting says the reset's red for the course is the older.
        val version = overrides.reserveVersion()
        answer = { true }
        overrides.send(calculus, colorHex = "#0000FF")
        assertFalse(overrides.hasPending())

        overrides.queue(listOf(PendingCourseOverrides.Change(calculus, colorHex = "#FF0000")), version)
        patches.clear()
        overrides.retry()

        assertFalse("the reset's older red is not queued over the pick that landed", overrides.hasPending())
        assertEquals(emptyList<Patch>(), patches)
    }

    @Test
    fun `after a restart a change still outbids every change waiting, whatever the clock says`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        val before = cache.loadPendingCourseOverrides().single().seq

        // A new process over the same file, with the clock set back: versions are compared only
        // against the changes still waiting, so this one must still count as the newer.
        now = 1L
        val restarted = PendingCourseOverrides(
            cache,
            { _, _, _, _, _, _, _ -> throw IOException("offline") },
            records = { true },
            sends = { true },
            signInGeneration = { signIn },
            clock = { now++ },
        )
        restarted.send(calculus, colorHex = "#0000FF")

        val after = cache.loadPendingCourseOverrides().single()
        assertEquals("#0000FF", after.colorHex)
        assertTrue("${after.seq} > $before", after.seq > before)
    }

    @Test
    fun `Use Server drops the category's waiting changes, and Use Local keeps them`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")

        overrides.adoptServer(listOf("course_colors", "assignments"))

        assertEquals(listOf("Calc"), cache.loadPendingCourseOverrides().map { it.customName })
        assertFalse(PendingCourseOverride.colorKey(calculus) in overrides.held(0L))
    }

    @Test
    fun `a change the backend refuses five times is let go`() = runTest {
        answer = refused(404)
        overrides.send(calculus, colorHex = "#FF0000")
        repeat(3) { overrides.retry() }
        assertEquals(4, patches.size)
        assertTrue("kept through four refusals", overrides.hasPending())

        overrides.retry()
        assertEquals(5, patches.size)
        assertFalse("let go on the fifth", overrides.hasPending())
    }

    @Test
    fun `a 404 for a course no longer on this phone lets its change go at once`() = runTest {
        val course = Course(courseNo = "MA1010002", courseName = "Calculus", moodleIdNumber = calculus)
        cache.saveCourses(listOf(course), term)
        answer = refused(404)
        overrides.send(calculus, colorHex = "#FF0000", semester = term, courseNo = course.courseNo)
        assertTrue("still on this phone: the row may yet be uploaded", overrides.hasPending())

        // Deleted on another device, and gone from here with the sync's prune.
        cache.saveCourses(emptyList(), term)
        overrides.retry()
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `nothing is kept for the next account`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())

        cache.clearAllUserData()

        assertFalse(overrides.hasPending())
        assertEquals(emptyList<PendingCourseOverride>(), cache.loadPendingCourseOverrides())
    }

    @Test
    fun `an edit read as one account is not sent as the next one`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue(overrides.hasPending())

        // The account signs out, and another signs in, before the retry's request has its header.
        answer = { true }
        beforeHeader = {
            beforeHeader = {}
            cache.clearAllUserData()
            signIn++
        }
        overrides.retry()

        assertEquals(emptyList<Patch>(), patches.filter { it.account == 2 })
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a snapshot fetched before an edit, or before its PATCH landed, must not take the edit back`() = runTest {
        val colour = PendingCourseOverride.colorKey(calculus)
        answer = { throw IOException("offline") }
        val fetchedBefore = now
        overrides.send(calculus, colorHex = "#FF0000")
        assertTrue("held while it waits", colour in overrides.held(fetchedBefore))
        assertTrue("held against a snapshot fetched after the edit too", colour in overrides.held(now))

        answer = { true }
        overrides.retry()
        val landedAt = now
        assertTrue("held against a snapshot fetched before it landed", colour in overrides.held(fetchedBefore))
        assertFalse("but not against one fetched after", colour in overrides.held(landedAt))
    }

    @Test
    fun `a deleted course's waiting colour and name go with it`() = runTest {
        answer = { throw IOException("offline") }
        overrides.send(calculus, colorHex = "#FF0000")
        overrides.send(calculus, customName = "Calc", locale = "en")
        overrides.send("1151CS1010001", colorHex = "#00FF00")

        overrides.forget(listOf(calculus))

        assertEquals(listOf("1151CS1010001"), cache.loadPendingCourseOverrides().map { it.moodleId })
    }
}
