package org.ntust.app.tigerduck.sync

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.backend.BackendApi

/**
 * The clock across a process death. A write `apply()` queued is lost when the process dies before
 * it reaches the disk, and that only matters for a value ahead of the wall clock: a later launch
 * starts below it, and would stamp an edit at or below one already issued, or below a backend
 * time already seen. Such a value is written before the clock goes on; any other is queued.
 */
class SyncClockDurabilityTest {

    /** The clock's file on disk: what a launch reads. */
    private val disk = HashMap<String, Long>()
    private var now = T

    /** A launch: the preferences as the disk has them, and the clock over them. */
    private inner class Launch {
        val prefs = DyingPreferences(disk)
        val clock = SyncClock(SyncClockStore(prefs), { BackendApi.V4 }, { now })
    }

    @Test
    fun `a stamp ahead of the wall clock outlives a process death`() {
        val first = Launch()
        first.clock.heard(us(T + 5 * MINUTE))
        val issued = first.clock.stampMs()

        // The process dies; the next launch comes with the wall clock barely moved.
        now = T + 100
        val next = Launch().clock.stampMs()

        assertTrue("$next comes after $issued", next > issued)
    }

    @Test
    fun `a backend time seen ahead of the wall clock outlives a process death`() {
        Launch().clock.heard(us(T + 5 * MINUTE))

        now = T + 100
        assertEquals(T + 5 * MINUTE + 1, Launch().clock.stampMs())
    }

    @Test
    fun `a snapshot's time ahead of the wall clock outlives a process death`() {
        Launch().clock.heardInSnapshot(us(T + 5 * MINUTE))

        now = T + 100
        assertEquals(T + 5 * MINUTE + 1, Launch().clock.stampMs())
    }

    @Test
    fun `a value that is not ahead of the wall clock is queued, not written at once`() {
        val launch = Launch()
        // The backend a second behind: its cap, five seconds past it, is past the wall clock too.
        launch.clock.heard(us(T - 1_000))
        launch.clock.heardInSnapshot(us(T - 500))
        assertEquals(T + 1, launch.clock.stampMs())

        assertEquals(0, launch.prefs.commits)
        assertEquals(emptyMap<String, Long>(), disk)
    }

    @Test
    fun `a header that raises nothing is not written at once again`() {
        val launch = Launch()
        launch.clock.heard(us(T + 5 * MINUTE))
        assertEquals(1, launch.prefs.commits)

        // The same time again, and an earlier one: the last value seen is already on disk.
        launch.clock.heard(us(T + 5 * MINUTE))
        launch.clock.heard(us(T + MINUTE))

        assertEquals(1, launch.prefs.commits)
    }

    private fun us(ms: Long) = ms * 1_000

    private companion object {
        const val T = 1_800_000_000_000L
        const val MINUTE = 60_000L
    }
}

/**
 * Preferences of `Long` values over [disk], as Android keeps them: an edit lands in memory at once,
 * `commit()` writes the whole of memory to [disk] before it returns, and `apply()` leaves the disk
 * for a write a process death loses, so a later [DyingPreferences] over the same disk never sees it.
 */
private class DyingPreferences(private val disk: MutableMap<String, Long>) : SharedPreferences {
    private val memory = HashMap(disk)

    /** How many edits were committed. */
    var commits = 0
        private set

    override fun getLong(key: String?, defValue: Long): Long = memory[key] ?: defValue
    override fun contains(key: String?): Boolean = key in memory
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun getAll(): MutableMap<String, *> = HashMap(memory)
    override fun getString(key: String?, defValue: String?): String? = unused()
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = unused()
    override fun getInt(key: String?, defValue: Int): Int = unused()
    override fun getFloat(key: String?, defValue: Float): Float = unused()
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = unused()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Long?>()

        override fun putLong(key: String?, value: Long) = apply { changes[key!!] = value }
        override fun remove(key: String?) = apply { changes[key!!] = null }

        override fun apply() {
            changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
        }

        override fun commit(): Boolean {
            apply()
            disk.clear()
            disk.putAll(memory)
            commits++
            return true
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = unused()
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = unused()
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = unused()
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = unused()
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = unused()
        override fun clear(): SharedPreferences.Editor = unused()
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("the clock keeps Long values only")
}
