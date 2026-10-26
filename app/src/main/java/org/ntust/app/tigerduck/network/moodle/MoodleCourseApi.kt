package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.moodle.MoodleMaterials
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Moodle calls behind a course's Moodle screens. The live one runs each under the stored
 * token, renewed as for any Moodle call; the demo account has its own, which never leaves the
 * device, and tests pass stand-ins. Calls only some screens make have defaults, so a stand-in for
 * another screen's tests keeps to the calls it is about, as on iOS.
 */
interface MoodleCourseApi {
    /** The web-service functions the site allows this account. Each feature hides without its own. */
    suspend fun functions(): Set<String>

    /** The forums of every course in [courseIds], in one call. */
    suspend fun forums(courseIds: List<Int>): List<MoodleForum>

    /** Page [page] (from 0) of forum [forumId]'s discussions, [DISCUSSIONS_PER_PAGE] to a page. */
    suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion>

    /** Every post of discussion [discussionId], oldest first: Moodle answers with the whole thread. */
    suspend fun posts(discussionId: Int): List<MoodlePost> = emptyList()

    /** Course [courseId]'s materials: its sections with their files, folders and links. */
    suspend fun materials(courseId: Int): MoodleCourseMaterials = MoodleCourseMaterials(emptyList())

    /**
     * Logs a view of [material], which may count toward its completion. [isCurrent] is asked once
     * the token is in hand, as for [markViewed].
     */
    suspend fun markMaterialViewed(material: MoodleMaterial, isCurrent: () -> Boolean) {}

    /**
     * Logs a view of discussion [discussionId], which marks it read. [isCurrent] is asked once the
     * token is in hand: a renewal can outlast a sign-out, and the next account's token would log
     * the view as theirs.
     */
    suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean)

    /** Everyone in course [courseId] the account can see, with the account's own user id. */
    suspend fun members(courseId: Int): MoodleMemberList

    /** A member's own picture from [url], one [MoodleJson.isOwnPicture] accepts. */
    suspend fun picture(url: String): ByteArray

    /** The signed-in student's grade items in course [courseId], in the gradebook's order. */
    suspend fun gradeItems(courseId: Int): List<MoodleGradeItem>

    suspend fun summary(courseId: Int): MoodleCourseSummary

    /** The account's submission for assignment [assignId], as its page shows it; none without a record. */
    suspend fun submission(assignId: Int): MoodleAssignmentSubmission = MoodleAssignmentSubmission(
        status = null, modified = null, files = emptyList(), onlineText = null, id = null,
        takesComments = false, groupId = null, groupName = null, comments = null, grade = null,
    )

    /**
     * Every comment on submission [submissionId] of the assignment with course module [cmid],
     * oldest first; see [MoodleCommentPages].
     */
    suspend fun comments(cmid: Int, submissionId: Int): List<MoodleSubmissionComment> = emptyList()

    /** The account's groups on course [courseId], by id, their names as text. */
    suspend fun groups(courseId: Int): Map<Int, String> = emptyMap()

    /**
     * Downloads [file], a file Moodle's own site serves, into [target], stopping past [maxBytes].
     * [progress] gets the share received so far. The account's key goes in the request only,
     * which never leaves the app; another app gets the copy, not the link. Cancelling the caller
     * cancels the request.
     */
    suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit)

    /**
     * Makes [jpeg] the account's profile picture, as the iPhone does: uploaded to a draft area,
     * then set with [UPDATE_PICTURE_FUNCTION]. Throws [MoodleWebserviceError] when Moodle refuses
     * either step or answers that the picture did not change. Only where the site allows the
     * function; a stand-in that never offers it has nothing to change.
     */
    suspend fun changePicture(jpeg: ByteArray): Unit = throw MoodleWebserviceError.AccessDenied()

    companion object {
        const val FORUMS_FUNCTION = "mod_forum_get_forums_by_courses"
        const val DISCUSSIONS_FUNCTION = "mod_forum_get_forum_discussions"
        const val DISCUSSION_POSTS_FUNCTION = "mod_forum_get_discussion_posts"
        const val VIEW_DISCUSSION_FUNCTION = "mod_forum_view_forum_discussion"
        const val MEMBERS_FUNCTION = "core_enrol_get_enrolled_users"
        const val GRADE_ITEMS_FUNCTION = "gradereport_user_get_grade_items"
        const val COURSES_FUNCTION = "core_course_get_courses_by_field"
        const val SUBMISSION_STATUS_FUNCTION = "mod_assign_get_submission_status"
        const val COMMENTS_FUNCTION = "core_comment_get_comments"
        const val GROUPS_FUNCTION = "core_group_get_course_user_groups"
        const val CONTENTS_FUNCTION = "core_course_get_contents"
        const val UPDATE_PICTURE_FUNCTION = "core_user_update_picture"

        /** Asked for explicitly: Moodle's default `perpage` of 0 returns every discussion at once. */
        const val DISCUSSIONS_PER_PAGE = 20

        /** Moodle's error where a course keeps its participants from the account. */
        const val MEMBERS_HIDDEN_CODE = "nopermissions"

        /**
         * What Moodle answers when a course keeps its grade report from the student, by a course
         * setting or a permission: the course has no grades to show, and the fetch did not fail.
         */
        val GRADES_WITHHELD_CODES = setOf("nopermissiontoviewgrades", "nopermissions")

        /**
         * Only what the members screens show, so Moodle sends no other personal data, such as
         * last access times or other courses. Not `department`, which NTUST fills for the
         * signed-in student alone and with the degree level: the department comes from the
         * student ID (MoodleDepartment), as on the iPhone.
         */
        const val MEMBER_FIELDS =
            "id,fullname,idnumber,email,groups,roles,profileimageurl,description,interests,customfields"
    }
}

