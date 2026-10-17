package org.ntust.app.tigerduck.ui.screen.classtable

/**
 * The class table's tombstone edits between the tap and their write.
 *
 * `deleteCourse` takes the row off the list at once and saves its tombstone
 * after, and `addCourse` lifts one the same way. A fetch folding its answers
 * in that gap reads the tombstones off the file, which does not carry the
 * edit yet, and the course's own answer brings it back. These are the edits
 * still on their way, applied over the file for such a reader, as
 * [ClassTableIgnoredCourses] does for the ignored courses.
 */
internal class ClassTableTombstoneEdits {

    private val lock = Any()
    private var nextId = 0L
    private val unsaved = LinkedHashMap<Long, (Set<String>) -> Set<String>>()

    /** Applies [edit] to every read until its write is done; hand the id it returns to [saved] then. */
    fun edit(edit: (Set<String>) -> Set<String>): Long = synchronized(lock) {
        val id = nextId++
        unsaved[id] = edit
        id
    }

    /** The edit [id]'s write landed: the file carries it, and it must not be applied a second time. */
    fun saved(id: Long) {
        synchronized(lock) { unsaved.remove(id) }
    }

    /** [stored], read from the file, with the edits not written yet on top, in the order they were made. */
    fun over(stored: Set<String>): Set<String> = synchronized(lock) {
        unsaved.values.fold(stored) { tombstones, edit -> edit(tombstones) }
    }

    /** At sign-out: the edits still on their way belong to the account that left. */
    fun clear() {
        synchronized(lock) { unsaved.clear() }
    }
}
