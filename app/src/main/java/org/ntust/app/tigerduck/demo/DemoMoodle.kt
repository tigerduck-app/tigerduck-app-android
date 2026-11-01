package org.ntust.app.tigerduck.demo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The demo student's courses on Moodle, from `assets/demo.json`'s `moodle` section: a few
 * announcements, grades, members and a summary per course, and for one a forum with threads and
 * materials, so the course detail's Moodle screens have something to show without a network. Read in the app's own models, so the screens take
 * exactly the path they take for a real account.
 */
data class DemoMoodle(
    /** The demo student's own Moodle user, which the members list counts as "you". */
    val ownMemberId: Int,
    val courses: List<DemoMoodleCourse>,
    /** The bytes of each file the fixture gives content to, by the file's demo URL. */
    val content: Map<String, ByteArray> = emptyMap(),
)

data class DemoMoodleCourse(
    val courseNo: String,
    val moodleId: Int,
    val fullname: String,
    val summaryHtml: String,
    val announcements: List<MoodleDiscussion>,
    val grades: List<MoodleGradeItem>,
    val members: List<MoodleMember>,
    val assignments: List<DemoMoodleAssignment>,
    /** The course's general forums, with every post of their discussions. */
    val forums: List<DemoMoodleForum> = emptyList(),
    val materials: MoodleCourseMaterials = MoodleCourseMaterials(emptyList()),
) {
    val newsForumId: Int get() = moodleId * 10 + 1
}

/**
 * What the demo student's assignment page shows beyond Home's row, which the file's top-level
 * `assignments` entry of the same [id] gives: the description and attachments, and the
 * submission as Moodle would answer it, with its comments and its group's name.
 */
data class DemoMoodleAssignment(
    val id: Int,
    val cmid: Int,
    /** HTML. */
    val intro: String,
    val attachments: List<MoodleFile>,
    val teamSubmission: Boolean,
    val noSubmissions: Boolean,
    /** Null for one with no record on Moodle yet. */
    val submission: MoodleAssignmentSubmission?,
)

/** A general forum of a demo course: its discussions as a list gives them, and each one's posts. */
data class DemoMoodleForum(
    val id: Int,
    val cmid: Int,
    val name: String,
    val intro: String?,
    val discussions: List<MoodleDiscussion>,
    val posts: Map<Int, List<MoodlePost>>,
)

object DemoMoodleFixture {
    private val ZONE: ZoneId = org.ntust.app.tigerduck.shared.time.TaipeiClock.zone

    /** The `moodle` section of [root], or null without one. A malformed entry is skipped. */
    fun parse(root: JsonObject, lang: String): DemoMoodle? {
        val moodle = root.get("moodle") as? JsonObject ?: return null
        val content = mutableMapOf<String, ByteArray>()
        val courses = (moodle.get("courses") as? JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { parseCourse(it, lang, content) }
        }
        return DemoMoodle(ownMemberId = moodle.int("ownMemberId") ?: 0, courses = courses, content = content)
    }

    private fun parseCourse(o: JsonObject, lang: String, content: MutableMap<String, ByteArray>): DemoMoodleCourse? {
        val courseNo = o.str("courseNo") ?: return null
        val moodleId = o.int("moodleId") ?: return null
        return DemoMoodleCourse(
            courseNo = courseNo,
            moodleId = moodleId,
            fullname = localized(o, "fullname", lang),
            summaryHtml = localized(o, "summary", lang),
            announcements = (o.get("announcements") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { announcement(it, moodleId, i, lang, content) } },
            grades = (o.get("grades") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { grade(it, moodleId, i, lang) } },
            members = (o.get("members") as? JsonArray).orEmpty()
                .mapNotNull { el -> (el as? JsonObject)?.let { member(it, lang) } },
            assignments = (o.get("assignments") as? JsonArray).orEmpty()
                .mapNotNull { el -> (el as? JsonObject)?.let { assignment(it, lang, content) } },
            forums = (o.get("forums") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { forum(it, moodleId, i, lang, content) } },
            materials = MoodleCourseMaterials(
                (o.get("materials") as? JsonArray).orEmpty()
                    .mapNotNull { el -> (el as? JsonObject)?.let { materialSection(it, lang, content) } },
            ),
        )
    }

