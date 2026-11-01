package org.ntust.app.tigerduck.auth.session

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.SignOutOwed.Replay
import org.ntust.app.tigerduck.util.JvmContext

class SignOutOwedTest {
    private val prefs = JvmContext().getSharedPreferences("owed-test", Context.MODE_PRIVATE)

    @Test
    fun `a marker for a student is replayed only for that student or for nobody`() {
        val owed = SignOutOwed(prefs)
        owed.mark(FIRST)

        assertEquals(Replay.SIGN_OUT_AND_WIPE, owed.replay(FIRST))
        assertEquals(Replay.WIPE, owed.replay(null))
        assertEquals("a later student's data is never wiped", Replay.DROP, owed.replay(SECOND))
    }

    @Test
    fun `a marker for nobody drops when a student is stored`() {
        val owed = SignOutOwed(prefs)
        owed.mark(null)

        assertEquals(Replay.WIPE, owed.replay(null))
        assertEquals(Replay.DROP, owed.replay(SECOND))
    }

    @Test
    fun `the marker does not hold the student ID`() {
        SignOutOwed(prefs).mark(FIRST)
        assertTrue(prefs.all.values.none { it.toString().contains(FIRST) })
    }

    /** Preferences whose commit fails [failures] times before it works. */
    private class Flaky(private val inner: SharedPreferences, var failures: Int) : SharedPreferences by inner {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val real = inner.edit()
            return object : SharedPreferences.Editor by real {
                override fun putBoolean(key: String, value: Boolean) = apply { real.putBoolean(key, value) }
                override fun putString(key: String, value: String?) = apply { real.putString(key, value) }
                override fun remove(key: String) = apply { real.remove(key) }
                override fun commit(): Boolean {
                    commits++
                    if (failures > 0) {
                        failures--
                        return false
                    }
                    return real.commit()
                }
            }
        }
    }

    @Test
    fun `a commit that fails is tried once more`() {
        val flaky = Flaky(prefs, failures = 1)
        val owed = SignOutOwed(flaky)

        assertTrue(owed.mark(FIRST))
        assertEquals(2, flaky.commits)
        assertTrue(owed.owed)

        flaky.failures = 1
        assertTrue(owed.clear())
        assertFalse(owed.owed)
    }

    @Test
    fun `a commit that fails twice says so, and the disk keeps the marker`() {
        val owed = SignOutOwed(prefs)
        owed.mark(FIRST)
        val flaky = Flaky(prefs, failures = 2)

        assertFalse(SignOutOwed(flaky).clear())
        assertTrue("the marker is still on disk", owed.owed)
        // And it still names who left, so the stale marker wipes no later student.
        assertEquals(Replay.DROP, owed.replay(SECOND))
    }

    private companion object {
        const val FIRST = "B00015998"
        const val SECOND = "B00015999"
    }
}
