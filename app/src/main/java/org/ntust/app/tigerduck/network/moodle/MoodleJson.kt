package org.ntust.app.tigerduck.network.moodle

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
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
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberField
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost

/**
 * Moodle's answers read into the app's models. Gson's tree API rather than reflection, so R8 has
 * no field names to keep and a field of an unexpected type drops that field, not the answer: a
 * malformed row is skipped rather than failing the whole list.
 */
object MoodleJson {

    fun siteInfo(json: JsonElement): MoodleSiteInfo {
        val o = json.objOrNull() ?: throw MoodleWebserviceError.MalformedResponse("site_info not an object")
        val userId = o.int("userid")
            ?: throw MoodleWebserviceError.MalformedResponse("userid missing from site_info")
        val functions = o.array("functions")
            ?.mapNotNull { it.objOrNull()?.str("name") }
            ?.toSet()
            .orEmpty()
        return MoodleSiteInfo(
            userId = userId,
            functions = functions,
            privateAccessKey = o.str("userprivateaccesskey")?.takeIf { it.isNotEmpty() },
        )
    }

    /** `webservice/upload.php`'s answer: the item id of the draft area the files went to. */
    fun draftItemId(json: JsonElement): Long =
        json.arrayOrThrow("upload").firstNotNullOfOrNull { it.objOrNull()?.long("itemid") }
            ?: throw MoodleWebserviceError.MalformedResponse("upload answered no draft area")

    /** `core_user_update_picture`: anything but `success: true` is a picture left as it was. */
    fun pictureChanged(json: JsonElement) {
        if (json.objOrNull()?.bool("success") != true) {
            throw MoodleWebserviceError.MalformedResponse("${MoodleCourseApi.UPDATE_PICTURE_FUNCTION} did not change the picture")
        }
    }

