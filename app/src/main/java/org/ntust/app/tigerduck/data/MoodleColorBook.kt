package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.MoodleColorState
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.shared.Course
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Moodle's default course colors, and which courses take them.
 *
 * Moodle 4.5 draws a course's generated image in `coursecolor{(id % 10) + 1}`
 * (`core_course\output\course_image::get_generated_color_for_id`); the iPhone app gives courses
 * these colors too (tigerduck-app/tigerduck-app#928), and Android follows it
 * (tigerduck-app/tigerduck-app-android#638).
 *
 * A course takes its Moodle color only when it is new to the app: a course already colored when
 * this was added keeps its color, so nothing moves on an upgrade. A new course whose Moodle id is
 * not known yet takes the app's palette and waits ([MoodleColorState.awaiting]); when the id map
 * later holds its id, it moves. A pick (its `customColorHex`) ends the wait, whether made here or
 * pulled from the server, and so does a reset. Android never sends automatic colors, so a move
 * sends nothing; every device derives the same color from the same id.
 */
class MoodleColorBook {

    private var state = MoodleColorState()
    private var ids: Map<String, Int> = emptyMap()
    private var loaded = false
    private var onChange: ((MoodleColorState) -> Unit)? = null

    @Synchronized
    fun isLoaded(): Boolean = loaded

    /** Called, under the book's lock, with the state to keep each time it changes. */
    @Synchronized
    fun persistWith(listener: ((MoodleColorState) -> Unit)?) {
        onChange = listener
    }

    /**
     * Starts the book from the state kept, or from [legacyIdents] (see [identOf]), the courses
     * already colored when no state was kept yet. [upgrading] is a signed-in student with no state
     * kept, an upgrade: the first refresh's courses count as existing even where the cache
     * holding them was evicted.
     */
    @Synchronized
    fun load(saved: MoodleColorState?, legacyIdents: Set<String>, upgrading: Boolean = false) {
        state = saved ?: MoodleColorState(known = legacyIdents.sorted(), upgrading = upgrading.takeIf { it })
        loaded = true
        if (saved == null && (legacyIdents.isNotEmpty() || upgrading)) onChange?.invoke(state)
    }

    /** Forgets everything, at a sign-out: the next account starts with no course known. */
    @Synchronized
    fun clear() {
        state = MoodleColorState()
        ids = emptyMap()
        loaded = true
        onChange?.invoke(state)
    }

    /** The Moodle ids by normalized idnumber, as the deep links use them. */
    @Synchronized
    fun setIds(map: Map<String, Int>) {
        ids = map
    }

    /**
     * Gives each course new to the app its Moodle color or a wait, and moves each waiting course
     * whose id is known now. Returns whether any course's Moodle color changed. Does nothing
     * before [load], when nothing is known of which courses are new. Returns true when a color
     * moved, and the caller then lays its palette slots out afresh, as the widgets do: a course
     * leaving the app palette frees the slot another probed past.
     */
    @Synchronized
    fun reconcile(courses: List<Course>): Boolean {
        if (!loaded) return false
        val known = state.known.orEmpty().toMutableSet()
        val awaiting = state.awaiting.orEmpty().toMutableSet()
        val moodle = state.moodle.orEmpty().toMutableMap()
        val upgrading = state.upgrading == true
        var colorsChanged = false
        for (course in courses) {
            val ident = identOf(course)
            // A course met before it had an idnumber was kept under its number.
            if (ident != course.courseNo && ident !in known && course.courseNo in awaiting) {
                known -= course.courseNo
                awaiting -= course.courseNo
                known += ident
                awaiting += ident
            }
            val color = colorOf(course)?.let(::formatHex)
            when {
                ident !in known -> {
                    known += ident
                    // A pick came before the first automatic color, so there is none to give; so did
                    // the upgrade, whose courses keep the colors they have.
                    if (!hasPick(course) && !upgrading) {
                        if (color != null) {
                            moodle[ident] = color
                            colorsChanged = true
                        } else {
                            awaiting += ident
                        }
                    }
                }
                ident in awaiting -> {
                    if (hasPick(course)) {
                        awaiting -= ident
                    } else if (color != null) {
                        awaiting -= ident
                        moodle[ident] = color
                        colorsChanged = true
                    }
                }
            }
        }
        // The refresh an upgrade waits for has brought the term's courses, not just hand-added ones.
        val stillUpgrading = upgrading && courses.none { !it.isManual }
        val next = state.copy(
            known = known.sorted(),
            awaiting = awaiting.sorted(),
            moodle = moodle.toSortedMap(),
            upgrading = stillUpgrading.takeIf { it },
        )
        if (next != state) {
            state = next
            onChange?.invoke(next)
        }
        return colorsChanged
    }

    /** The Moodle color, as RGB, of each of [courses] that takes it, by course number. */
    @Synchronized
    fun colorsFor(courses: List<Course>): Map<String, Int> {
        val kept = state.moodle.orEmpty()
        return courses.mapNotNull { course -> parseHex(kept[identOf(course)])?.let { course.courseNo to it } }.toMap()
    }

    /** [courses] with the Moodle color of each that has no pick of its own as its `customColorHex`, as the watch is sent them. */
    @Synchronized
    fun withColors(courses: List<Course>): List<Course> {
        val colors = colorsFor(courses)
        return courses.map { course ->
            val rgb = colors[course.courseNo]
            if (rgb == null || hasPick(course)) course else course.copy(customColorHex = formatHex(rgb))
        }
    }

    /**
     * A reset: the Moodle color of each of [courses], by course number, from the id map, else
     * the color the book remembers, else the id on the course itself; and no course waits any
     * longer. The courses it leaves out are for the caller to give app colors.
     */
    @Synchronized
    fun reset(courses: List<Course>): Map<String, Int> {
        val kept = state.moodle.orEmpty()
        val fresh = courses.mapNotNull { course ->
            val color = colorOf(course) ?: parseHex(kept[identOf(course)])
                ?: course.moodleNumericCourseId?.let(::forCourseId)
            color?.let { course.courseNo to it }
        }.toMap()
        if (!loaded) return fresh
        val idents = courses.map(::identOf).toSet()
        val next = state.copy(
            known = (state.known.orEmpty() + idents).distinct().sorted(),
            awaiting = state.awaiting.orEmpty().filter { it !in idents }.sorted(),
            moodle = (kept.filterKeys { it !in idents } +
                courses.mapNotNull { c -> fresh[c.courseNo]?.let { identOf(c) to formatHex(it) } }).toSortedMap(),
        )
        if (next != state) {
            state = next
            onChange?.invoke(next)
        }
        return fresh
    }

    private fun colorOf(course: Course): Int? {
        // The id map first; the numeric id a Home or background fetch put on the course otherwise.
        val mapped = course.moodleIdNumber?.takeIf { it.isNotEmpty() }?.let { ids[MoodleCourseIds.normalizedIdnumber(it)] }
        return (mapped ?: course.moodleNumericCourseId?.takeIf { it > 0 })?.let(::forCourseId)
    }

    companion object {
        /**
         * What a course is to the book: its Moodle idnumber, which carries its term, so a course
         * number back in another term with another Moodle id is another course; its number while
         * it has no idnumber.
         */
        fun identOf(course: Course): String =
            course.moodleIdNumber?.takeIf { it.isNotEmpty() }?.let(MoodleCourseIds::normalizedIdnumber) ?: course.courseNo

        /** The one book the app reads and the screens share. */
        val shared = MoodleColorBook()

        /** Moodle's `coursecolor1` to `coursecolor10`, as NTUST's Moodle draws them. */
        val DEFAULTS: List<Int> = listOf(
            0x81ECEC, 0x74B9FF, 0xA29BFE, 0xDFE6E9, 0x00B894, 0x0984E3, 0xB2BEC3, 0xFDCB6E, 0xFD79A8, 0x6C5CE7,
        )

        /** [DEFAULTS], each made readable on both of the app's cards. */
        val READABLE: List<Int> = DEFAULTS.map(::readable)

        /** The color Moodle draws Moodle course [id] in, made readable. */
        fun forCourseId(id: Int): Int = READABLE[(abs(id.toLong()) % READABLE.size).toInt()]

        /** Whether [rgb] is one of the colors Moodle gives, which two courses may share. */
        fun isMoodleColor(rgb: Int): Boolean = rgb in READABLE

        private const val DARK_CARD = 0x181818
        private const val LIGHT_CARD = 0xFCFCFD
        private const val MIN_CONTRAST = 3.0

        /**
         * [rgb] with a contrast ratio of at least 3.0 against both cards: if it fails on the dark
         * card, mixed toward white in 1% steps until it does; otherwise, if it fails on the light
         * card, mixed toward black in 1% steps. The iPhone app's rule, pinned by the same values.
         */
        internal fun readable(rgb: Int): Int {
            val target = when {
                contrast(rgb, DARK_CARD) < MIN_CONTRAST -> 255
                contrast(rgb, LIGHT_CARD) < MIN_CONTRAST -> 0
                else -> return rgb
            }
            val card = if (target == 255) DARK_CARD else LIGHT_CARD
            for (percent in 1..100) {
                val mixed = mix(rgb, target, percent / 100.0)
                if (contrast(mixed, card) >= MIN_CONTRAST) return mixed
            }
            return if (target == 255) 0xFFFFFF else 0x000000
        }

        private fun mix(rgb: Int, target: Int, amount: Double): Int {
            var out = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val c = (rgb shr shift) and 0xFF
                out = out or ((c + (target - c) * amount).roundToInt() shl shift)
            }
            return out
        }

        private fun contrast(a: Int, b: Int): Double {
            val la = luminance(a) + 0.05
            val lb = luminance(b) + 0.05
            return maxOf(la, lb) / minOf(la, lb)
        }

        private fun luminance(rgb: Int): Double {
            fun linear(shift: Int): Double {
                val c = ((rgb shr shift) and 0xFF) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }

        fun formatHex(rgb: Int): String = "#" + String.format(java.util.Locale.ROOT, "%06X", rgb and 0xFFFFFF)

        fun parseHex(hex: String?): Int? =
            hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)

        private fun hasPick(course: Course): Boolean = parseHex(course.customColorHex) != null
    }
}
