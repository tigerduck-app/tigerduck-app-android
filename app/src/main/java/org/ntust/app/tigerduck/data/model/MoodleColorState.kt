package org.ntust.app.tigerduck.data.model

/**
 * What the app remembers about which courses take Moodle's default color, kept in
 * `moodle_course_colors.json` (see `MoodleColorBook`).
 *
 * Every field is nullable: Gson builds this class without its constructor (see CLAUDE.md), so a
 * field added later reads back from an older file as null rather than crashing.
 */
data class MoodleColorState(
    /**
     * Every course that has been given its automatic color, so only a course new to the app is ever
     * moved. A course is its Moodle idnumber, which carries its term, or its number while it has none.
     */
    val known: List<String>? = null,
    /** Courses given an app color because their Moodle id was unknown, which move to Moodle's when it arrives. */
    val awaiting: List<String>? = null,
    /** Courses that take Moodle's color, with it as `#RRGGBB`. */
    val moodle: Map<String, String>? = null,
    /** True while the first refresh after an upgrade is still to come: whatever it brings is existing, not new. */
    val upgrading: Boolean? = null,
    /** A hash of the student this belongs to, so a file a sign-out cut short is not the next student's. */
    val owner: String? = null,
)