    /** `mod_forum_get_forums_by_courses`. */
    fun forums(json: JsonElement): List<MoodleForum> =
        json.arrayOrThrow("forums").mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val intro = o.str("intro")?.let(MoodleHtmlText::plainText).orEmpty()
            MoodleForum(
                id = id,
                courseId = o.int("course") ?: return@mapNotNull null,
                type = o.str("type").orEmpty(),
                name = MoodleHtmlText.plainText(o.str("name").orEmpty()),
                cmid = o.int("cmid") ?: 0,
                discussionCount = o.int("numdiscussions") ?: 0,
                unreadPostCount = o.int("unreadpostscount") ?: 0,
                intro = intro.ifEmpty { null },
            )
        }

    /** `mod_forum_get_forum_discussions`. */
    fun discussions(json: JsonElement): List<MoodleDiscussion> {
        val list = json.objOrNull()?.array("discussions")
            ?: throw MoodleWebserviceError.MalformedResponse("discussions missing")
        return list.mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val message = o.str("message").orEmpty()
            MoodleDiscussion(
                id = o.int("discussion") ?: return@mapNotNull null,
                postId = o.int("id") ?: 0,
                title = MoodleHtmlText.plainText(o.str("name").orEmpty()),
                message = message,
                preview = MoodleHtmlText.plainText(message),
                created = o.long("created") ?: 0,
                modified = o.long("timemodified") ?: 0,
                isPinned = o.bool("pinned") ?: false,
                unreadCount = o.int("numunread") ?: 0,
                replyCount = o.int("numreplies") ?: 0,
                authorName = o.str("userfullname"),
                authorId = o.int("userid"),
                attachments = o.array("attachments")?.mapNotNull(::file).orEmpty(),
            )
        }
    }

    /**
     * `mod_forum_get_discussion_posts`. Moodle writes no parent as `parentid` 0 beside
     * `hasparent` false, and a deleted post's message as its own note.
     */
    fun posts(json: JsonElement): List<MoodlePost> {
        val list = json.objOrNull()?.array("posts")
            ?: throw MoodleWebserviceError.MalformedResponse("posts missing")
        return list.mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val author = o.get("author")?.objOrNull()
            MoodlePost(
                id = o.int("id") ?: return@mapNotNull null,
                parentId = if (o.bool("hasparent") == false) null else o.int("parentid")?.takeIf { it > 0 },
                subject = MoodleHtmlText.plainText(o.str("subject").orEmpty()),
                message = o.str("message").orEmpty(),
                created = o.long("timecreated")?.takeIf { it > 0 },
                authorName = author?.str("fullname"),
                authorId = author?.int("id"),
                attachments = o.array("attachments")?.mapNotNull(::file).orEmpty(),
                isUnread = o.bool("unread") ?: false,
                isDeleted = o.bool("isdeleted") ?: false,
            )
        }
    }

    /**
     * `core_course_get_contents`: the sections the account can open that hold a file, a folder
     * or a link, in Moodle's order.
     */
    fun materials(json: JsonElement): MoodleCourseMaterials =
        MoodleCourseMaterials(json.arrayOrThrow("sections").mapNotNull(::materialSection))

    private fun materialSection(json: JsonElement): MoodleMaterialSection? {
        val o = json.objOrNull() ?: return null
        if (o.bool("uservisible") == false) return null
        val materials = o.array("modules").orEmpty().mapNotNull(::material)
        if (materials.isEmpty()) return null
        return MoodleMaterialSection(
            id = o.int("id") ?: return null,
            name = MoodleHtmlText.plainText(o.str("name").orEmpty()),
            materials = materials,
            number = o.int("section"),
        )
    }

    /**
     * A file, folder or link the account can open, else null. A resource shows its first file,
     * which Moodle lists as the main one; a link only an http or https address.
     */
    private fun material(json: JsonElement): MoodleMaterial? {
        val o = json.objOrNull() ?: return null
        if (o.bool("uservisible") == false) return null
        val contents = o.array("contents").orEmpty().mapNotNull { it.objOrNull() }
        val files = contents.filter { it.str("type") == "file" }.mapNotNull(::file)
        val module = o.str("modname") ?: return null
        var link: String? = null
        val shown = when (module) {
            MoodleMaterial.RESOURCE -> listOf(files.firstOrNull() ?: return null)
            MoodleMaterial.FOLDER -> files.ifEmpty { return null }
            MoodleMaterial.URL -> {
                val address = contents.firstOrNull { it.str("type") == "url" }?.str("fileurl") ?: return null
                val scheme = runCatching { java.net.URI(address).scheme }.getOrNull()?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                link = address
                emptyList()
            }
            else -> return null
        }
        return MoodleMaterial(
            id = o.int("id") ?: return null,
            instance = o.int("instance") ?: return null,
            module = module,
            name = MoodleHtmlText.plainText(o.str("name").orEmpty()),
            files = shown,
            link = link,
        )
    }

    /**
     * A file in a Moodle answer: a post's attachment or a module's content. Moodle's file
     * exporter names the link `url`; `external_files` names it `fileurl`.
     */
    fun file(json: JsonElement): MoodleFile? {
        val o = json.objOrNull() ?: return null
        val name = o.str("filename") ?: return null
        val url = o.str("fileurl") ?: o.str("url") ?: return null
        return MoodleFile(
            name = name,
            size = o.long("filesize") ?: 0,
            url = url,
            mimeType = o.str("mimetype"),
            modified = o.long("timemodified")?.takeIf { it > 0 },
            path = o.str("filepath"),
        )
    }

    /**
     * `gradereport_user_get_grade_items` for [userId]. Moodle writes "-" for an item not graded
     * yet, puts a pass mark's icon as HTML before a grade, and sends no `graderaw` for a grade it
     * keeps from the student, such as one hidden until a date.
     */
    fun gradeItems(json: JsonElement, userId: Int): List<MoodleGradeItem> {
        val users = json.objOrNull()?.array("usergrades")
            ?: throw MoodleWebserviceError.MalformedResponse("usergrades missing")
        val mine = users.mapNotNull { it.objOrNull() }.firstOrNull { it.int("userid") == userId }
            ?: return emptyList()
        return mine.array("gradeitems").orEmpty().mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val formatted = MoodleHtmlText.plainText(o.str("gradeformatted").orEmpty())
            val raw = o.double("graderaw")
            MoodleGradeItem(
                id = o.int("id") ?: return@mapNotNull null,
                name = MoodleHtmlText.plainText(o.str("itemname").orEmpty()),
                type = o.str("itemtype").orEmpty(),
                module = o.str("itemmodule"),
                cmid = o.int("cmid"),
                grade = if (raw == null || formatted.isEmpty() || formatted == "-") null else formatted,
                maxGrade = o.double("grademax"),
                isHidden = o.bool("gradeishidden") ?: false,
                feedback = MoodleHtmlText.markdown(o.str("feedback").orEmpty()),
            )
        }
    }

    /** `core_enrol_get_enrolled_users`. */
    fun members(json: JsonElement): List<MoodleMember> =
        json.arrayOrThrow("members").mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val picture = o.str("profileimageurl")
            MoodleMember(
                id = o.int("id") ?: return@mapNotNull null,
                fullName = o.str("fullname").orEmpty(),
                idNumber = nonEmpty(o.str("idnumber")),
                email = nonEmpty(o.str("email")),
                groups = o.array("groups").orEmpty().mapNotNull { g ->
                    nonEmpty(g.objOrNull()?.str("name")?.let(MoodleHtmlText::plainText))
                },
                roles = o.array("roles").orEmpty().mapNotNull { r ->
                    val role = r.objOrNull() ?: return@mapNotNull null
                    MoodleMemberRole(
                        shortName = role.str("shortname") ?: return@mapNotNull null,
                        name = role.str("name").orEmpty(),
                        sortOrder = role.int("sortorder") ?: 0,
                    )
                },
                pictureUrl = picture?.takeIf { isOwnPicture(it) },
                description = o.str("description")?.let(MoodleHtmlText::plainText).orEmpty(),
                interests = o.str("interests").orEmpty().split(',').mapNotNull { nonEmpty(it) },
                fields = o.array("customfields").orEmpty().mapNotNull(::memberField),
            )
        }

    /** A checkbox's or a date's raw value, `1` or a Unix time, does not read as text. */
    private fun memberField(json: JsonElement): MoodleMemberField? {
        val o = json.objOrNull() ?: return null
        if (o.str("type") in setOf("checkbox", "datetime")) return null
        val label = nonEmpty(MoodleHtmlText.plainText(o.str("name").orEmpty())) ?: return null
        val value = nonEmpty(o.str("value")?.let(MoodleHtmlText::plainText)) ?: return null
        return MoodleMemberField(label, value)
    }

    /**
     * A picture the member uploaded is a file of theirs under `pluginfile.php`; Moodle's default
     * picture is a theme image, such as `/theme/image.php/moove/core/1/u/f1`.
     */
    fun isOwnPicture(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() != MoodleWebservice.SITE_HOST) return false
        val path = uri.rawPath.orEmpty()
        return (path.startsWith("/pluginfile.php/") || path.startsWith("/webservice/pluginfile.php/")) &&
            path.contains("/user/icon/")
    }

    /** `core_course_get_courses_by_field` for [courseId]; empty when Moodle does not list it. */
    fun summary(json: JsonElement, courseId: Int): MoodleCourseSummary {
        val courses = json.objOrNull()?.array("courses")
            ?: throw MoodleWebserviceError.MalformedResponse("courses missing")
        val course = courses.mapNotNull { it.objOrNull() }.firstOrNull { it.int("id") == courseId }
            ?: return MoodleCourseSummary.EMPTY
        val html = course.str("summary").orEmpty()
        return MoodleCourseSummary(
            html = html,
            text = MoodleHtmlText.plainText(html),
            names = listOf("fullname", "displayname", "shortname", "idnumber")
                .mapNotNull { course.str(it) }
                .map(MoodleHtmlText::plainText)
                .filter { it.isNotEmpty() },
            format = course.str("format"),
            startDate = course.long("startdate"),
        )
    }

    /**
     * `mod_assign_get_submission_status`, read from the record the assignment round's status
     * comes from, so the page and Home agree: a team assignment reports the group's status in
     * `teamsubmission`, so a teammate's submission leaves the student's own `submission`
     * unsubmitted or without a status.
     */
    fun submission(json: JsonElement): MoodleAssignmentSubmission {
        val attempt = json.objOrNull()?.get("lastattempt")?.objOrNull()
        val own = attempt?.get("submission")?.objOrNull()
        val team = attempt?.get("teamsubmission")?.objOrNull()
        val record = if (team?.str("status") == SUBMITTED || own?.str("status") == null) team ?: own else own
        val plugins = record?.array("plugins").orEmpty().mapNotNull { it.objOrNull() }
        // What was handed in, from the group's record whenever there is one: a team assignment
        // keeps it there, also while it waits for every member to submit and the status above
        // follows the student's own.
        val handedIn = (team ?: record)?.array("plugins").orEmpty().mapNotNull { it.objOrNull() }
        return MoodleAssignmentSubmission(
            status = record?.str("status"),
            modified = record?.long("timemodified")?.takeIf { it > 0 },
            files = pluginFiles(handedIn.filter { it.str("type") == "file" }),
            onlineText = pluginText(handedIn.firstOrNull { it.str("type") == "onlinetext" }),
            id = record?.int("id"),
            takesComments = plugins.any { it.str("type") == "comments" },
            groupId = attempt?.int("submissiongroup")?.takeIf { it > 0 },
            extensionDue = attempt?.long("extensionduedate")?.takeIf { it > 0 },
            groupName = null,
            comments = null,
            grade = grade(json.objOrNull()?.get("feedback")?.objOrNull()),
        )
    }

    /**
     * The grade and feedback of the answer's `feedback`, which Moodle sends only once the student
     * may see them, with `gradefordisplay` and `gradeddate` null before a grade and "-" for none.
     * Null with neither a grade nor feedback to show.
     */
    private fun grade(feedback: JsonObject?): MoodleAssignmentGrade? {
        feedback ?: return null
        val shown = MoodleHtmlText.plainText(feedback.str("gradefordisplay").orEmpty())
        val grade = shown.takeUnless { it.isEmpty() || it == "-" }
        val comments = pluginText(
            feedback.array("plugins").orEmpty().mapNotNull { it.objOrNull() }.firstOrNull { it.str("type") == "comments" },
        )
        if (grade == null && comments == null) return null
        return MoodleAssignmentGrade(grade, feedback.long("gradeddate")?.takeIf { it > 0 }, comments)
    }

    /**
     * A plugin's editor text with its links pointed at the plugin's own files; null for text with
     * nothing to read, such as an emptied editor's `<p></p>`.
     */
    private fun pluginText(plugin: JsonObject?): String? {
        plugin ?: return null
        val raw = plugin.array("editorfields").orEmpty().firstOrNull()?.objOrNull()?.str("text") ?: return null
        val html = MoodleHtmlText.resolvingPluginFiles(raw, pluginFiles(listOf(plugin)))
        return html.takeIf { MoodleHtmlText.markdown(it).isNotBlank() }
    }

    private fun pluginFiles(plugins: List<JsonObject>): List<MoodleFile> =
        plugins.flatMap { plugin ->
            plugin.array("fileareas").orEmpty().flatMap { area ->
                area.objOrNull()?.array("files").orEmpty().mapNotNull(::file)
            }
        }

    /** One page of `core_comment_get_comments`. */
    fun comments(json: JsonElement): MoodleCommentPage {
        val o = json.objOrNull() ?: throw MoodleWebserviceError.MalformedResponse("comments not an object")
        val comments = o.array("comments")
            ?: throw MoodleWebserviceError.MalformedResponse("comments missing")
        return MoodleCommentPage(
            comments = comments.mapNotNull { el ->
                val c = el.objOrNull() ?: return@mapNotNull null
                MoodleSubmissionComment(
                    id = c.int("id") ?: return@mapNotNull null,
                    authorName = c.str("fullname").orEmpty(),
                    created = c.long("timecreated") ?: 0,
                    content = c.str("content").orEmpty(),
                )
            },
            count = o.int("count"),
        )
    }

    /** `core_group_get_course_user_groups`: the account's groups on the course, by id, their names as text. */
    fun groups(json: JsonElement): Map<Int, String> {
        val groups = json.objOrNull()?.array("groups")
            ?: throw MoodleWebserviceError.MalformedResponse("groups missing")
        val byId = LinkedHashMap<Int, String>()
        for (el in groups) {
            val g = el.objOrNull() ?: continue
            val id = g.int("id") ?: continue
            if (id !in byId) byId[id] = MoodleHtmlText.plainText(g.str("name").orEmpty())
        }
        return byId
    }

    private const val SUBMITTED = "submitted"

    private fun nonEmpty(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    // --- tree helpers ---

    private fun JsonElement.objOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.arrayOrThrow(what: String): JsonArray =
        if (isJsonArray) asJsonArray else throw MoodleWebserviceError.MalformedResponse("$what not a list")

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

    private fun JsonObject.str(key: String): String? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        return el.asString
    }

    private fun JsonObject.long(key: String): Long? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        return runCatching { el.asLong }.getOrNull()
            ?: el.asString.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.double(key: String): Double? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        return runCatching { el.asDouble }.getOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asInt != 0
            else -> p.asString.lowercase().let { it == "true" || it == "1" }
        }
    }
}
