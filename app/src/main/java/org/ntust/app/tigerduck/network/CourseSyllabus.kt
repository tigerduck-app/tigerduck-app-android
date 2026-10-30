package org.ntust.app.tigerduck.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/**
 * One course's syllabus from QueryCourse's `coursedetials`, as the iPhone reads it
 * (tigerduck-app#885). Any field may be null or blank, and an alliance course carries little more
 * than a link to the other school's page. Read by hand rather than by Gson, so a field of an
 * unexpected type leaves the rest readable; kept in memory only.
 */
data class CourseSyllabus(
    val courseGrading: String?,
    val courseObject: String?,
    val courseContent: String?,
    val courseTextbook: String?,
    val courseRefbook: String?,
    val courseNote: String?,
    val coreAbility: String?,
    val courseUrl: String?,
    /** Shares of lecture, group discussion, case study and practical exercises, as "60" or "". */
    val instruction1: String?,
    val instruction2: String?,
    val instruction3: String?,
    val instruction4: String?,
    val instructionOther: String?,
) {
    // The service sends sections a teacher left empty as blank or whitespace, so each field is
    // trimmed and a blank one is null, which hides its section.
    val grading: String? get() = text(courseGrading)
    val outline: String? get() = text(courseObject)
    val content: String? get() = text(courseContent)
    val notes: String? get() = text(courseNote)
    val objectives: String? get() = text(coreAbility)

    val references: String?
        get() = listOfNotNull(text(courseTextbook), text(courseRefbook)).takeIf { it.isNotEmpty() }?.joinToString("\n\n")

    /** The course's page: an http or https address only. */
    val website: String?
        get() {
            val address = text(courseUrl) ?: return null
            val scheme = runCatching { URI(address).scheme }.getOrNull()?.lowercase()
            return address.takeIf { scheme == "http" || scheme == "https" }
        }

    /** Whether a page of it would show anything: the course detail offers it only then. */
    fun hasContent(labels: CourseSyllabusBreakdown.TeachingLabels): Boolean =
        listOf(grading, outline, content, references, notes, objectives).any { it != null } ||
            website != null || CourseSyllabusBreakdown.teaching(this, labels) != null

    companion object {
        /**
         * The first syllabus of the array the service answers; null for none. For a course it has
         * no row for, the service answers its own exception rather than an empty array. Anything
         * else that is not an array throws.
         */
        fun decode(json: String): CourseSyllabus? {
            val value = JSONTokener(json).nextValue()
            if (value is JSONObject && value.optString("ClassName") == "System.IndexOutOfRangeException") return null
            if (value !is JSONArray) throw JSONException("not a syllabus array")
            val row = value.optJSONObject(0) ?: return null
            fun field(key: String): String? = when (val v = row.opt(key)) {
                is String -> v
                is Int, is Long -> v.toString()
                is Double -> if (v.isFinite() && v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
                is Number -> v.toString()
                else -> null
            }
            return CourseSyllabus(
                courseGrading = field("CourseGrading"),
                courseObject = field("CourseObject"),
                courseContent = field("CourseContent"),
                courseTextbook = field("CourseTextbook"),
                courseRefbook = field("CourseRefbook"),
                courseNote = field("CourseNote"),
                coreAbility = field("CoreAbility"),
                courseUrl = field("CourseURL"),
                instruction1 = field("Instruction_1"),
                instruction2 = field("Instruction_2"),
                instruction3 = field("Instruction_3"),
                instruction4 = field("Instruction_4"),
                instructionOther = field("Instruction_other"),
            )
        }

        private fun text(value: String?): String? =
            value?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The syllabus's shares drawn as a stacked bar: grading parsed from its text, and teaching methods
 * from the Instruction fields. As the iPhone's `CourseSyllabusBreakdown`.
 */
object CourseSyllabusBreakdown {
    data class Part(
        val label: String,
        /** Null for a row with no share in the bar, such as Others written without a percentage. */
        val percent: Double?,
        val detail: String? = null,
    )

    /** The teaching methods' names, in the app's language. */
    data class TeachingLabels(
        val lecture: String,
        val groupDiscussion: String,
        val caseStudy: String,
        val practical: String,
        val other: String,
    )

    private const val MAX_LABEL_LENGTH = 20

    // Longer pieces are prose, and the lazy pattern would backtrack through all of it.
    private const val MAX_PIECE_LENGTH = 200

    private val separators = Regex("[\\r\\n;；,，、]")

    // Numbered lists ("1.", "(2)", "-") lead many syllabi's lines.
    private val listMark = Regex("^(?:[0-9]{1,2}[.)、]|[(（][0-9]{1,2}[)）]|[-•‧・*])\\s*")

    // A range such as 40-60% or 40–(60%) stays text: a bar would show one share the teacher did
    // not give.
    private val range = Regex("[0-9][%％]?\\s*[-－–—~～〜至到]\\s*[(（]?[0-9]{1,3}(?:\\.[0-9]+)?\\s*[%％][)）]?$")

    // The number is whole: not the tail of another, as the 5 of "Quiz .5%", which stays text.
    private val trailing = Regex("(.*?)[\\s:：=－–—\\-]*[(（]?(?<![0-9.])([0-9]{1,3}(?:\\.[0-9]+)?)\\s*[%％][)）]?")

    /** Null when one part is not a short label followed by a percentage, to show the text as written. */
    fun grading(text: String): List<Part>? {
        val pieces = text.split(separators).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = pieces.mapNotNull(::gradingPart)
        if (parts.isEmpty() || parts.size != pieces.size || parts.none { (it.percent ?: 0.0) > 0 }) return null
        return parts
    }

    /**
     * The service fills all four methods, 0 or blank when unused, so only those above 0% show,
     * followed by Others.
     */
    fun teaching(syllabus: CourseSyllabus, labels: TeachingLabels): List<Part>? {
        val methods = listOf(
            syllabus.instruction1 to labels.lecture,
            syllabus.instruction2 to labels.groupDiscussion,
            syllabus.instruction3 to labels.caseStudy,
            syllabus.instruction4 to labels.practical,
        )
        val parts = methods.mapNotNull { (value, label) ->
            share(value).takeIf { it > 0 }?.let { Part(label, it) }
        }.toMutableList()
        val other = syllabus.instructionOther?.trim()?.takeIf { it.isNotEmpty() }
        if (other != null) {
            val split = trailingPercent(other)
            parts += if (split != null) {
                Part(labels.other, split.second, detail = split.first.ifEmpty { null })
            } else {
                Part(labels.other, null, detail = other)
            }
        }
        return parts.ifEmpty { null }
    }

    /** An Instruction field's percentage: blank, non-numeric, not finite or negative counts as 0. */
    fun share(value: String?): Double {
        var text = value?.trim().orEmpty()
        if (text.endsWith("%") || text.endsWith("％")) text = text.dropLast(1)
        val share = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0.0
        return maxOf(share, 0.0)
    }

    private fun gradingPart(piece: String): Part? {
        val (text, percent) = trailingPercent(piece) ?: return null
        val label = text.replace(listMark, "").trim()
        if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return null
        return Part(label, percent)
    }

    private fun trailingPercent(text: String): Pair<String, Double>? {
        if (text.length > MAX_PIECE_LENGTH || range.containsMatchIn(text)) return null
        val match = trailing.matchEntire(text) ?: return null
        val percent = match.groupValues[2].toDoubleOrNull() ?: return null
        return match.groupValues[1].trim() to percent
    }
}
