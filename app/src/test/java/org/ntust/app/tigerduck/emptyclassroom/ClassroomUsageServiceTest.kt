package org.ntust.app.tigerduck.emptyclassroom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.emptyclassroom.ClassroomPageFixtures as Pages
import org.ntust.app.tigerduck.network.lockedUnlessStale
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * The cour01 client against made-up pages: the sign-in, the campus and date postbacks, the pages
 * of a day, the kept days, and what a sign-out leaves behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassroomUsageServiceTest {

    private class Harness(var nowMs: Long = 1_000_000L, demo: (LocalDate) -> CampusUsage? = { null }) {
        val transport = FakeCour01Transport()
        var signIns = 0
        var signInAttempts = 0
        var drops = 0

        /** The SSO login's lock, which a test holds to stand for another login in progress. */
        val ssoLock = Mutex()
        val service = ClassroomUsageService(
            transport = transport,
            // Through the same lock gate as SsoLoginService's: a login no longer wanted is refused.
            signIn = { _, isStillWanted ->
                signInAttempts++
                ssoLock.lockedUnlessStale(isStillWanted, stale = false) {
                    signIns++
                    true
                }
            },
            dropSessionCookies = { drops++ },
            demo = demo,
            nowMs = { nowMs },
            parsing = Dispatchers.Unconfined,
        )

        fun serve(html: String?, gate: CompletableDeferred<Unit>? = null) = transport.serve(html, gate)

        /** The query page, the campus postback, and both pages of 2026-10-14 after a date postback. */
        fun serveFirstDay() {
            serve(Pages.page(rows = null, campus = ""))
            serve(Pages.page(rows = listOf(Pages.row("TR-212", "cc....cc..b...")), viewState = "campus"))
            serve(
                Pages.page(
                    rows = listOf(Pages.row("E1-306", "cccc.........."), Pages.row("IB-508", "..............")),
                    pager = Pages.firstOfTwoPages,
                    viewState = "first",
                ),
            )
            serve(Pages.page(rows = listOf(Pages.row("TR-617", "cc............")), pager = Pages.secondOfTwoPages, viewState = "second"))
        }

        val methods get() = transport.sent.map { it.method }

        fun form(index: Int) = transport.sent[index].fields

        suspend fun usage(day: LocalDate, isToday: Boolean = false, force: Boolean = false) =
            service.usage(day, isToday, force, credentials)
    }

    private companion object {
        val credentials = ClassroomUsageService.Credentials("B00000000", "not-a-password")
        val wednesday: LocalDate = LocalDate.of(2026, 10, 14)
        val thursday: LocalDate = LocalDate.of(2026, 10, 15)
    }

    private suspend inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun `a day is read from the campus page through every page of the grid, then kept`() = runTest {
        val harness = Harness()
        harness.serveFirstDay()

        val usage = harness.usage(wednesday).usage

        assertEquals(listOf("E1-306", "IB-508", "TR-617"), usage.rooms.map { it.room })
        assertEquals("研揚大樓", usage.buildingNames["TR"])
        assertEquals(listOf("GET", "POST", "POST", "POST"), harness.methods)
        assertEquals(0, harness.signIns)
        assertEquals("DropdownCampusListID", harness.form(1)["__EVENTTARGET"])
        assertEquals("HQ", harness.form(1)["DropdownCampusListID"])
        assertEquals("date_cal", harness.form(2)["__EVENTTARGET"])
        assertEquals("9783", harness.form(2)["__EVENTARGUMENT"])
        assertEquals("campus", harness.form(2)["__VIEWSTATE"])
        assertEquals("showinfo_grd\$_ctl54\$_ctl1", harness.form(3)["__EVENTTARGET"])
        assertEquals("first", harness.form(3)["__VIEWSTATE"])
        assertTrue("query_btn" !in harness.form(2))

        assertEquals(usage, harness.usage(wednesday).usage)
        assertEquals(4, harness.transport.sent.size)
        assertEquals(usage, harness.service.keptUsage(wednesday)?.usage)
    }

    @Test
    fun `today is read by posting its date, not off the grid the campus page shows`() = runTest {
        // Nothing on the campus page is known to name its grid's day, which the site may keep
        // from an earlier read, so a read of another day and then today could show the other
        // day's grid as today's (tigerduck-app#475).
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-212", "cc....cc..b...")), viewState = "campus"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), viewState = "today"))

        val usage = harness.usage(wednesday, isToday = true).usage

        assertEquals(listOf("TR-209"), usage.rooms.map { it.room })
        assertEquals(listOf("GET", "POST", "POST"), harness.methods)
        assertEquals("date_cal", harness.form(2)["__EVENTTARGET"])
        assertEquals("9783", harness.form(2)["__EVENTARGUMENT"])
        assertEquals("campus", harness.form(2)["__VIEWSTATE"])
    }

    @Test
    fun `every read starts from a new campus page, so no page of the site outlives its read`() = runTest {
        val harness = Harness()
        harness.serveFirstDay()
        harness.usage(wednesday)
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus again"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), viewState = "thursday"))

        val usage = harness.usage(thursday).usage

        assertEquals(listOf("TR-209"), usage.rooms.map { it.room })
        assertEquals(listOf("GET", "POST", "POST"), harness.methods.drop(4))
        assertEquals("9784", harness.form(6)["__EVENTARGUMENT"])
        assertEquals("campus again", harness.form(6)["__VIEWSTATE"])
        assertEquals(0, harness.signIns)
    }

    @Test
    fun `reading the same day again posts it from a new campus page, not from the page that shows it`() = runTest {
        // The calendar raises no change for the date it already has selected, so posting it again
        // could answer with the grid kept from the read before (tigerduck-app#513).
        val harness = Harness()
        harness.serveFirstDay()
        harness.usage(wednesday)
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus again"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), viewState = "again"))

        val usage = harness.usage(wednesday, force = true).usage

        assertEquals(listOf("TR-209"), usage.rooms.map { it.room })
        assertEquals(listOf("GET", "POST", "POST"), harness.methods.drop(4))
        assertEquals("campus again", harness.form(6)["__VIEWSTATE"])
    }

    @Test
    fun `a date past the calendar's month steps to the next month first`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        harness.serve(Pages.page(calendar = Pages.november, viewState = "november"))
        harness.serve(Pages.page(rows = listOf(Pages.row("IB-508", "..............")), calendar = Pages.november))

        val usage = harness.usage(LocalDate.of(2026, 11, 12)).usage

        assertEquals(listOf("IB-508"), usage.rooms.map { it.room })
        assertEquals("V9801", harness.form(2)["__EVENTARGUMENT"])
        assertEquals("9812", harness.form(3)["__EVENTARGUMENT"])
        assertEquals("november", harness.form(3)["__VIEWSTATE"])
    }

    @Test
    fun `a date the calendar never reaches is unavailable`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        repeat(3) { harness.serve(Pages.page(viewState = "step")) }

        assertThrows<ClassroomUsageError.DateUnavailable> { harness.usage(LocalDate.of(2027, 3, 1)) }
        assertEquals(5, harness.transport.sent.size)
    }

    @Test
    fun `a page without the grid signs in again once and starts over`() = runTest {
        val harness = Harness()
        // The session lapses between the query page and the campus postback.
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.SIGN_IN_PAGE)
        harness.serve(null)
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), viewState = "thursday"))

        val usage = harness.usage(thursday).usage

        assertEquals(listOf("TR-209"), usage.rooms.map { it.room })
        assertEquals(1, harness.signIns)
        assertEquals(1, harness.drops)
        assertEquals(listOf("GET", "POST", "GET", "GET", "POST", "POST"), harness.methods)
    }

    @Test
    fun `a second page without the grid is an error`() = runTest {
        val harness = Harness()
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }

        assertThrows<ClassroomUsageError.Unreadable> { harness.usage(wednesday) }
        assertEquals(2, harness.signIns)
        assertNull(harness.service.keptUsage(wednesday))
    }

    @Test
    fun `a sign-in the school refuses is reported as such`() = runTest {
        val transport = FakeCour01Transport()
        val service = ClassroomUsageService(transport, { _, _ -> false }, {}, { null }, { 0L }, Dispatchers.Unconfined)
        transport.serve(Pages.SIGN_IN_PAGE)

        assertThrows<ClassroomUsageError.NotSignedIn> { service.usage(wednesday, false, false, credentials) }
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `forgetting drops the kept days`() = runTest {
        val harness = Harness()
        harness.serveFirstDay()
        harness.usage(wednesday)

        harness.service.forget()

        assertNull(harness.service.keptUsage(wednesday))
        harness.serveFirstDay()
        harness.usage(thursday.minusDays(1))
        assertEquals("GET", harness.methods[4])
    }

    @Test
    fun `a day still loading when the user signs out is not kept`() = runTest {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", ".............."))), gate)

        val read = async { runCatching { harness.usage(wednesday) } }
        runCurrent()
        assertEquals(3, harness.transport.sent.size)
        harness.service.forget()
        gate.complete(Unit)

        assertTrue(read.await().exceptionOrNull() is ClassroomUsageError.SignedOut)
        assertNull(harness.service.keptUsage(wednesday))
    }

    @Test
    fun `a read that a sign-out ends does not sign in again after a page without the grid`() = runTest {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.SIGN_IN_PAGE, gate)

        val read = async { runCatching { harness.usage(thursday) } }
        runCurrent()
        harness.service.forget()
        gate.complete(Unit)

        assertTrue(read.await().exceptionOrNull() is ClassroomUsageError.SignedOut)
        assertEquals(2, harness.transport.sent.size)
        assertEquals(0, harness.signInAttempts)
        assertEquals(0, harness.drops)
    }

    @Test
    fun `a sign-out while the sign-in waits for its turn refuses the sign-in`() = runTest {
        // The SSO login waits behind any other; a sign-out in that wait must not let it sign the
        // departed account in once its turn comes.
        val harness = Harness()
        harness.ssoLock.lock()
        harness.serve(Pages.SIGN_IN_PAGE)

        val read = async { runCatching { harness.usage(wednesday) } }
        runCurrent()
        assertEquals(1, harness.signInAttempts)
        harness.service.forget()
        harness.ssoLock.unlock()

        assertTrue(read.await().exceptionOrNull() is ClassroomUsageError.SignedOut)
        assertEquals(0, harness.signIns)
        assertEquals(1, harness.transport.sent.size)
    }

    @Test
    fun `a server error is reported as one, without signing in again, and drops the cour01 cookies`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.transport.fail(ClassroomUsageError.ServerError(500))

        val error = assertThrows<ClassroomUsageError.ServerError> { harness.usage(wednesday) }

        assertEquals(500, error.code)
        assertEquals(0, harness.signInAttempts)
        assertEquals(2, harness.transport.sent.size)
        // So a session the site broke on its side does not outlive the error.
        assertEquals(1, harness.drops)
    }

    @Test
    fun `a server error on the very first request drops the cour01 cookies too`() = runTest {
        val harness = Harness()
        harness.transport.fail(ClassroomUsageError.ServerError(503))

        assertThrows<ClassroomUsageError.ServerError> { harness.usage(wednesday) }

        assertEquals(1, harness.drops)
        assertEquals(0, harness.signInAttempts)
    }

    @Test
    fun `every page is parsed on the parsing dispatcher, off the caller's thread`() = runTest {
        var parsed = 0
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                parsed++
                block.run()
            }
        }
        val transport = FakeCour01Transport()
        val service = ClassroomUsageService(transport, { _, _ -> true }, {}, { null }, { 0L }, counting)
        transport.serve(Pages.page(rows = null, campus = ""))
        transport.serve(Pages.page(viewState = "campus"))
        transport.serve(Pages.page(rows = listOf(Pages.row("IB-508", "..............")), pager = Pages.firstOfTwoPages))
        transport.serve(Pages.page(rows = listOf(Pages.row("TR-617", "..............")), pager = Pages.secondOfTwoPages))

        service.usage(wednesday, false, false, credentials)

        assertEquals(4, parsed)
    }

    @Test
    fun `credentials never print the student ID or the password`() {
        assertFalse(credentials.toString().contains("B00000000"))
        assertFalse(credentials.toString().contains("not-a-password"))
    }

    @Test
    fun `a read queued behind a sign-out never signs the departed account in again`() = runTest {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.serve(Pages.SIGN_IN_PAGE, gate)

        val first = async { runCatching { harness.usage(wednesday) } }
        runCurrent()
        val second = async { runCatching { harness.usage(thursday) } }
        runCurrent()
        harness.service.forget()
        gate.complete(Unit)

        assertTrue(first.await().exceptionOrNull() is ClassroomUsageError.SignedOut)
        assertTrue(second.await().exceptionOrNull() is ClassroomUsageError.SignedOut)
        assertEquals(0, harness.signIns)
        assertEquals(1, harness.transport.sent.size)
    }

    @Test
    fun `a cancelled read keeps nothing and lets the next read through`() = runTest {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.serve(Pages.page(rows = null, campus = ""), gate)

        val read = async { harness.usage(wednesday) }
        runCurrent()
        read.cancel()
        runCurrent()

        assertNull(harness.service.keptUsage(wednesday))
        harness.serveFirstDay()
        assertEquals(3, harness.usage(wednesday).usage.rooms.size)
    }

    @Test
    fun `a date that answers with its second page first is read from the first page on`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-617", "cc............")), pager = Pages.secondOfTwoPages, viewState = "second"))
        harness.serve(
            Pages.page(
                rows = listOf(Pages.row("E1-306", "cccc.........."), Pages.row("IB-508", "..............")),
                pager = Pages.firstOfTwoPages,
            ),
        )

        val usage = harness.usage(wednesday).usage

        assertEquals(listOf("E1-306", "IB-508", "TR-617"), usage.rooms.map { it.room })
        assertEquals("showinfo_grd\$_ctl42\$_ctl0", harness.form(3)["__EVENTTARGET"])
        assertEquals(4, harness.transport.sent.size)
    }

    @Test
    fun `a pager that runs past 20 pages gives no day rather than part of one`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        for (page in 1..20) {
            harness.serve(Pages.page(rows = listOf(Pages.row("TR-${500 + page}", "..............")), pager = Pages.pagerTo(page)))
        }
        repeat(2) { harness.serve(Pages.ERROR_PAGE) }

        assertThrows<ClassroomUsageError.Unreadable> { harness.usage(wednesday) }
        val targets = harness.transport.sent.mapNotNull { it.fields["__EVENTTARGET"] }
        assertEquals(19, targets.count { it.startsWith("pager\$") })
        assertTrue("pager\$21" !in targets)
    }

    @Test
    fun `a pager that answers with a page already read gives no day`() = runTest {
        val harness = Harness()
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        repeat(2) { harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), pager = Pages.firstOfTwoPages)) }
        repeat(2) { harness.serve(Pages.ERROR_PAGE) }

        assertThrows<ClassroomUsageError.Unreadable> { harness.usage(wednesday) }
        assertEquals(6, harness.transport.sent.size)
    }

    @Test
    fun `a campus postback that leaves another campus chosen is not read as a day without rooms`() = runTest {
        val harness = Harness()
        repeat(4) { harness.serve(Pages.page(campus = "HHC")) }

        assertThrows<ClassroomUsageError.Unreadable> { harness.usage(wednesday) }
        assertEquals(listOf("GET", "POST", "GET", "POST"), harness.methods)
        assertEquals(0, harness.signIns)
    }

    @Test
    fun `today stays fresh for 15 minutes, a later day for 3 hours, and shows while reloading for 12`() = runTest {
        val harness = Harness()
        harness.serveFirstDay()
        harness.usage(wednesday, isToday = false)

        harness.nowMs += 14 * 60_000L
        harness.usage(wednesday, isToday = true)
        assertEquals(4, harness.transport.sent.size)

        harness.nowMs += 2 * 60_000L
        harness.serve(Pages.page(rows = null, campus = ""))
        harness.serve(Pages.page(viewState = "campus"))
        harness.serve(Pages.page(rows = listOf(Pages.row("TR-209", "..............")), viewState = "again"))
        harness.usage(wednesday, isToday = true)
        assertEquals(7, harness.transport.sent.size)

        harness.nowMs += 2 * 60 * 60_000L
        harness.usage(wednesday, isToday = false)
        assertEquals(7, harness.transport.sent.size)

        harness.nowMs += 9 * 60 * 60_000L
        assertEquals(listOf("TR-209"), harness.service.keptUsage(wednesday)?.usage?.rooms?.map { it.room })
        harness.nowMs += 60 * 60_000L
        assertNull(harness.service.keptUsage(wednesday))
    }

    @Test
    fun `the demo account's rooms come from the device`() = runTest {
        val demo = CampusUsage(listOf(RoomUsage("TR-209", List(14) { RoomSlotUse.Free })), mapOf("TR" to "研揚大樓"))
        val harness = Harness(demo = { demo })

        assertEquals(demo, harness.usage(wednesday).usage)
        assertEquals(demo, harness.service.keptUsage(wednesday)?.usage)
        assertTrue(harness.transport.sent.isEmpty())
    }
}
