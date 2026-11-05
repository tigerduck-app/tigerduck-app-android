package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.StampedValue
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.util.JvmContext
import kotlin.coroutines.CoroutineContext

/**
 * A holiday PUT whose answer carries the backend's later choice (LWW D12, a write response's
 * winner), with the pull's holiday step running in between: after the winner's take lets the
 * choice go and clears its edit time, on the Calendar's thread, and before the lane settles the
 * send on its own. The send's acknowledgment is recorded before the choice goes, so the pull holds
 * the field, and an older snapshot does not undo the winner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HolidayWinnerPullTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val signIn = SignInSeries().current
    private val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)
    private val ack = AckClock()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // TigerSync is off for good on F-Droid, where no holiday choice is kept to send.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The Calendar's thread, which runs [inTheGap] right after a block it ran returns: the winner's
     * take is done, and the send's own coroutine waits on its dispatcher to settle.
     */
    private class GapMain(private val inTheGap: () -> Unit) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
            inTheGap()
        }
    }

    // The pull's holiday step over a snapshot older than the PUT's answer, as HolidaysReconciler runs it.
    private fun pullOlderSnapshot(holidays: PendingHolidayOverrides) {
        val holds = PullHolds(SNAPSHOT_HLC, ack)
        HolidayOverrideApply.applyStamped(
            mapOf(HOLIDAY to StampedValue(true, OLDER_EDIT_HLC)), holds.serverHlc,
            { holds.keeps(SyncField.holiday(it)) }, signIn, holidays, calendar,
        )
    }

    @Test
    fun `a winner the PUT's answer brings outlives an older snapshot pulled before the lane settles`() = runTest {
        var pulled = false
        lateinit var holidays: PendingHolidayOverrides
        val main = GapMain {
            if (!pulled && HOLIDAY !in prefs.holidayOverridesAwaitingUpload) {
                pulled = true
                pullOlderSnapshot(holidays)
            }
        }
        holidays = PendingHolidayOverrides(
            prefs = prefs,
            // Another device turned the reminder off after this phone's choice was made.
            writer = HolidayOverrideWriter { _, _, _, _ ->
                WriteAnswer(sent = true, serverClockUs = REPLY_HLC, applied = emptySet(), values = mapOf(WriteAnswer.NOTIFY to false))
            },
            syncs = { true },
            stamp = { MADE_AT_MS },
            ack = ack,
            scope = backgroundScope,
            calendar = calendar,
            main = main,
        )

        calendar.setNotifyOnHoliday(HOLIDAY, true)
        holidays.send(HOLIDAY, signIn)

        assertTrue("the pull ran after the take let the choice go", pulled)
        assertEquals("the backend's later choice stays", emptySet<Int>(), calendar.optedInHolidayIds)
        assertEquals(REPLY_HLC, ack.lastAck(SyncField.holiday(HOLIDAY)))
    }

    private companion object {
        const val HOLIDAY = 3
        const val MADE_AT_MS = 1_789_500_000_000L
        const val OLDER_EDIT_HLC = 1_789_000_000_000_000L
        const val SNAPSHOT_HLC = 1_790_000_000_000_000L
        const val REPLY_HLC = 1_790_000_600_000_000L
    }
}
