package org.ntust.app.tigerduck.data.model

/**
 * A done or ignored mark tapped on this phone that the backend has not
 * confirmed, kept in `unsent_marks.json` and sent again on the next sync;
 * see `UnsentMarks`.
 *
 * Every field is nullable or primitive: Gson builds this class without its
 * constructor (see CLAUDE.md), so a field added later reads back from an
 * older file as null or zero rather than crashing.
 */
data class UnsentMark(
    /** The Moodle assignment id. */
    val assignmentId: String?,
    /** The status the tap set: `ignored`, `locally_completed` or `none`. */
    val status: String?,
    /** How many times the backend refused it; `UnsentMarks` parks it after five. */
    val attempts: Int = 0,
    /** When the backend last refused it, epoch ms; 0 when it never has. */
    val lastRefusedAtMs: Long = 0L,
    /** When the user tapped it, sent as its `hlc`; null on a mark written before it was kept, which goes out unstamped. */
    val madeAtMs: Long? = null,
)
