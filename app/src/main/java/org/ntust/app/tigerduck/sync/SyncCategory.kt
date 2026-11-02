package org.ntust.app.tigerduck.sync

/**
 * The four kinds of data TigerSync syncs, each behind a switch of its own.
 *
 * [stored] is the name this phone has always kept a category by, in `syncedCategories` and
 * `pendingConflictCategories`, and [wireName] the device row's flag the backend keeps its switch
 * in. Earlier builds wrote the first and the backend reads the second, so neither may change. A
 * `when` on a category names each one, with no `else`, so a fifth does not compile until every
 * rule has answered for it.
 */
enum class SyncCategory(val stored: String) {
    COURSES("courses"),
    COURSE_COLORS("course_colors"),
    COURSE_NAMES("course_names"),
    ASSIGNMENTS("assignments"),
    ;

    /** The flag `PATCH /devices/{id}/preferences` names this category's switch by. */
    val wireName: String = "sync_$stored"

    companion object {
        /** The category [value] names, or null for a string this build does not know. */
        fun ofStored(value: String): SyncCategory? = entries.firstOrNull { it.stored == value }

        /**
         * The categories a stored set names. A string this build does not know, which a newer
         * build may have stored, is left out here and stays where it is: nothing writes a stored
         * set back from these values alone.
         */
        fun of(stored: Set<String>): Set<SyncCategory> = entries.filterTo(LinkedHashSet()) { it.stored in stored }
    }
}
