package org.ntust.app.tigerduck.demo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
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
    private val ZONE: ZoneId = ZoneId.of("Asia/Taipei")

    /** The `moodle` section of [root], or null without one. A malformed entry is skipped. */
    fun parse(root: JsonObject, lang: String): DemoMoodle? {
        val moodle = root.get("moodle") as? JsonObject ?: return null
        val courses = (moodle.get("courses") as? JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { parseCourse(it, lang) }
        }
        return DemoMoodle(ownMemberId = moodle.int("ownMemberId") ?: 0, courses = courses)
    }

    private fun parseCourse(o: JsonObject, lang: String): DemoMoodleCourse? {
        val courseNo = o.str("courseNo") ?: return null
        val moodleId = o.int("moodleId") ?: return null
        return DemoMoodleCourse(
            courseNo = courseNo,
            moodleId = moodleId,
            fullname = localized(o, "fullname", lang),
            summaryHtml = localized(o, "summary", lang),
            announcements = (o.get("announcements") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { announcement(it, moodleId, i, lang) } },
            grades = (o.get("grades") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { grade(it, moodleId, i, lang) } },
            members = (o.get("members") as? JsonArray).orEmpty()
                .mapNotNull { el -> (el as? JsonObject)?.let { member(it, lang) } },
            assignments = (o.get("assignments") as? JsonArray).orEmpty()
                .mapNotNull { el -> (el as? JsonObject)?.let { assignment(it, lang) } },
            forums = (o.get("forums") as? JsonArray).orEmpty()
                .mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { forum(it, moodleId, i, lang) } },
            materials = MoodleCourseMaterials(
                (o.get("materials") as? JsonArray).orEmpty()
                    .mapNotNull { el -> (el as? JsonObject)?.let { materialSection(it, lang) } },
            ),
        )
    }

    private fun forum(o: JsonObject, moodleId: Int, index: Int, lang: String): DemoMoodleForum? {
        val id = o.int("id") ?: return null
        val threads = (o.get("discussions") as? JsonArray).orEmpty().mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            val discussionId = d.int("id") ?: return@mapNotNull null
            val title = localized(d, "title", lang)
            val posts = (d.get("posts") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { p -> post(p, lang) } }
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

    private fun post(o: JsonObject, lang: String): MoodlePost? {
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
                (el as? JsonObject)?.let { file(it, "mod_forum/attachment/$id", time(o.str("created"))) }
            },
            isUnread = o.bool("unread"),
            isDeleted = o.bool("deleted"),
        )
    }

    private fun materialSection(o: JsonObject, lang: String): MoodleMaterialSection? {
        val id = o.int("id") ?: return null
        val materials = (o.get("items") as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val cmid = m.int("id") ?: return@mapNotNull null
            val area = "mod_${m.str("type")}/content/$cmid"
            when (m.str("type")) {
                "file" -> (m.get("file") as? JsonObject)?.let { file(it, area, null) }?.let {
                    MoodleMaterial(cmid, cmid, MoodleMaterial.RESOURCE, localized(m, "name", lang), listOf(it), null)
                }
                "folder" -> MoodleMaterial(
                    cmid, cmid, MoodleMaterial.FOLDER, localized(m, "name", lang),
                    (m.get("files") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { f -> file(f, area, null) } },
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
    private fun file(o: JsonObject, area: String, created: Long?): MoodleFile? {
        val name = o.str("name") ?: return null
        return MoodleFile(
            name = name,
            size = o.long("size") ?: 0,
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/0/$area/$name",
            mimeType = o.str("mime"),
            modified = time(o.str("modified")) ?: created,
            path = "/",
        )
    }

    private fun assignment(o: JsonObject, lang: String): DemoMoodleAssignment? {
        val id = o.int("id") ?: return null
        val cmid = o.int("cmid") ?: (id * 10)
        return DemoMoodleAssignment(
            id = id,
            cmid = cmid,
            intro = localized(o, "intro", lang),
            attachments = files(o.get("attachments") as? JsonArray, "mod_assign/introattachment/$cmid"),
            teamSubmission = o.bool("teamSubmission"),
            noSubmissions = o.bool("noSubmissions"),
            submission = (o.get("submission") as? JsonObject)?.let { submission(it, id, lang) },
        )
    }

    /** A submission as `mod_assign_get_submission_status` would answer it, its comments and group included. */
    private fun submission(o: JsonObject, assignId: Int, lang: String): MoodleAssignmentSubmission {
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
            files = files(o.get("files") as? JsonArray, "assignsubmission_file/submission_files/$submissionId"),
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

    /** `[{"name", "size", "mime"}]` as files Moodle would list under [area]. */
    private fun files(array: JsonArray?, area: String): List<MoodleFile> = array.orEmpty().mapNotNull { el ->
        val a = el as? JsonObject ?: return@mapNotNull null
        val name = a.str("name") ?: return@mapNotNull null
        MoodleFile(
            name = name,
            size = a.long("size") ?: 0,
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/0/$area/$name",
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

    private fun announcement(o: JsonObject, moodleId: Int, index: Int, lang: String): MoodleDiscussion? {
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
                MoodleFile(
                    name = name,
                    size = a.long("size") ?: 0,
                    url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/0/mod_forum/attachment/${id * 10}/$name",
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

/**
 * The demo account's Moodle: answers from [moodle] and nothing from the network. What was opened
 * stays read, across restarts too, as Moodle would keep it: [readIds] and [markRead] keep the
 * discussions read with the rest of the demo account's state, which sign-out clears.
 */
class DemoMoodleCourseApi(
    private val moodle: DemoMoodle,
    private val readIds: () -> Set<Int>,
    private val markRead: (Int) -> Unit,
) : MoodleCourseApi {

    private fun course(moodleId: Int) = moodle.courses.firstOrNull { it.moodleId == moodleId }

    private fun unread(discussion: MoodleDiscussion) = if (discussion.id in readIds()) 0 else discussion.unreadCount

    override suspend fun functions(): Set<String> = setOf(
        MoodleCourseApi.FORUMS_FUNCTION,
        MoodleCourseApi.DISCUSSIONS_FUNCTION,
        MoodleCourseApi.DISCUSSION_POSTS_FUNCTION,
        MoodleCourseApi.VIEW_DISCUSSION_FUNCTION,
        MoodleCourseApi.MEMBERS_FUNCTION,
        MoodleCourseApi.GRADE_ITEMS_FUNCTION,
        MoodleCourseApi.COURSES_FUNCTION,
        MoodleCourseApi.SUBMISSION_STATUS_FUNCTION,
        MoodleCourseApi.COMMENTS_FUNCTION,
        MoodleCourseApi.GROUPS_FUNCTION,
        MoodleCourseApi.CONTENTS_FUNCTION,
    ) + MoodleMaterials.VIEW_CALLS.values.map { it.first }

    override suspend fun forums(courseIds: List<Int>): List<MoodleForum> =
        moodle.courses.filter { it.moodleId in courseIds }.flatMap { c ->
            listOf(
                MoodleForum(
                    id = c.newsForumId,
                    courseId = c.moodleId,
                    type = "news",
                    name = "",
                    cmid = c.moodleId * 10 + 2,
                    discussionCount = c.announcements.size,
                    unreadPostCount = c.announcements.sumOf(::unread),
                    intro = null,
                ),
            ) + c.forums.map { f ->
                MoodleForum(
                    id = f.id,
                    courseId = c.moodleId,
                    type = "general",
                    name = f.name,
                    cmid = f.cmid,
                    discussionCount = f.discussions.size,
                    unreadPostCount = f.discussions.sumOf(::unread),
                    intro = f.intro,
                )
            }
        }

    override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
        val list = moodle.courses.firstOrNull { it.newsForumId == forumId }?.announcements
            ?: moodle.courses.flatMap { it.forums }.firstOrNull { it.id == forumId }?.discussions
            ?: return emptyList()
        return list
            .sortedByDescending { it.modified }
            .drop(page * MoodleCourseApi.DISCUSSIONS_PER_PAGE)
            .take(MoodleCourseApi.DISCUSSIONS_PER_PAGE)
            .map { it.copy(unreadCount = unread(it)) }
    }

    /** A discussion opened once has every post read, as Moodle marks the whole of it. */
    override suspend fun posts(discussionId: Int): List<MoodlePost> {
        val posts = moodle.courses.flatMap { it.forums }.firstNotNullOfOrNull { it.posts[discussionId] } ?: return emptyList()
        return if (discussionId in readIds()) posts.map { it.copy(isUnread = false) } else posts
    }

    override suspend fun materials(courseId: Int): MoodleCourseMaterials =
        course(courseId)?.materials ?: MoodleCourseMaterials(emptyList())

    /** Nothing to count a view toward without a server. */
    override suspend fun markMaterialViewed(material: MoodleMaterial, isCurrent: () -> Boolean) = Unit

    override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
        if (isCurrent()) markRead(discussionId)
    }

    override suspend fun members(courseId: Int): MoodleMemberList =
        MoodleMemberList(moodle.ownMemberId, course(courseId)?.members.orEmpty())

    override suspend fun picture(url: String): ByteArray =
        throw java.io.IOException("demo mode has no member pictures")

    override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> = course(courseId)?.grades.orEmpty()

    override suspend fun summary(courseId: Int): MoodleCourseSummary {
        val course = course(courseId) ?: return MoodleCourseSummary.EMPTY
        return MoodleCourseSummary(
            html = course.summaryHtml,
            text = MoodleHtmlText.plainText(course.summaryHtml),
            names = listOf(course.fullname).filter { it.isNotEmpty() },
            format = null,
            startDate = null,
        )
    }

    private fun assignment(assignId: Int): DemoMoodleAssignment? =
        moodle.courses.asSequence().flatMap { it.assignments }.firstOrNull { it.id == assignId }

    /** As Moodle answers it: the comments and the group's name come from their own calls. */
    override suspend fun submission(assignId: Int): MoodleAssignmentSubmission =
        assignment(assignId)?.submission?.copy(comments = null, groupName = null)
            ?: MoodleAssignmentSubmission(
                status = null, modified = null, files = emptyList(), onlineText = null, id = null,
                takesComments = false, groupId = null, groupName = null, comments = null, grade = null,
            )

    override suspend fun comments(cmid: Int, submissionId: Int): List<MoodleSubmissionComment> =
        moodle.courses.asSequence().flatMap { it.assignments }
            .firstOrNull { it.cmid == cmid && it.submission?.id == submissionId }
            ?.submission?.comments.orEmpty()

    override suspend fun groups(courseId: Int): Map<Int, String> =
        course(courseId)?.assignments.orEmpty().mapNotNull { a ->
            val submission = a.submission ?: return@mapNotNull null
            val id = submission.groupId ?: return@mapNotNull null
            id to submission.groupName.orEmpty()
        }.toMap()

    /** The demo account has no network to fetch a file on. */
    override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) =
        throw java.io.IOException("demo mode has no files")
}
