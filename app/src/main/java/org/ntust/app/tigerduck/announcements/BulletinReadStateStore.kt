package org.ntust.app.tigerduck.announcements

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.ApplicationScope
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
    @param:ApplicationScope private val persistScope: CoroutineScope,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bulletin_read_state", Context.MODE_PRIVATE)

    private val lock = Any()
    private val _readIds = MutableStateFlow(loadFromPrefs())
    val readIds: StateFlow<Set<Int>> = _readIds.asStateFlow()

    // Debounce per-toggle persists so a rapid series of swipes coalesces into
    // one disk write instead of N. markAllRead/prune still persist immediately
    // since those are batched user actions.
    private var pendingPersist: Job? = null

    fun isRead(id: Int): Boolean = id in _readIds.value

    fun markRead(id: Int) {
        synchronized(lock) { markReadLocked(id) }
    }

    fun markUnread(id: Int) {
        synchronized(lock) { markUnreadLocked(id) }
    }

    fun toggleRead(id: Int) {
        synchronized(lock) {
            if (id in _readIds.value) markUnreadLocked(id) else markReadLocked(id)
        }
    }

    fun markAllRead(ids: Iterable<Int>) {
        synchronized(lock) {
            val current = _readIds.value
            val merged = current + ids
            if (merged.size == current.size) return
            _readIds.value = merged
            persistNow()
        }
    }

    /** Drop ids that are no longer in [keep] so prefs don't grow unbounded. */
    fun prune(keep: Iterable<Int>) {
        synchronized(lock) {
            val current = _readIds.value
            val trimmed = current.intersect(keep.toSet())
            if (trimmed.size == current.size) return
            _readIds.value = trimmed
            persistNow()
        }
    }

    private fun markReadLocked(id: Int) {
        val current = _readIds.value
        if (id in current) return
        _readIds.value = current + id
        persistDebounced()
    }

    private fun markUnreadLocked(id: Int) {
        val current = _readIds.value
        if (id !in current) return
        _readIds.value = current - id
        persistDebounced()
    }

    private fun persistDebounced() {
        pendingPersist?.cancel()
        pendingPersist = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                synchronized(lock) { writeToPrefs() }
            }
        }
    }

    private fun persistNow() {
        pendingPersist?.cancel()
        pendingPersist = persistScope.launch {
            withContext(Dispatchers.IO) {
                synchronized(lock) { writeToPrefs() }
            }
        }
    }

    private fun writeToPrefs() {
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
        const val PERSIST_DEBOUNCE_MS = 300L
    }
}