    private fun forum(o: JsonObject, moodleId: Int, index: Int, lang: String, content: MutableMap<String, ByteArray>): DemoMoodleForum? {
        val id = o.int("id") ?: return null
        val threads = (o.get("discussions") as? JsonArray).orEmpty().mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val discussionId = d.int("id") ?: return@mapNotNull null
            val title = localized(d, "title", lang)
            val posts = (d.get("posts") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { p -> post(p, lang, content) } }
                // As Moodle titles them: the first post by the discussion, a reply "Re:" it.
                .mapIndexed { i, post -> post.copy(subject = if (i == 0) title else "Re: $title") }
            val first = posts.firstOrNull() ?: return@mapNotNull null
            val discussion = MoodleDiscussion(
                id = discussionId,
                postId = first.id,
                title = title,
                message = first.message,
                preview = MoodleHtmlText.plainText(first.message),
                created = first.created ?: 0,
                modified = posts.maxOf { it.created ?: 0 },
                isPinned = d.bool("pinned"),
                unreadCount = posts.count { it.isUnread },
                replyCount = posts.size - 1,
                authorName = first.authorName,
                authorId = first.authorId,
                attachments = first.attachments,
            )
            discussion to posts
        }
        return DemoMoodleForum(
            id = id,
            cmid = moodleId * 10 + 3 + index,
            name = localized(o, "name", lang),
            intro = localized(o, "intro", lang).ifEmpty { null },
            discussions = threads.map { it.first },
            posts = threads.associate { (discussion, posts) -> discussion.id to posts },
        )
    }

    private fun post(o: JsonObject, lang: String, content: MutableMap<String, ByteArray>): MoodlePost? {
        val id = o.int("id") ?: return null
        return MoodlePost(
            id = id,
            parentId = o.int("parent"),
            subject = "",
            message = localized(o, "message", lang),
            created = time(o.str("created")),
            authorName = localized(o, "author", lang).ifEmpty { null },
            authorId = o.int("authorId"),
            attachments = (o.get("attachments") as? JsonArray).orEmpty().mapNotNull { el ->
                (el as? JsonObject)?.let { file(it, "mod_forum/attachment/$id", time(o.str("created")), content) }
            },
            isUnread = o.bool("unread"),
            isDeleted = o.bool("deleted"),
        )
    }

    private fun materialSection(o: JsonObject, lang: String, content: MutableMap<String, ByteArray>): MoodleMaterialSection? {
        val id = o.int("id") ?: return null
        val materials = (o.get("items") as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val cmid = m.int("id") ?: return@mapNotNull null
            val area = "mod_${m.str("type")}/content/$cmid"
            when (m.str("type")) {
                "file" -> (m.get("file") as? JsonObject)?.let { file(it, area, null, content) }?.let {
                    MoodleMaterial(cmid, cmid, MoodleMaterial.RESOURCE, localized(m, "name", lang), listOf(it), null)
                }
                "folder" -> MoodleMaterial(
                    cmid, cmid, MoodleMaterial.FOLDER, localized(m, "name", lang),
                    (m.get("files") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { f -> file(f, area, null, content) } },
                    null,
                ).takeIf { it.files.isNotEmpty() }
                "link" -> m.str("url")?.let { MoodleMaterial(cmid, cmid, MoodleMaterial.URL, localized(m, "name", lang), emptyList(), it) }
                else -> null
            }
        }
        if (materials.isEmpty()) return null
        return MoodleMaterialSection(id, localized(o, "name", lang), materials, null)
    }

    /**
     * A file under the Moodle site's own web-service path, so a tap takes the path a real file
     * takes, to the demo account's download, which has no network and says so.
     */
    private fun file(o: JsonObject, area: String, created: Long?, content: MutableMap<String, ByteArray>): MoodleFile? {
        val name = o.str("name") ?: return null
        val url = fileUrl(area, name)
        keep(content, url, o)
        return MoodleFile(
            name = name,
            size = o.long("size") ?: 0,
            url = url,
            mimeType = o.str("mime"),
            modified = time(o.str("modified")) ?: created,
            path = "/",
        )
    }

    private fun assignment(o: JsonObject, lang: String, content: MutableMap<String, ByteArray>): DemoMoodleAssignment? {
        val id = o.int("id") ?: return null
        val cmid = o.int("cmid") ?: (id * 10)
        return DemoMoodleAssignment(
            id = id,
            cmid = cmid,
            intro = localized(o, "intro", lang),
            attachments = files(o.get("attachments") as? JsonArray, "mod_assign/introattachment/$cmid", content),
            teamSubmission = o.bool("teamSubmission"),
            noSubmissions = o.bool("noSubmissions"),
            submission = (o.get("submission") as? JsonObject)?.let { submission(it, id, lang, content) },
        )
    }

    /** A submission as `mod_assign_get_submission_status` would answer it, its comments and group included. */
    private fun submission(o: JsonObject, assignId: Int, lang: String, content: MutableMap<String, ByteArray>): MoodleAssignmentSubmission {
        val submissionId = assignId * 10 + 1
        val group = o.get("group") as? JsonObject
        val comments = (o.get("comments") as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val c = el as? JsonObject ?: return@mapIndexedNotNull null
            MoodleSubmissionComment(
                id = submissionId * 10 + i + 1,
                authorName = localized(c, "author", lang),
                created = time(c.str("time")) ?: return@mapIndexedNotNull null,
                content = localized(c, "content", lang),
            )
        }
        return MoodleAssignmentSubmission(
            status = o.str("status") ?: "new",
            modified = time(o.str("time")),
            files = files(o.get("files") as? JsonArray, "assignsubmission_file/submission_files/$submissionId", content),
            onlineText = localized(o, "text", lang).ifEmpty { null },
            id = submissionId,
            takesComments = true,
            groupId = group?.int("id"),
            groupName = group?.let { localized(it, "name", lang) }?.ifEmpty { null },
            comments = comments,
            grade = (o.get("grade") as? JsonObject)?.let { g ->
                MoodleAssignmentGrade(
                    grade = g.str("grade"),
                    gradedAt = time(g.str("gradedAt")),
                    feedback = localized(g, "feedback", lang).ifEmpty { null },
                )
            }?.takeIf { it.grade != null || it.feedback != null },
        )
    }

    private fun fileUrl(area: String, name: String) =
        "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/0/$area/$name"

    /** Remembers the bytes of a file entry's `base64`, if it has them, under its demo [url]. */
    private fun keep(content: MutableMap<String, ByteArray>, url: String, entry: JsonObject) {
        val bytes = entry.str("base64")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return
        content[url] = bytes
    }

    /** `[{"name", "size", "mime"}]` as files Moodle would list under [area]. */
    private fun files(array: JsonArray?, area: String, content: MutableMap<String, ByteArray>): List<MoodleFile> = array.orEmpty().mapNotNull { el ->
        val a = el as? JsonObject ?: return@mapNotNull null
        val name = a.str("name") ?: return@mapNotNull null
        val url = fileUrl(area, name)
        keep(content, url, a)
        MoodleFile(
            name = name,
            size = a.long("size") ?: 0,
            url = url,
            mimeType = a.str("mime"),
            modified = null,
            path = "/",
        )
    }

    /**
     * The records the demo student's assignment rounds would have kept: one per Moodle assignment
     * of [moodle] whose id a Home row among [listed] has, with that row's title and due time.
     */
    fun records(moodle: DemoMoodle, listed: List<Assignment>): List<MoodleAssignmentRecord> {
        val rows = listed.associateBy { it.assignmentId }
        return moodle.courses.flatMap { course ->
            course.assignments.mapNotNull { a ->
                val row = rows[a.id.toString()] ?: return@mapNotNull null
                MoodleAssignmentRecord(
                    assignId = a.id,
                    cmid = a.cmid,
                    courseId = course.moodleId,
                    name = row.title,
                    dueDate = row.dueDate.time / 1000,
                    cutoffDate = row.cutoffDate?.let { it.time / 1000 } ?: 0,
                    allowSubmissionsFrom = 0,
                    intro = a.intro,
                    introAttachments = a.attachments,
                    noSubmissions = a.noSubmissions,
                    teamSubmission = a.teamSubmission,
                )
            }
        }
    }

    private fun announcement(o: JsonObject, moodleId: Int, index: Int, lang: String, content: MutableMap<String, ByteArray>): MoodleDiscussion? {
        val created = time(o.str("created")) ?: return null
        val message = localized(o, "message", lang)
        val id = o.int("id") ?: (moodleId * 100 + index + 1)
        return MoodleDiscussion(
            id = id,
            postId = id * 10,
            title = localized(o, "title", lang),
            message = message,
            preview = MoodleHtmlText.plainText(message),
            created = created,
            modified = created,
            isPinned = o.bool("pinned"),
            unreadCount = if (o.bool("unread")) 1 else 0,
            replyCount = 0,
            authorName = localized(o, "author", lang).ifEmpty { null },
            authorId = o.int("authorId"),
            attachments = (o.get("attachments") as? JsonArray).orEmpty().mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val name = a.str("name") ?: return@mapNotNull null
                val url = fileUrl("mod_forum/attachment/${id * 10}", name)
                keep(content, url, a)
                MoodleFile(
                    name = name,
                    size = a.long("size") ?: 0,
                    url = url,
                    mimeType = a.str("mime"),
                    modified = created,
                    path = "/",
                )
            },
        )
    }

    private fun grade(o: JsonObject, moodleId: Int, index: Int, lang: String): MoodleGradeItem? {
        val type = o.str("type") ?: "mod"
        return MoodleGradeItem(
            id = moodleId * 100 + index + 1,
            name = localized(o, "name", lang),
            type = type,
            module = o.str("module"),
            cmid = if (type == "mod") moodleId * 100 + index + 1 else null,
            grade = o.str("grade"),
            maxGrade = o.double("max"),
            isHidden = false,
            feedback = MoodleHtmlText.markdown(localized(o, "feedback", lang)),
        )
    }

    private fun member(o: JsonObject, lang: String): MoodleMember? {
        val id = o.int("id") ?: return null
        val role = o.str("role") ?: "student"
        return MoodleMember(
            id = id,
            fullName = localized(o, "name", lang),
            idNumber = o.str("idNumber"),
            email = o.str("email"),
            groups = emptyList(),
            roles = listOf(MoodleMemberRole(shortName = role, name = "", sortOrder = 0)),
            pictureUrl = null,
            description = localized(o, "description", lang),
            interests = (o.get("interests") as? JsonArray).orEmpty().mapNotNull { it.primitive() },
            fields = emptyList(),
        )
    }

    private fun localized(o: JsonObject, key: String, lang: String): String {
        val pair = o.get(key) as? JsonObject ?: return o.str(key).orEmpty()
        return pair.str(lang) ?: pair.str(if (lang == DemoFixture.LANG_ZH) DemoFixture.LANG_EN else DemoFixture.LANG_ZH).orEmpty()
    }

    private fun time(text: String?): Long? = try {
        text?.let { LocalDateTime.parse(it).atZone(ZONE).toEpochSecond() }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

    private fun JsonElement.primitive(): String? = takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.str(key: String): String? = get(key)?.primitive()?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.double(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}
