package org.ntust.app.tigerduck.demo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.ntust.app.tigerduck.clubs.ClubActivity
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.ClubCard
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditBreakdown
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.RankingStats
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.model.SemesterRanking
import org.ntust.app.tigerduck.shared.time.AcademicTerm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A change of the backend's course-change feed on the coming [weekday], 1 for Monday to 7 for
 * Sunday, today included: `demo.json`'s `courseChanges`, as the iPhone's (tigerduck-app#1049).
 */
data class DemoCourseChange(
    val id: Int,
    val courseNo: String,
    /** As the feed sends it: `cancelled`, `room`, `start`, `end`, `online`, `assessment` or `note`. */
    val kind: String,
    val weekday: Int,
    val periods: List<String>?,
    val room: String?,
    val start: String?,
    val end: String?,
    val noteZh: String?,
    val noteEn: String?,
)

/** The transcript of the regular terms before the current one: `demo.json`'s `scores`. */
data class DemoScores(val student: String, val terms: List<Term>) {
    /** [termsAgo] counts back from the current term, 1 for the one before it, summer terms skipped. */
    data class Term(val termsAgo: Int, val semester: RankingStats?, val cumulative: RankingStats?, val courses: List<Course>)

    data class Course(val code: String, val name: String, val credits: Float?, val grade: String, val status: GradeStatus)
}

/** A club of the club directory, with its activities on the coming weekday: `demo.json`'s `clubs`. */
data class DemoClub(
    val id: Int,
    val name: String,
    val enName: String?,
    val attribute: String?,
    val tagline: String?,
    /** Subscribed once, at the demo sign-in; an unsubscribe after it stays. */
    val subscribed: Boolean,
    val activities: List<Activity>,
) {
    /** [start] and [end] are `HH:mm`. */
    data class Activity(
        val id: Int,
        val name: String,
        val weekday: Int,
        val start: String,
        val end: String,
        val location: String,
        val kind: ClubActivityKind,
        val content: String,
    )
}

/**
 * Reads and builds the demo account's course changes, transcript and clubs. Each is shown as the
 * app's own models, built here, and never as a cache's bytes written by hand.
 */
object DemoFeatures {
    /** The next date on [weekday] (1 for Monday) from [today], [today] itself included; null for no weekday. */
    fun nextDate(weekday: Int, today: LocalDate): LocalDate? =
        weekday.takeIf { it in 1..7 }?.let { today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(it))) }

    /** The feed's body, as `calendar/course-changes` sends it for [term], each row on its coming class day. */
    fun courseChangeFeed(term: String, changes: List<DemoCourseChange>, today: LocalDate): String {
        val rows = JsonArray()
        for (change in changes) {
            val day = nextDate(change.weekday, today) ?: continue
            rows.add(JsonObject().apply {
                addProperty("id", change.id)
                addProperty("course_no", change.courseNo)
                addProperty("kind", change.kind)
                change.periods?.let { periods -> add("periods", JsonArray().apply { periods.forEach(::add) }) }
                add("class_days", JsonArray().apply { add(day.toString()) })
                addProperty("room", change.room)
                addProperty("start", change.start)
                addProperty("end", change.end)
                addProperty("note_zh", change.noteZh)
                addProperty("note_en", change.noteEn)
            })
        }
        return JsonObject().apply {
            addProperty("term", term)
            add("changes", rows)
        }.toString()
    }

    /**
     * The report the score page shows, on the terms before [currentTerm]. Earned credits are those
     * of every listed course, enrolled ones [enrolledCredits], the current term's; null for a
     * current term that is not one.
     */
    fun scoreReport(scores: DemoScores, currentTerm: String, enrolledCredits: Float): ScoreReport? {
        val current = AcademicTerm.parse(currentTerm) ?: return null
        val placed = scores.terms.map { term ->
            term to (0 until term.termsAgo).fold(current) { later, _ -> previousRegular(later) }.code
        }
        val rankings = placed.mapNotNull { (term, code) ->
            term.semester?.let { SemesterRanking(code, it, term.cumulative ?: it) }
        }
        val courses = placed.flatMap { (term, code) ->
            term.courses.mapIndexed { index, course ->
                CourseGrade(
                    index = index + 1, term = code, code = course.code, name = course.name, credits = course.credits,
                    creditType = CreditType.NORMAL, grade = course.grade, status = course.status,
                )
            }
        }
        val earned = courses.sumOf { (it.credits ?: 0f).toDouble() }.toFloat()
        fun inPerson(credits: Float) = CreditBreakdown(inPerson = credits, distance = 0f, total = credits)
        return ScoreReport(
            student = scores.student,
            currentTerm = currentTerm,
            rankings = rankings,
            courses = courses,
            creditSummary = CreditSummary(inPerson(earned), inPerson(enrolledCredits), inPerson(earned + enrolledCredits)),
        )
    }

    /** As the iPhone's `previousRegular`: a summer term is preceded by the second term of its year. */
    private fun previousRegular(term: AcademicTerm): AcademicTerm =
        if (term.part == 'H') AcademicTerm.parse("${term.year}2") ?: term else term.previous()

    fun card(club: DemoClub): ClubCard = ClubCard(club.id, club.name, club.enName, club.attribute, club.tagline)

    /** [club]'s activities, each on its coming weekday from [today], as the feed has them. */
    fun activities(club: DemoClub, today: LocalDate): List<ClubActivity> = club.activities.mapNotNull { activity ->
        val day = nextDate(activity.weekday, today)?.toString() ?: return@mapNotNull null
        ClubActivity(
            id = activity.id, name = activity.name, clubId = club.id, clubName = club.name, date = day, endDate = day,
            startTime = "${activity.start}:00", endTime = "${activity.end}:00", location = activity.location,
            kind = activity.kind, content = activity.content,
        )
    }

    fun parseCourseChanges(root: JsonObject): List<DemoCourseChange>? = root.list("courseChanges") { o ->
        DemoCourseChange(
            id = o.int("id") ?: return@list null,
            courseNo = o.str("courseNo") ?: return@list null,
            kind = o.str("kind") ?: return@list null,
            weekday = o.int("weekday") ?: return@list null,
            periods = o.strings("periods"),
            room = o.str("room"),
            start = o.str("start"),
            end = o.str("end"),
            noteZh = o.textOrNull("note", DemoFixture.LANG_ZH),
            noteEn = o.textOrNull("note", DemoFixture.LANG_EN),
        )
    }

    fun parseScores(root: JsonObject, lang: String): DemoScores? {
        val scores = root.get("scores") as? JsonObject ?: return null
        val terms = scores.list("terms") { o ->
            DemoScores.Term(
                termsAgo = o.int("termsAgo") ?: return@list null,
                semester = (o.get("semester") as? JsonObject)?.let(::rank),
                cumulative = (o.get("cumulative") as? JsonObject)?.let(::rank),
                courses = o.list("courses") { c -> course(c, lang) }.orEmpty(),
            )
        }
        return DemoScores(scores.textOrNull("student", lang).orEmpty(), terms.orEmpty())
    }

    /** A club's `name` is the directory's: `zh` its `name`, `en` its `en_name`. */
    fun parseClubs(root: JsonObject, lang: String): List<DemoClub>? = root.list("clubs") { o ->
        DemoClub(
            id = o.int("id") ?: return@list null,
            name = o.textOrNull("name", DemoFixture.LANG_ZH) ?: return@list null,
            enName = (o.get("name") as? JsonObject)?.str(DemoFixture.LANG_EN),
            attribute = o.str("attribute"),
            tagline = o.textOrNull("tagline", lang),
            subscribed = o.get("subscribed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
            activities = o.list("activities") { a -> activity(a, lang) }.orEmpty(),
        )
    }

    private fun activity(o: JsonObject, lang: String): DemoClub.Activity? = DemoClub.Activity(
        id = o.int("id") ?: return null,
        name = o.textOrNull("name", lang) ?: return null,
        weekday = o.int("weekday") ?: return null,
        start = o.str("start") ?: return null,
        end = o.str("end") ?: return null,
        location = o.textOrNull("location", lang).orEmpty(),
        kind = o.str("type")?.let(ClubActivityKind::of) ?: ClubActivityKind.Meeting,
        content = o.textOrNull("content", lang).orEmpty(),
    )

    private fun course(o: JsonObject, lang: String): DemoScores.Course? = DemoScores.Course(
        code = o.str("code") ?: return null,
        name = o.textOrNull("name", lang) ?: return null,
        credits = o.number("credits")?.toFloat(),
        grade = o.str("grade") ?: return null,
        status = status(o.str("status")),
    )

    /** The iPhone's `GradeStatus` names; `graded` when left out or unknown. */
    private fun status(raw: String?): GradeStatus = when (raw) {
        "pending" -> GradeStatus.PENDING
        "passed" -> GradeStatus.PASS_FAIL_GRADED
        "withdrew" -> GradeStatus.WITHDREW
        "exempted" -> GradeStatus.EXEMPTED
        "unknown" -> GradeStatus.UNKNOWN
        else -> GradeStatus.GRADED
    }

    private fun rank(o: JsonObject) = RankingStats(o.int("classRank"), o.int("deptRank"), o.number("gpa"))

    /** A plain string, or [lang]'s half of a `{"zh", "en"}` pair and else the other half; null for neither. */
    private fun JsonObject.textOrNull(key: String, lang: String): String? {
        val value = get(key) ?: return null
        val pair = value as? JsonObject ?: return value.primitive()?.takeIf { it.isNotBlank() }
        return pair.str(lang) ?: pair.str(if (lang == DemoFixture.LANG_ZH) DemoFixture.LANG_EN else DemoFixture.LANG_ZH)
    }

    private fun JsonElement.primitive(): String? = takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.str(key: String): String? = get(key)?.primitive()?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.int(key: String): Int? = number(key)?.toInt()

    private fun JsonObject.strings(key: String): List<String>? = (get(key) as? JsonArray)?.mapNotNull { it.primitive() }

    private fun <T> JsonObject.list(key: String, parse: (JsonObject) -> T?): List<T>? =
        (get(key) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(parse) }
}
