package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The class table's ignored courses ([org.ntust.app.tigerduck.data.CourseIgnores]):
 * the set the cache holds, with this screen's own changes on top until their
 * write is done. A reload that read the file before such a write landed would
 * otherwise put back the value the user just changed.
 *
 * [marksVersion] is the cache's assignment-marks version, bumped after every
 * write of the file.
 */
internal class ClassTableIgnoredCourses(private val marksVersion: StateFlow<Int>) {

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    private val lock = Any()
    private var nextId = 0L
    private val unsaved = LinkedHashMap<Long, (Set<String>) -> Set<String>>()

    /** Applies [edit] at once; hand the id it returns to [saved] once its write is done. */
    fun edit(edit: (Set<String>) -> Set<String>): Long = synchronized(lock) {
        val id = nextId++
        unsaved[id] = edit
        _keys.value = edit(_keys.value)
        id
    }

    /** Whether [id] was still waiting for its write. */
    fun saved(id: Long): Boolean = synchronized(lock) { unsaved.remove(id) != null }

    /**
     * Takes [stored], read from the cache when [marksVersion] was [readAt],
     * with the edits not saved yet on top. Dropped when a write has landed
     * since: the read may predate it, and that write's own reload follows.
     */
    fun reloaded(stored: Set<String>, readAt: Int) {
        synchronized(lock) {
            if (marksVersion.value != readAt) return
            _keys.value = unsaved.values.fold(stored) { keys, edit -> edit(keys) }
        }
    }

    /** At sign-out: the edits still on their way belong to the account that left. */
    fun clear() {
        synchronized(lock) {
            unsaved.clear()
            _keys.value = emptySet()
        }
    }
}
