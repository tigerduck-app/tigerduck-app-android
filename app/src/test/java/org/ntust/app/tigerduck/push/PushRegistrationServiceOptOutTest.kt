package org.ntust.app.tigerduck.push

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers [applyOptOutIfAccepted] — the invariant extracted out of
 * [PushRegistrationService.updateServerPushOptOut]: a rejected server-push
 * preference change must leave the persisted opt-out exactly where it
 * started, not the new (rejected) value.
 *
 * [PushRegistrationService] itself can't be constructed here to test this
 * end to end — [PushApiClient] and
 * [org.ntust.app.tigerduck.auth.AuthTokenManager] make real OkHttp /
 * Android-Keystore calls that only work on a device, and this module has
 * neither a mocking library nor Robolectric (confirmed: no such dependency
 * anywhere in `gradle/libs.versions.toml`, and every other push/settings
 * test in this module tests pure logic the same way). So this exercises the
 * real production code path — [applyOptOutIfAccepted] itself, unchanged
 * from what `updateServerPushOptOut` calls — against a hand-rolled
 * [SharedPreferences] double, which is the one dependency in this chain
 * that's actually just a plain interface and safe to fake by hand.
 */
class PushRegistrationServiceOptOutTest {

    private val key = "server_push_opt_out_test_key"

    /**
     * The hazard: after a failed toggle, `isServerPushOptedOut()` (a plain
     * `prefs.getBoolean` read) must still answer with the pre-toggle value —
     * not the rejected one — because that is what re-seeds
     * `SettingsViewModel._serverPushOn` on the next construction and what
     * `announceDevice` broadcasts on the next reconciliation pass. A test
     * that only checks an in-memory flag would be exactly the bug this
     * guards against, not a check for it.
     */
    @Test
    fun `a failed attempt leaves the persisted opt-out exactly where it started`() = runBlocking {
        val prefs = FakeSharedPreferences(mapOf(key to false))

        val failure = applyOptOutIfAccepted(prefs, key, optOut = true) {
            throw RuntimeException("PATCH rejected")
        }

        assertNotNull("a failed attempt must report a failure", failure)
        assertEquals(
            "a failed toggle must not persist the requested (rejected) value",
            false,
            prefs.getBoolean(key, true),
        )
    }

    /**
     * Positive control: without this, a degenerate "never persist" fix would
     * also pass the test above.
     */
    @Test
    fun `a successful attempt commits the new opt-out value`() = runBlocking {
        val prefs = FakeSharedPreferences(mapOf(key to false))

        val failure = applyOptOutIfAccepted(prefs, key, optOut = true) {
            // Network call succeeds: no throw.
        }

        assertNull(failure)
        assertEquals(true, prefs.getBoolean(key, false))
    }

    /**
     * Mirrors the existing `if (e is CancellationException) throw e` contract
     * the pre-fix code already had (structured concurrency: a cancelled
     * `viewModelScope` must propagate, not be swallowed as an ordinary
     * failure) — and pins that a cancelled attempt doesn't persist either.
     */
    @Test
    fun `cancellation propagates instead of being swallowed, and does not persist`() = runBlocking {
        val prefs = FakeSharedPreferences(mapOf(key to false))

        try {
            applyOptOutIfAccepted(prefs, key, optOut = true) {
                throw CancellationException("scope cleared")
            }
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(false, prefs.getBoolean(key, true))
    }
}

/**
 * Minimal in-memory [SharedPreferences] double, hand-rolled because this
 * module has no mocking library and no Robolectric (see the class doc on
 * [PushRegistrationServiceOptOutTest]). Only `getBoolean` and
 * `edit().putBoolean().apply()` are exercised by [applyOptOutIfAccepted];
 * the rest of the interface is implemented just enough to compile and to
 * behave sanely if ever touched.
 */
private class FakeSharedPreferences(seed: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = seed.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removedKeys = mutableSetOf<String>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key!!] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            removedKeys.add(key!!)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            shouldClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (shouldClear) values.clear()
            removedKeys.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
