// Covers the v2.1.0 "自訂" removal: LiveActivityPreferences.MIN_*/MAX_*
// narrowed to match the new slider ranges (assignment 1h..8h, class-prep
// 5min..4h), which is strictly inside the old ranges (5min..7days,
// 1min..3h). An install that persisted a value only reachable through the
// removed 自訂 dialog would otherwise be stuck holding a value the new
// slider can neither display nor produce. These tests pin the clamp-on-read
// behavior that fixes that — see LiveActivityPreferences.readClampedLong's
// KDoc, and LiveActivityPreferencesStore.init() on iOS for the prior art.

package org.ntust.app.tigerduck.liveactivity

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveActivityPreferencesTest {

    @Test
    fun `assignment lead time above the new 8h ceiling is clamped down on read`() {
        val fake = FakeSharedPreferences(initialLongs = mapOf(LiveActivityPreferences.KEY_ASSIGNMENT_LEAD to 86_400L)) // 1 day, valid pre-v2.1.0 via 自訂
        val prefs = LiveActivityPreferences(fake)

        assertEquals(28_800L, prefs.assignmentLeadTimeSec) // 8h ceiling
    }

    @Test
    fun `assignment lead time below the new 1h floor is clamped up on read`() {
        val fake = FakeSharedPreferences(initialLongs = mapOf(LiveActivityPreferences.KEY_ASSIGNMENT_LEAD to 300L)) // 5 min, the pre-v2.1.0 floor
        val prefs = LiveActivityPreferences(fake)

        assertEquals(3_600L, prefs.assignmentLeadTimeSec) // 1h floor
    }

    @Test
    fun `class lead time within the new 5min-4h range is read back unchanged`() {
        val fake = FakeSharedPreferences(initialLongs = mapOf(LiveActivityPreferences.KEY_CLASS_LEAD to 10_800L)) // 3h, inside the new 4h ceiling
        val prefs = LiveActivityPreferences(fake)

        assertEquals(10_800L, prefs.classPreparingLeadTimeSec)
    }

    @Test
    fun `in-range values are read back exactly as stored and never rewritten`() {
        val fake = FakeSharedPreferences(
            initialLongs = mapOf(
                LiveActivityPreferences.KEY_ASSIGNMENT_LEAD to 7_200L, // 2h, inside 1h..8h
                LiveActivityPreferences.KEY_CLASS_LEAD to 1_800L, // 30min, inside 5min..4h
            )
        )
        val prefs = LiveActivityPreferences(fake)

        assertEquals(7_200L, prefs.assignmentLeadTimeSec)
        assertEquals(1_800L, prefs.classPreparingLeadTimeSec)
        // Reading an already-in-range value must not touch storage — a
        // getter that clamps unconditionally (see the next test) would
        // still return the right number here while silently writing (and
        // emitting changeEvent) on every single read.
        assertEquals(0, fake.editCallCount)
    }

    /**
     * [LiveActivityPreferences.syncSnapshot] is the local half of the
     * `notification` document's `live_activity` mapping (the wire half lives
     * in `NotificationSettingsSyncTest`). Every value is deliberately
     * different from its neighbours' — the two booleans that are easiest to
     * transpose disagree, and so do the two lead times — so a snapshot that
     * reads the wrong preference key fails here instead of passing by luck.
     */
    @Test
    fun `syncSnapshot reads each synced value from its own preference key`() {
        val fake = FakeSharedPreferences(
            initialLongs = mapOf(
                LiveActivityPreferences.KEY_CLASS_LEAD to 900L,      // 15 min
                LiveActivityPreferences.KEY_ASSIGNMENT_LEAD to 7_200L, // 2 h
            ),
            initialBooleans = mapOf(
                "show_in_class" to true,
                "show_class_preparing" to false,
                "show_assignment" to true,
                // Not part of the synced section; present so a snapshot that
                // grabbed the wrong boolean key would read a distinguishable
                // value rather than another `true`.
                "show_on_lock_screen" to false,
            ),
        )

        val snapshot = LiveActivityPreferences(fake).syncSnapshot()

        assertEquals(
            LiveActivitySyncValues(
                showInClass = true,
                showClassPreparing = false,
                showAssignment = true,
                classPreparingLeadSeconds = 900,
                assignmentLeadSeconds = 7_200,
            ),
            snapshot,
        )
    }

    @Test
    fun `a clamped value is written back once, not recomputed on every read`() {
        val fake = FakeSharedPreferences(initialLongs = mapOf(LiveActivityPreferences.KEY_ASSIGNMENT_LEAD to 86_400L))
        val prefs = LiveActivityPreferences(fake)

        val firstRead = prefs.assignmentLeadTimeSec
        val secondRead = prefs.assignmentLeadTimeSec

        assertEquals(28_800L, firstRead)
        assertEquals(28_800L, secondRead)
        // The underlying pref now holds the clamped value directly...
        assertEquals(28_800L, fake.getLong(LiveActivityPreferences.KEY_ASSIGNMENT_LEAD, -1))
        // ...and the rewrite happened exactly once (on the first read that
        // found a stale value), not again on the second read that found the
        // already-clamped one. Otherwise a synced copy of this pref and the
        // on-screen slider would only agree until the next read recomputed
        // — or worse, every read would fire changeEvent unnecessarily.
        assertEquals(1, fake.editCallCount)
    }
}

/**
 * Minimal in-memory [SharedPreferences] double covering only what
 * [LiveActivityPreferences] uses (getLong/getBoolean, edit().putLong/
 * putBoolean/clear().apply()). The project has no Robolectric dependency,
 * so a real Context-backed SharedPreferences isn't available to a plain JVM
 * unit test — this stands in for it.
 *
 * [editCallCount] counts calls to [edit], which is exactly once per write
 * attempt in [LiveActivityPreferences] (every write is a single
 * `.edit().putXxx(...).apply()` chain) — tests use it to prove a read did
 * or did not trigger a write-back.
 */
private class FakeSharedPreferences(
    initialLongs: Map<String, Long> = emptyMap(),
    initialBooleans: Map<String, Boolean> = emptyMap(),
) : SharedPreferences {
    private val longs = initialLongs.toMutableMap()
    private val booleans = initialBooleans.toMutableMap()
    var editCallCount = 0
        private set

    override fun getAll(): MutableMap<String, *> = (longs + booleans).toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = longs[key] ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = booleans[key] ?: defValue
    override fun contains(key: String?): Boolean = longs.containsKey(key) || booleans.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editCallCount++
        return FakeEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val putLongs = mutableMapOf<String, Long>()
        private val putBooleans = mutableMapOf<String, Boolean>()
        private val removedKeys = mutableSetOf<String>()
        private var doClear = false

        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) {
                putLongs[key] = value
                removedKeys.remove(key)
            }
        }

        override fun putFloat(key: String?, value: Float) = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) {
                putBooleans[key] = value
                removedKeys.remove(key)
            }
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                removedKeys.add(key)
                putLongs.remove(key)
                putBooleans.remove(key)
            }
        }

        override fun clear(): SharedPreferences.Editor = apply { doClear = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        // Real SharedPreferences.Editor semantics: clear/remove are applied
        // before this editor's own puts, regardless of call order in code.
        private fun applyChanges() {
            if (doClear) {
                longs.clear()
                booleans.clear()
            }
            removedKeys.forEach {
                longs.remove(it)
                booleans.remove(it)
            }
            longs.putAll(putLongs)
            booleans.putAll(putBooleans)
        }
    }
}
