package org.ntust.app.tigerduck.sync.outbox

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The per-key rule of `age_ms`: an edit made before the answer to an earlier send of its field
 * arrived counts its age from that arrival, so a quick second edit, sent after the first's answer,
 * is never stamped before the first. Kept across a process death, and the account's alone.
 */
class AnswerArrivalsTest {

    private val series = SignInSeries()
    private val prefs = JvmContext().getSharedPreferences("tigerduck_outbox_answers", 0)
    private var wall = 1_790_000_000_000L
    private var elapsed = 5_000_000L
    private val clocks = DeviceClocks({ wall }, { elapsed }, { 7 })

    private fun arrivals() = AnswerArrivals(prefs, clocks)

    private fun later(ms: Long) {
        wall += ms
        elapsed += ms
    }

    @Test
    fun `an edit made while its field's send was out counts its age from that send's answer`() {
        val arrivals = arrivals()
        val second = clocks.now()
        later(1_500)
        arrivals.arrived(listOf("mark:7"), series.current)
        val answeredAt = clocks.now()
        later(200)

        assertEquals(answeredAt, arrivals.ageFrom("mark:7", second))
        assertEquals(200L, clocks.ageMs(arrivals.ageFrom("mark:7", second)))
    }

    @Test
    fun `an edit made after the answer, or of another field, keeps its own time`() {
        val arrivals = arrivals()
        arrivals.arrived(listOf("mark:7"), series.current)
        later(10)
        val after = clocks.now()
        val other = EditTime(wall - 60_000, elapsed - 60_000, 7)

        assertEquals(after, arrivals.ageFrom("mark:7", after))
        assertEquals(other, arrivals.ageFrom("mark:8", other))
    }

    @Test
    fun `the oldest value stays the oldest`() {
        val arrivals = arrivals()
        arrivals.arrived(listOf("mark:7"), series.current)

        assertEquals(EditTime.OLDEST, arrivals.ageFrom("mark:7", EditTime.OLDEST))
    }

    @Test
    fun `an arrival outlives the process, so an edit queued before a process death keeps the rule`() {
        val queued = clocks.now()
        later(800)
        arrivals().arrived(listOf("membership:client:1151:XA1"), series.current)
        val answeredAt = clocks.now()
        later(5_000)

        // The next process, in the same boot: its elapsed clock still counts from the arrival.
        assertEquals(answeredAt, arrivals().ageFrom("membership:client:1151:XA1", queued))
    }

    @Test
    fun `an arrival is on disk once arrived returns, never left to a write after it`() {
        val queued = clocks.now()
        later(800)
        AnswerArrivals(LosesApplied(prefs), clocks).arrived(listOf("mark:7"), series.current)
        val answeredAt = clocks.now()
        later(5_000)

        // A process death right after: what apply() would have written later never reached the file.
        assertEquals(answeredAt, arrivals().ageFrom("mark:7", queued))
    }

    @Test
    fun `an arrival is the account's - none under an ended sign-in, and a sign-out forgets them`() = runTest {
        val arrivals = arrivals()
        val queued = clocks.now()
        later(100)
        val ended = series.current
        series.end()
        arrivals.arrived(listOf("mark:7"), ended)
        assertEquals(queued, arrivals.ageFrom("mark:7", queued))

        series.open()
        arrivals.arrived(listOf("mark:7"), series.current)
        arrivals.forgetAccount()

        assertEquals(queued, arrivals.ageFrom("mark:7", queued))
        assertEquals(queued, arrivals().ageFrom("mark:7", queued))
    }

    @Test
    fun `only the latest arrivals are kept`() {
        val arrivals = arrivals()
        val queued = clocks.now()
        later(1)
        repeat(AnswerArrivals.KEPT + 1) { arrivals.arrived(listOf("mark:$it"), series.current) }

        assertEquals("the first is gone", queued, arrivals().ageFrom("mark:0", queued))
        assertEquals(clocks.now(), arrivals().ageFrom("mark:${AnswerArrivals.KEPT}", queued))
    }
}

/**
 * [disk] as a process that dies before an `apply()` reaches the file leaves it: an edit applied
 * never lands, one committed does.
 */
private class LosesApplied(private val disk: SharedPreferences) : SharedPreferences by disk {
    override fun edit(): SharedPreferences.Editor {
        val edit = disk.edit()
        return object : SharedPreferences.Editor by edit {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { edit.putString(key, value) }

            override fun remove(key: String?): SharedPreferences.Editor = also { edit.remove(key) }

            override fun apply() = Unit
        }
    }
}
