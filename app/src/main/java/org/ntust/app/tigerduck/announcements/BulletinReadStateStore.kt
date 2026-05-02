package org.ntust.app.tigerduck.announcements

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local cache for the "read" state of bulletins. Mirrors iOS
 * BulletinReadStateStore: a Set<Int> persisted across launches in
 * SharedPreferences, with an in-memory mirror so isRead() never touches disk
 * on the hot path. State never leaves the device — the server has no concept
 * of per-device read tracking.
 */
@Singleton
class BulletinReadStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bulletin_read_state", Context.MODE_PRIVATE)

    private val _readIds = MutableStateFlow(loadFromPrefs())
    val readIds: StateFlow<Set<Int>> = _readIds.asStateFlow()

    fun isRead(id: Int): Boolean = id in _readIds.value

    fun markRead(id: Int) {
        val before = _readIds.value
        _readIds.update { if (id in it) it else it + id }
        // Structural !=, not referential: a concurrent update could race between
        // update{} and this read and produce an equal-but-distinct reference,
        // making !== falsely true (extra persist) — or, after a B→A revert,
        // falsely false (silently dropped disk write). Set equality is cheap.
        if (_readIds.value != before) persist()
    }

    fun markUnread(id: Int) {
        val before = _readIds.value
        _readIds.update { if (id !in it) it else it - id }
        if (_readIds.value != before) persist()
    }

    fun toggleRead(id: Int) {
        if (isRead(id)) markUnread(id) else markRead(id)
    }

    fun markAllRead(ids: Iterable<Int>) {
        val incoming = ids.toSet()
        val before = _readIds.value
        _readIds.update { current ->
            val merged = current + incoming
            if (merged.size == current.size) current else merged
        }
        if (_readIds.value != before) persist()
    }

    /** Drop ids that are no longer in [keep] so prefs don't grow unbounded. */
    fun prune(keep: Iterable<Int>) {
        val alive = keep.toSet()
        val before = _readIds.value
        _readIds.update { current ->
            val trimmed = current.intersect(alive)
            if (trimmed.size == current.size) current else trimmed
        }
        if (_readIds.value != before) persist()
    }

    private fun persist() {
        prefs.edit()
            .putStringSet(KEY_READ_IDS, _readIds.value.map(Int::toString).toSet())
            .apply()
    }

    private fun loadFromPrefs(): Set<Int> =
        prefs.getStringSet(KEY_READ_IDS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    private companion object {
        const val KEY_READ_IDS = "read_ids"
    }
}
