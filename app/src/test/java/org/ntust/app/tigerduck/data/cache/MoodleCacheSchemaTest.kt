package org.ntust.app.tigerduck.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionThread
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberField
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.lang.reflect.Modifier

/**
 * The Moodle cache drops every copy in another shape by its schema number, so the number must
 * move with the shape. Changed a field in network/model/moodle? Bump
 * DataCache.MOODLE_CACHE_SCHEMA, then paste the new shape below with the new number.
 */
class MoodleCacheSchemaTest {

    private val persisted = listOf(
        MoodleAssignmentGrade::class, MoodleAssignmentRecord::class, MoodleAssignmentSubmission::class,
        MoodleCourseGrades::class, MoodleCourseMaterials::class, MoodleCourseSummary::class, MoodleDiscussion::class,
        MoodleDiscussionList::class, MoodleDiscussionThread::class, MoodleFile::class, MoodleForum::class,
        MoodleGradeItem::class, MoodleMaterial::class, MoodleMaterialSection::class, MoodleMember::class,
        MoodleMemberField::class, MoodleMemberList::class, MoodleMemberRole::class, MoodlePost::class,
        MoodleSubmissionComment::class, MoodleTermForums::class,
    )

    private fun shape(): String = persisted.joinToString("\n") { kind ->
        val fields = kind.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
            .sortedBy { it.name }
            .joinToString(", ") { "${it.name}: ${it.genericType.typeName.replace(PACKAGE, "")}" }
        "${kind.simpleName}($fields)"
    }

    @Test
    fun `the cache schema moves with the shape of what it keeps`() {
        assertEquals(5, DataCache.MOODLE_CACHE_SCHEMA)
        assertEquals(SHAPE_AT_SCHEMA_5, shape())
    }

    private companion object {
        val PACKAGE = Regex("""\b(?:[a-z]\w*\.)+""")

        val SHAPE_AT_SCHEMA_5 = """
            MoodleAssignmentGrade(feedback: String, grade: String, gradedAt: Long)
            MoodleAssignmentRecord(allowSubmissionsFrom: long, assignId: int, cmid: int, courseId: int, cutoffDate: long, dueDate: long, intro: String, introAttachments: List<MoodleFile>, name: String, noSubmissions: boolean, teamSubmission: boolean)
            MoodleAssignmentSubmission(comments: List<MoodleSubmissionComment>, extensionDue: Long, files: List<MoodleFile>, grade: MoodleAssignmentGrade, groupId: Integer, groupName: String, id: Integer, modified: Long, onlineText: String, status: String, takesComments: boolean)
            MoodleCourseGrades(items: List<MoodleGradeItem>)
            MoodleCourseMaterials(sections: List<MoodleMaterialSection>)
            MoodleCourseSummary(format: String, html: String, names: List<String>, startDate: Long, text: String)
            MoodleDiscussion(attachments: List<MoodleFile>, authorId: Integer, authorName: String, created: long, id: int, isPinned: boolean, message: String, modified: long, postId: int, preview: String, replyCount: int, title: String, unreadCount: int)
            MoodleDiscussionList(discussions: List<MoodleDiscussion>, forumId: int, hasMore: boolean, pageCount: int)
            MoodleDiscussionThread(discussionId: int, posts: List<MoodlePost>)
            MoodleFile(mimeType: String, modified: Long, name: String, path: String, size: long, url: String)
            MoodleForum(cmid: int, courseId: int, discussionCount: int, id: int, intro: String, name: String, type: String, unreadPostCount: int)
            MoodleGradeItem(cmid: Integer, feedback: String, grade: String, id: int, isHidden: boolean, maxGrade: Double, module: String, name: String, type: String)
            MoodleMaterial(files: List<MoodleFile>, id: int, instance: int, link: String, module: String, name: String)
            MoodleMaterialSection(id: int, materials: List<MoodleMaterial>, name: String, number: Integer)
            MoodleMember(description: String, email: String, fields: List<MoodleMemberField>, fullName: String, groups: List<String>, id: int, idNumber: String, interests: List<String>, pictureUrl: String, roles: List<MoodleMemberRole>)
            MoodleMemberField(name: String, value: String)
            MoodleMemberList(members: List<MoodleMember>, ownId: Integer)
            MoodleMemberRole(name: String, shortName: String, sortOrder: int)
            MoodlePost(attachments: List<MoodleFile>, authorId: Integer, authorName: String, created: Long, id: int, isDeleted: boolean, isUnread: boolean, message: String, parentId: Integer, subject: String)
            MoodleSubmissionComment(authorName: String, content: String, created: long, id: int)
            MoodleTermForums(courseIds: List<Integer>, forums: List<MoodleForum>)
        """.trimIndent()
    }
}