@Singleton
class LiveMoodleCourseApi internal constructor(
    private val webservice: MoodleWebservice,
    /** Where answers are read: their HTML is parsed for every post, which is not for the main thread. */
    private val parse: CoroutineDispatcher,
) : MoodleCourseApi {

    @Inject
    constructor(webservice: MoodleWebservice) : this(webservice, Dispatchers.Default)

    override suspend fun functions(): Set<String> =
        webservice.withToken { webservice.siteInfo(it).functions }

    override suspend fun forums(courseIds: List<Int>): List<MoodleForum> = webservice.withToken { token ->
        val answer = webservice.call(
            MoodleCourseApi.FORUMS_FUNCTION,
            token,
            courseIds.mapIndexed { i, id -> "courseids[$i]" to id.toString() },
        )
        withContext(parse) { MoodleJson.forums(answer) }
    }

    /** `moodlewssettingfilter` runs the message through the site's filters, as the Moodle app asks. */
    override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> =
        webservice.withToken { token ->
            val answer = webservice.call(
                MoodleCourseApi.DISCUSSIONS_FUNCTION,
                token,
                listOf(
                    "forumid" to forumId.toString(),
                    "page" to page.toString(),
                    "perpage" to MoodleCourseApi.DISCUSSIONS_PER_PAGE.toString(),
                    "moodlewssettingfilter" to "true",
                ),
            )
            withContext(parse) { MoodleJson.discussions(answer) }
        }

    /** Sorted by Moodle, oldest first, so a reply follows what it answers. */
    override suspend fun posts(discussionId: Int): List<MoodlePost> = webservice.withToken { token ->
        val answer = webservice.call(
            MoodleCourseApi.DISCUSSION_POSTS_FUNCTION,
            token,
            listOf(
                "discussionid" to discussionId.toString(),
                "sortby" to "created",
                "sortdirection" to "ASC",
                "moodlewssettingfilter" to "true",
            ),
        )
        withContext(parse) { MoodleJson.posts(answer) }
    }

    override suspend fun materials(courseId: Int): MoodleCourseMaterials = webservice.withToken { token ->
        val answer = webservice.call(
            MoodleCourseApi.CONTENTS_FUNCTION,
            token,
            listOf("courseid" to courseId.toString()),
        )
        withContext(parse) { MoodleJson.materials(answer) }
    }

    override suspend fun markMaterialViewed(material: MoodleMaterial, isCurrent: () -> Boolean) {
        val (function, parameter) = MoodleMaterials.VIEW_CALLS[material.module] ?: return
        webservice.withToken { token ->
            if (!isCurrent()) return@withToken
            webservice.call(function, token, listOf(parameter to material.instance.toString()))
        }
    }

    override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
        webservice.withToken { token ->
            if (!isCurrent()) return@withToken
            webservice.call(
                MoodleCourseApi.VIEW_DISCUSSION_FUNCTION,
                token,
                listOf("discussionid" to discussionId.toString()),
            )
        }
    }

    override suspend fun changePicture(jpeg: ByteArray) = webservice.withToken { token ->
        val draft = webservice.uploadDraft(token, PICTURE_FILE_NAME, "image/jpeg", jpeg)
        val answer = webservice.call(
            MoodleCourseApi.UPDATE_PICTURE_FUNCTION,
            token,
            listOf("draftitemid" to draft.toString(), "delete" to "0"),
        )
        MoodleJson.pictureChanged(answer)
    }

    override suspend fun members(courseId: Int): MoodleMemberList = webservice.withToken { token ->
        val ownId = webservice.siteInfo(token).userId
        val answer = webservice.call(
            MoodleCourseApi.MEMBERS_FUNCTION,
            token,
            listOf(
                "courseid" to courseId.toString(),
                "options[0][name]" to "userfields",
                "options[0][value]" to MoodleCourseApi.MEMBER_FIELDS,
            ),
        )
        MoodleMemberList(ownId = ownId, members = withContext(parse) { MoodleJson.members(answer) })
    }

    /**
     * Under `tokenpluginfile.php` with the account's private access key, as the Moodle app
     * downloads files, else the web-service path with the token. Either key only ever goes to
     * the Moodle site. An answer that is not an image, such as a sign-in page, throws.
     */
    override suspend fun picture(url: String): ByteArray = webservice.withToken { token ->
        val key = webservice.siteInfo(token).privateAccessKey
        val download = MoodleFileUrls.downloadRequest(url, key, token)
        if (!MoodleJson.isOwnPicture(url) || download == null) {
            throw MoodleWebserviceError.MalformedResponse("not a member's picture")
        }
        withContext(Dispatchers.IO) {
            webservice.download(download) { type ->
                type?.startsWith("image/") == true
            }
        }
    }

    override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> = webservice.withToken { token ->
        val userId = webservice.siteInfo(token).userId
        try {
            val answer = webservice.call(
                MoodleCourseApi.GRADE_ITEMS_FUNCTION,
                token,
                listOf("courseid" to courseId.toString(), "userid" to userId.toString()),
            )
            withContext(parse) { MoodleJson.gradeItems(answer, userId) }
        } catch (e: MoodleWebserviceError.MalformedResponse) {
            if (e.detail in MoodleCourseApi.GRADES_WITHHELD_CODES) emptyList() else throw e
        }
    }

    /**
     * The share is of the length the reply gives, else of the size Moodle lists. A file whose type
     * Moodle does not list is checked against a page standing in for it: a web page is refused
     * unless the file's own name says it is one.
     */
    override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) {
        webservice.withToken { token ->
            val request = MoodleFileUrls.downloadRequest(file.url, webservice.siteInfo(token).privateAccessKey, token)
                ?: throw MoodleWebserviceError.MalformedResponse("not a Moodle file")
            val expected = file.checkableMimeType()
            webservice.downloadTo(
                request,
                target,
                expected,
                maxBytes,
                refusesPage = expected == null && !file.isWebPage(),
            ) { read, length ->
                val total = if (length > 0) length else file.size
                if (total > 0) progress((read.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    override suspend fun submission(assignId: Int): MoodleAssignmentSubmission = webservice.withToken { token ->
        val userId = webservice.siteInfo(token).userId
        val answer = webservice.call(
            MoodleCourseApi.SUBMISSION_STATUS_FUNCTION,
            token,
            listOf("assignid" to assignId.toString(), "userid" to userId.toString()),
        )
        withContext(parse) { MoodleJson.submission(answer) }
    }

    /**
     * Moodle keeps a submission's comments on the assignment's module, under the submission's
     * record, a page at a time.
     */
    override suspend fun comments(cmid: Int, submissionId: Int): List<MoodleSubmissionComment> =
        webservice.withToken { token ->
            MoodleCommentPages.all { page ->
                val answer = webservice.call(
                    MoodleCourseApi.COMMENTS_FUNCTION,
                    token,
                    listOf(
                        "contextlevel" to "module",
                        "instanceid" to cmid.toString(),
                        "component" to "assignsubmission_comments",
                        "itemid" to submissionId.toString(),
                        "area" to "submission_comments",
                        "page" to page.toString(),
                    ),
                )
                withContext(parse) { MoodleJson.comments(answer) }
            }
        }

    override suspend fun groups(courseId: Int): Map<Int, String> = webservice.withToken { token ->
        val userId = webservice.siteInfo(token).userId
        val answer = webservice.call(
            MoodleCourseApi.GROUPS_FUNCTION,
            token,
            listOf("courseid" to courseId.toString(), "userid" to userId.toString()),
        )
        withContext(parse) { MoodleJson.groups(answer) }
    }

    override suspend fun summary(courseId: Int): MoodleCourseSummary = webservice.withToken { token ->
        val answer = webservice.call(
            MoodleCourseApi.COURSES_FUNCTION,
            token,
            listOf(
                "field" to "id",
                "value" to courseId.toString(),
                "moodlewssettingfilter" to "true",
            ),
        )
        withContext(parse) { MoodleJson.summary(answer, courseId) }
    }

    private companion object {
        /** The profile picture's name in its draft area; Moodle keeps the picture under its own. */
        const val PICTURE_FILE_NAME = "picture.jpg"
    }
}
