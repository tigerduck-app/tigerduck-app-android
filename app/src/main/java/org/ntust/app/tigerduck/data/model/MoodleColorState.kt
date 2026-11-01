package org.ntust.app.tigerduck.data.model

/**
 * What the app remembers about which courses take Moodle's default color, kept in
 * `moodle_course_colors.json` (see `MoodleColorBook`).
 *
 * Every field is nullable: Gson builds this class without its constructor (see CLAUDE.md), so a
 * field added later reads back from an older file as null rather than crashing.
 */
data class MoodleColorState(
    /** Every course that has been given its automatic color, so only a course new to the app is ever moved. */
    val known: List<String>? = null,
    /** Courses given an app color because their Moodle id was unknown, which move to Moodle's when it arrives. */
    val awaiting: List<String>? = null,
    /** Courses that take Moodle's color, with it as `#RRGGBB`. */
    val moodle: Map<String, String>? = null,
)
