package org.ntust.app.tigerduck.coursecatalog

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import java.util.Locale

/**
 * A term's courses in one language, with its colleges, departments and general-education
 * dimensions, as `GET course-catalog/{semester}` serves it. The backend builds it in
 * tigerduck-backend's `server/course_catalog/refresh.py`; as the iPhone's `CourseCatalog`.
 */
class CourseCatalog(
    val courses: List<Course>,
    val colleges: List<College>,
    val dimensions: List<Named>,
) {
    /**
     * One row of the school's course query with the three flags the backend adds, and what the
     * search compares, made once here so that a keystroke folds no case: the upper-cased course
     * number, and the lower-cased name and teacher.
     */
    class Course internal constructor(
        val row: CourseSearchResult,
        val isIntensive: Boolean,
        val isAlliance: Boolean,
        val isEnglishTaught: Boolean,
        val numberKey: String,
        val textKey: String,
    ) {
        constructor(row: CourseSearchResult, isIntensive: Boolean, isAlliance: Boolean, isEnglishTaught: Boolean) : this(
            row, isIntensive, isAlliance, isEnglishTaught,
            numberKey = row.courseNo.uppercase(Locale.ROOT),
            textKey = "${row.courseName}\n${row.courseTeacher}".lowercase(Locale.ROOT),
        )

        /** The same course shown as [shown], still found by what the backend sent. */
        internal fun showing(shown: CourseSearchResult) =
            Course(shown, isIntensive, isAlliance, isEnglishTaught, numberKey, textKey)
    }

    data class Named(val no: String, val name: String)

    data class College(val no: String, val name: String, val departments: List<Named>)

    private val firstRows: Map<String, CourseSearchResult> by lazy {
        HashMap<String, CourseSearchResult>(courses.size).apply {
            courses.forEach { putIfAbsent(it.row.courseNo, it.row) }
        }
    }

    /** The first row of [courseNo], or null when this catalog lacks it. */
    fun row(courseNo: String): CourseSearchResult? = firstRows[courseNo]

    /**
     * This catalog showing [shown], its rows relabeled one for one, and still matched by what the
     * backend sent; see [CourseCatalogStore]. A list of another length leaves it as it is.
     */
    fun showing(shown: List<CourseSearchResult>): CourseCatalog {
        if (shown.size != courses.size) return this
        return CourseCatalog(courses.mapIndexed { i, course -> course.showing(shown[i]) }, colleges, dimensions)
    }

    companion object {
        private val gson = Gson()

        /**
         * The catalog [body] holds. A row without a course number or name is dropped, as
         * `DataCache.usableRows` drops one, and a missing teacher, credit or term reads as empty:
         * Gson leaves a missing field null whatever its Kotlin type says.
         */
        fun decode(body: String): CourseCatalog {
            val root = JsonParser.parseString(body).asJsonObject
            return CourseCatalog(
                courses = root.list("courses").mapNotNull { element ->
                    val course = element as? JsonObject ?: return@mapNotNull null
                    val row = usable(gson.fromJson(course, CourseSearchResult::class.java)) ?: return@mapNotNull null
                    Course(row, course.flag("IsIntensive"), course.flag("IsAlliance"), course.flag("IsEnglishTaught"))
                },
                colleges = root.list("colleges").mapNotNull { element ->
                    val college = element as? JsonObject ?: return@mapNotNull null
                    val named = named(college) ?: return@mapNotNull null
                    College(named.no, named.name, college.list("departments").mapNotNull(::named))
                },
                dimensions = root.list("dimensions").mapNotNull(::named),
            )
        }

        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        private fun usable(row: CourseSearchResult?): CourseSearchResult? {
            if (row == null || row.courseNo == null || row.courseName == null) return null
            if (row.semester != null && row.courseTeacher != null && row.creditPoint != null) return row
            return row.copy(
                semester = row.semester ?: "",
                courseTeacher = row.courseTeacher ?: "",
                creditPoint = row.creditPoint ?: "",
            )
        }

        private fun named(element: JsonElement): Named? {
            val named = element as? JsonObject ?: return null
            return Named(named.text("no") ?: return null, named.text("name").orEmpty())
        }

        private fun JsonObject.list(key: String): List<JsonElement> = (get(key) as? JsonArray)?.toList().orEmpty()

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.asString

        private fun JsonObject.flag(key: String): Boolean =
            (get(key) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: false
    }
}

/**
 * The Add course filter lists, from the catalogs: the colleges with their departments, and the
 * general-education dimensions, each named in both languages. As the iPhone's `CourseSearchOptions`.
 */
data class CourseSearchOptions(
    val colleges: List<College> = emptyList(),
    val dimensions: List<Option> = emptyList(),
) {
    data class Option(val code: String, val name: String, val englishName: String) {
        fun name(language: String): String = if (language == "en") englishName else name
    }

    data class College(val name: String, val englishName: String, val departments: List<Option>) {
        fun name(language: String): String = if (language == "en") englishName else name
    }

    companion object {
        /** The lists in the zh catalog's order, with the en catalog's names; either alone serves for both. */
        fun of(catalogs: Map<String, CourseCatalog>): CourseSearchOptions {
            val zh = catalogs["zh"] ?: catalogs["en"] ?: return CourseSearchOptions()
            val en = catalogs["en"] ?: zh
            fun list(named: List<CourseCatalog.Named>, english: List<CourseCatalog.Named>): List<Option> {
                val names = english.reversed().associate { it.no to it.name }
                return named.map { Option(it.no, it.name, names[it.no] ?: it.name) }
            }
            val enColleges = en.colleges.reversed().associateBy { it.no }
            return CourseSearchOptions(
                colleges = zh.colleges.map { college ->
                    val english = enColleges[college.no]
                    College(college.name, english?.name ?: college.name, list(college.departments, english?.departments.orEmpty()))
                },
                dimensions = list(zh.dimensions, en.dimensions),
            )
        }
    }
}
