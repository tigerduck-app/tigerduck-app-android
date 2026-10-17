// The rules the Moodle screens follow, kept apart from the screens and the store so they can be
// tested on the JVM: which section rows show, the order of a forum's discussions and the layout
// of a thread, the rows of a course's materials, the role sections of the members list, and the
// grades a course total and its items show.

package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentStatus
import org.ntust.app.tigerduck.data.model.status
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
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
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.text.Collator
import java.util.Locale

/** A row of the course detail's Moodle section. */
data class MoodleSectionRow(val kind: Kind, val count: Int, val hasUnread: Boolean) {
    enum class Kind { ANNOUNCEMENTS, MATERIALS, ASSIGNMENTS, GRADES, MEMBERS, FORUMS }
}

/**
 * An assignment as the course's list and its page take it: the record a round kept, with Home's
 * row for it, which keeps what Moodle said of the submission. Home lists none without a due date
 * or an online submission, so [listed] is null for those.
 */
data class MoodleAssignmentEntry(val record: MoodleAssignmentRecord, val listed: Assignment?) {
    val assignmentId: String get() = record.assignId.toString()
}

object MoodleAssignmentEntries {
    /**
     * The entry for [assignmentId]: its record among [records] with its row among [listed]. A row
     * no round has recorded, from a cache written before records were kept, gets a record built
     * from the row, with its course module read from the row's Moodle link, so its page opens
     * with what the row knows. Null for an id in neither, which is not this account's homework.
     */
    fun of(assignmentId: String, records: List<MoodleAssignmentRecord>, listed: List<Assignment>): MoodleAssignmentEntry? {
        val row = listed.firstOrNull { it.assignmentId == assignmentId }
        val record = records.firstOrNull { it.assignId.toString() == assignmentId }
            ?: row?.let(::recordOf)
            ?: return null
        return MoodleAssignmentEntry(record, row)
    }

    private fun recordOf(row: Assignment) = MoodleAssignmentRecord(
        assignId = row.assignmentId.toIntOrNull() ?: 0,
        cmid = cmidOf(row.moodleUrl) ?: 0,
        courseId = 0,
        name = row.title,
        dueDate = row.dueDate.time / 1000,
        cutoffDate = row.cutoffDate?.let { it.time / 1000 } ?: 0,
        allowSubmissionsFrom = 0,
        intro = "",
        introAttachments = emptyList(),
        // Home lists only assignments that take a submission.
        noSubmissions = false,
        teamSubmission = false,
    )

    private val ASSIGNMENT_LINK = Regex("""/mod/assign/view\.php\?(?:.*&)?id=(\d+)(?:&|$|#)""")

    /** The course module in a `mod/assign/view.php?id=<cmid>` link; null for any other. */
    fun cmidOf(moodleUrl: String?): Int? =
        moodleUrl?.let { ASSIGNMENT_LINK.find(it) }?.groupValues?.get(1)?.toIntOrNull()
}

/** The course's assignment list: its order, and what each row says of the submission. */
object MoodleAssignmentLists {
    /** A list row, with what Moodle says of the submission; null while there is nothing to say. */
    data class Row(val entry: MoodleAssignmentEntry, val status: AssignmentStatus?)

    /**
     * Those still to come by due time, then the ones with no due date, then the past ones, the
     * latest first. Ties keep Moodle's order, which [entries] is in.
     */
    fun rows(entries: List<MoodleAssignmentEntry>, nowMs: Long): List<Row> {
        val nowSeconds = nowMs / 1000
        val ordered = entries.withIndex()
        fun isPast(entry: MoodleAssignmentEntry) = entry.record.dueDate > 0 && entry.record.dueDate < nowSeconds
        val toCome = ordered.filter { !isPast(it.value) }.sortedWith(
            compareBy<IndexedValue<MoodleAssignmentEntry>> { it.value.record.dueDate.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
                .thenBy { it.index },
        )
        val past = ordered.filter { isPast(it.value) }.sortedWith(
            compareByDescending<IndexedValue<MoodleAssignmentEntry>> { it.value.record.dueDate }.thenBy { it.index },
        )
        return (toCome + past).map { (_, entry) ->
            Row(entry, entry.listed?.let { shownStatus(it, nowMs) })
        }
    }

    /**
     * What Moodle says of the assignment, without the marks the user puts on Home's rows: handed
     * in on time or late, or overdue, and nothing while it is still to come.
     */
    fun shownStatus(assignment: Assignment, nowMs: Long): AssignmentStatus? =
        when (assignment.status(now = java.util.Date(nowMs))) {
            AssignmentStatus.SUBMITTED -> AssignmentStatus.SUBMITTED
            AssignmentStatus.SUBMITTED_LATE -> AssignmentStatus.SUBMITTED_LATE
            AssignmentStatus.OVERDUE_ACCEPTABLE, AssignmentStatus.OVERDUE_REJECTED -> AssignmentStatus.OVERDUE_ACCEPTABLE
            AssignmentStatus.PENDING -> null
        }
}

object MoodleSections {

    /**
     * Why the section's last fetch of [states] failed, the first that did and is not being
     * fetched again; null with none.
     */
    fun failure(states: List<MoodleFeed.State<*>>): Throwable? =
        states.firstOrNull { !it.isRefreshing && it.failure != null }?.failure

    /** [term]'s forums of course [courseId]; none while the copy does not cover the course. */
    fun courseForums(term: MoodleTermForums?, courseId: Int): List<MoodleForum> {
        if (term == null || courseId !in term.courseIds) return emptyList()
        return term.forums.filter { it.courseId == courseId }
    }

    fun newsForum(term: MoodleTermForums?, courseId: Int): MoodleForum? =
        courseForums(term, courseId).firstOrNull { it.isNews }

    /** The course's other forums that have something in them. */
    fun generalForums(term: MoodleTermForums?, courseId: Int): List<MoodleForum> =
        courseForums(term, courseId).filter { !it.isNews && it.discussionCount > 0 }

    /**
     * The section's rows, in its order; a kind with nothing to count has no row. [assignmentCount]
     * is every assignment of the course the last round recorded, undated ones and ones with no
     * online submission included; it follows the materials, as iOS orders the rows.
     */
    fun rows(
        term: MoodleTermForums?,
        courseId: Int,
        grades: MoodleCourseGrades?,
        members: MoodleMemberList?,
        materials: MoodleCourseMaterials? = null,
        assignmentCount: Int = 0,
    ): List<MoodleSectionRow> {
        val rows = mutableListOf<MoodleSectionRow>()
        newsForum(term, courseId)?.takeIf { it.discussionCount > 0 }?.let {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, it.discussionCount, it.unreadPostCount > 0)
        }
        materials?.let(MoodleMaterials::count)?.takeIf { it > 0 }?.let {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.MATERIALS, it, false)
        }
        if (assignmentCount > 0) {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.ASSIGNMENTS, assignmentCount, false)
        }
        val graded = grades?.let(MoodleGrades::graded)?.size ?: 0
        // A course total shown is worth the row on its own, graded activities shown or none.
        if (graded > 0 || grades?.let(MoodleGrades::total) != null) {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.GRADES, graded, false)
        }
        if (members != null && MoodleMembers.showsOthers(members)) {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.MEMBERS, members.members.size, false)
        }
        val general = generalForums(term, courseId)
        if (general.isNotEmpty()) {
            rows += MoodleSectionRow(
                MoodleSectionRow.Kind.FORUMS,
                general.sumOf { it.discussionCount },
                general.any { it.unreadPostCount > 0 },
            )
        }
        return rows
    }

    /**
     * Whether a summary says something the course detail does not: it has a letter or Han
     * character, and it is not just a name of the course or one of [codes], ignoring case and
     * spaces. An NTUST course's summary can be its idnumber and nothing else.
     */
    fun isWorthShowing(summary: MoodleCourseSummary, codes: List<String>): Boolean {
        if (summary.text.none { it.isLetter() }) return false
        val text = comparable(summary.text)
        return (summary.names + codes).none { comparable(it) == text }
    }

    private fun comparable(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()
}

object MoodleDiscussions {

    /** Pinned first, then newest first by [byModified]'s date: the latest post's, else the posting time. */
    fun sorted(list: MoodleDiscussionList, byModified: Boolean = false): List<MoodleDiscussion> =
        list.discussions.sortedWith(
            compareByDescending<MoodleDiscussion> { it.isPinned }
                .thenByDescending { if (byModified) it.modified else it.created }
                .thenByDescending { it.id },
        )

    /** [list] with [page] added: discussions already listed are not listed twice. */
    fun append(list: MoodleDiscussionList, page: List<MoodleDiscussion>): MoodleDiscussionList {
        val known = list.discussions.map { it.id }.toSet()
        return list.copy(
            discussions = list.discussions + page.filter { it.id !in known },
            pageCount = list.pageCount + 1,
            hasMore = page.size == MoodleCourseApi.DISCUSSIONS_PER_PAGE,
        )
    }

    fun empty(forumId: Int) = MoodleDiscussionList(forumId, emptyList(), pageCount = 0, hasMore = false)
}

object MoodleGrades {
    fun total(grades: MoodleCourseGrades): MoodleGradeItem? =
        grades.items.firstOrNull { it.isCourseTotal && it.isShown }

    /** What the grades screen lists and its row counts: graded and shown, without totals. */
    fun graded(grades: MoodleCourseGrades): List<MoodleGradeItem> =
        grades.items.filter { !it.isCourseTotal && !it.isCategoryTotal && it.isShown }

    private val NUMBER = Regex("-?[0-9]+([.,][0-9]+)?")

    /**
     * The most the grade could be, written only beside a grade that is a number: over a letter, a
     * percentage or a scale's word, the maximum would misread. Its digits follow Moodle's,
     * whatever the device's region.
     */
    fun maxText(item: MoodleGradeItem): String? {
        val max = item.maxGrade ?: return null
        val grade = item.grade ?: return null
        if (!NUMBER.matches(grade)) return null
        val rounded = Math.round(max * 100) / 100.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }
    }

    private val MODULE = Regex("[a-z0-9_]+")

    /** The item's activity page, `/mod/<module>/view.php?id=<cmid>`; null for one no activity gives. */
    fun activityPath(item: MoodleGradeItem): String? {
        val module = item.module ?: return null
        val cmid = item.cmid ?: return null
        if (!MODULE.matches(module)) return null
        return "/mod/$module/view.php?id=$cmid"
    }
}

/** A Moodle user as the app names them: NTUST's Moodle spells a full name "student ID @ name". */
object MoodlePersonName {
    fun displayName(fullName: String): String {
        val trimmed = fullName.trim()
        val at = trimmed.indexOf('@')
        if (at < 0) return trimmed
        return trimmed.substring(at + 1).trim().ifEmpty { trimmed }
    }

    /** The part before the `@`, for a user whose `idnumber` Moodle leaves out. */
    fun studentId(fullName: String): String? {
        val at = fullName.indexOf('@')
        if (at < 0) return null
        return fullName.substring(0, at).trim().ifEmpty { null }
    }
}

val MoodleMember.name: String get() = MoodlePersonName.displayName(fullName)
val MoodleMember.studentId: String? get() = idNumber ?: MoodlePersonName.studentId(fullName)

/** Where a member is listed. A role the app does not know goes after the assistants, under Moodle's name for it. */
sealed class MoodleMemberPlacement : Comparable<MoodleMemberPlacement> {
    data object Instructors : MoodleMemberPlacement()
    data object Assistants : MoodleMemberPlacement()
    data class Other(val sortOrder: Int, val name: String) : MoodleMemberPlacement()
    data object Auditors : MoodleMemberPlacement()
    data object Students : MoodleMemberPlacement()

    private val rank: Int
        get() = when (this) {
            Instructors -> 0
            Assistants -> 1
            is Other -> 2
            Auditors -> 3
            Students -> 4
        }

    override fun compareTo(other: MoodleMemberPlacement): Int {
        if (rank != other.rank) return rank.compareTo(other.rank)
        if (this is Other && other is Other) {
            return compareValuesBy(this, other, { it.sortOrder }, { it.name })
        }
        return 0
    }

    /** Staff by name; auditors and students by student ID, as a class list runs. */
    val listsByStudentId: Boolean get() = this == Auditors || this == Students

    companion object {
        fun of(role: MoodleMemberRole): MoodleMemberPlacement = when (role.shortName) {
            "editingteacher" -> Instructors
            "teacher" -> Assistants
            "auditor_student" -> Auditors
            "student" -> Students
            else -> Other(role.sortOrder, role.name.ifEmpty { role.shortName })
        }

        /**
         * A person with several roles shows once, in the first section of them. One with none is
         * listed with the students, as Moodle counts every enrolled person a participant.
         */
        fun of(member: MoodleMember): MoodleMemberPlacement =
            member.roles.map(::of).minOrNull() ?: Students
    }
}

data class MoodleMemberSection(val placement: MoodleMemberPlacement, val members: List<MoodleMember>)

object MoodleMembers {

    /** Whether anyone but the account is there to show, which a course split into groups may not have. */
    fun showsOthers(list: MoodleMemberList): Boolean = list.members.any { it.id != list.ownId }

    /** The search runs here, never on Moodle, over what a row shows. */
    fun sections(members: List<MoodleMember>, query: String = "", locale: Locale = Locale.getDefault()): List<MoodleMemberSection> {
        val needle = query.trim()
        val shown = if (needle.isEmpty()) members else members.filter { matches(it, needle) }
        val collator = Collator.getInstance(locale)
        val byName = Comparator<MoodleMember> { a, b ->
            collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
        // A person without a student ID comes after those with one.
        val byStudentId = Comparator<MoodleMember> { a, b ->
            val left = a.studentId
            val right = b.studentId
            when {
                left != null && right != null && left != right -> left.compareTo(right)
                left != null && right == null -> -1
                left == null && right != null -> 1
                else -> byName.compare(a, b)
            }
        }
        return shown.groupBy(MoodleMemberPlacement::of)
            .toSortedMap()
            .map { (placement, people) ->
                MoodleMemberSection(placement, people.sortedWith(if (placement.listsByStudentId) byStudentId else byName))
            }
    }

    /** A member's [groups] in one line: Chinese and Japanese list with 、, other languages with a comma. */
    fun groupsText(groups: List<String>, locale: Locale): String =
        groups.joinToString(if (locale.language in setOf("zh", "ja")) "、" else ", ")

    private fun matches(member: MoodleMember, needle: String): Boolean =
        listOfNotNull(member.name, member.studentId, member.department)
            .any { it.contains(needle, ignoreCase = true) }
}

/** Who a file belongs to, which keeps two files of one name apart on the device. */
sealed interface MoodleFileOwner {
    /** The folder the owner's files are kept in, under the course's. */
    val folderName: String

    /** A course module: a resource or a folder on the course's page. */
    data class Module(val id: Int) : MoodleFileOwner {
        override val folderName: String get() = "m$id"
    }

    /** A forum post, an announcement's included. */
    data class Post(val id: Int) : MoodleFileOwner {
        override val folderName: String get() = "p$id"
    }

    /** The account's submission for an assignment, whose files the assignment's page lists. */
    data class Submission(val assignId: Int) : MoodleFileOwner {
        override val folderName: String get() = "s$assignId"
    }
}

/** A file with its owner: the owner is part of where its copy is kept. */
data class MoodleFileRef(val file: MoodleFile, val owner: MoodleFileOwner)

/** A row of a section of the materials screen. */
sealed interface MoodleMaterialRow {
    val material: MoodleMaterial
    val key: String

    /** A resource's file, or one of a folder's, listed under it. */
    data class File(override val material: MoodleMaterial, val file: MoodleFile, val inFolder: Boolean) : MoodleMaterialRow {
        override val key: String get() = "f${material.id}${file.path ?: "/"}${file.name}"
        val ref: MoodleFileRef get() = MoodleFileRef(file, MoodleFileOwner.Module(material.id))
    }

    data class Link(override val material: MoodleMaterial, val url: String) : MoodleMaterialRow {
        override val key: String get() = "l${material.id}"
    }

    /** Not a button: a folder only heads the files listed under it. */
    data class Folder(override val material: MoodleMaterial, val fileCount: Int) : MoodleMaterialRow {
        override val key: String get() = "d${material.id}"
    }
}

object MoodleMaterials {

    /** What the course detail counts: files and links, a folder by the files in it. */
    fun count(materials: MoodleCourseMaterials): Int =
        materials.sections.sumOf { section -> section.materials.sumOf { if (it.isLink) 1 else it.files.size } }

    fun fileRefs(material: MoodleMaterial): List<MoodleFileRef> =
        material.files.map { MoodleFileRef(it, MoodleFileOwner.Module(material.id)) }

    fun fileRefs(materials: MoodleCourseMaterials): List<MoodleFileRef> =
        materials.sections.flatMap { section -> section.materials.flatMap(::fileRefs) }

    /** [section]'s rows: a folder heads its files, which are listed under it. */
    fun rows(section: MoodleMaterialSection): List<MoodleMaterialRow> = section.materials.flatMap { material ->
        when {
            material.isLink -> listOfNotNull(material.link?.let { MoodleMaterialRow.Link(material, it) })
            material.isFolder -> listOf(MoodleMaterialRow.Folder(material, material.files.size)) +
                material.files.map { MoodleMaterialRow.File(material, it, inFolder = true) }
            else -> material.files.take(1).map { MoodleMaterialRow.File(material, it, inFolder = false) }
        }
    }

    /** The call that logs a view of each kind of module, with the name it takes the instance by. */
    val VIEW_CALLS: Map<String, Pair<String, String>> = mapOf(
        MoodleMaterial.RESOURCE to ("mod_resource_view_resource" to "resourceid"),
        MoodleMaterial.FOLDER to ("mod_folder_view_folder" to "folderid"),
        MoodleMaterial.URL to ("mod_url_view_url" to "urlid"),
    )

    /** A link's site for its row: the host without its `www.`, which says where it goes in fewer characters. */
    fun site(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return url
        return if (host.lowercase().startsWith("www.")) host.substring(4) else host
    }
}

/** A post as a thread shows it: how deep it is indented, and whom a reply deeper than that answers. */
data class MoodleThreadEntry(val post: MoodlePost, val depth: Int, val replyingTo: String?)

object MoodleThreads {
    /** Replies are indented this many levels at most; deeper ones say whom they answer. */
    const val MAX_DEPTH = 3

    /** The first post as a discussion list gives it, which does not say what is unread in it. */
    fun firstPost(discussion: MoodleDiscussion) = MoodlePost(
        id = discussion.postId,
        parentId = null,
        subject = discussion.title,
        message = discussion.message,
        created = discussion.created.takeIf { it > 0 },
        authorName = discussion.authorName,
        authorId = discussion.authorId,
        attachments = discussion.attachments,
        isUnread = false,
        isDeleted = false,
    )

    /**
     * [thread]'s posts in reading order, each reply under the post it answers. Missing parents and
     * parents that answer each other in a loop must not hide a reply. The cached first post keeps
     * a discussion readable before its thread arrives.
     */
    fun entries(discussion: MoodleDiscussion, thread: MoodleDiscussionThread?): List<MoodleThreadEntry> {
        val posts = thread?.posts.orEmpty()
        if (posts.isEmpty()) return listOf(MoodleThreadEntry(firstPost(discussion), 0, null))
        val ids = posts.map { it.id }.toSet()
        val replies = mutableMapOf<Int, MutableList<MoodlePost>>()
        val roots = mutableListOf<MoodlePost>()
        for (post in posts) {
            val parent = post.parentId
            if (parent != null && parent != post.id && parent in ids) {
                replies.getOrPut(parent) { mutableListOf() } += post
            } else {
                roots += post
            }
        }
        val entries = mutableListOf<MoodleThreadEntry>()
        val shown = mutableSetOf<Int>()
        fun add(post: MoodlePost, level: Int, answering: MoodlePost?) {
            if (!shown.add(post.id)) return
            val name = answering?.authorName?.let(MoodlePersonName::displayName)?.takeIf { it.isNotEmpty() }
            entries += MoodleThreadEntry(post, minOf(level, MAX_DEPTH), if (level > MAX_DEPTH) name else null)
            replies[post.id]?.forEach { add(it, level + 1, post) }
        }
        roots.forEachIndexed { index, root -> add(root, if (index == 0) 0 else 1, null) }
        // Posts whose parents answer each other in a loop reach no root; they still show.
        posts.filter { it.id !in shown }.forEach { add(it, 1, null) }
        return entries
    }
}

/** What a Moodle file holds, for its icon: by MIME type, else by extension. */
enum class MoodleFileKind {
    PDF, DOCUMENT, PRESENTATION, SPREADSHEET, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, OTHER;

    companion object {
        fun of(mimeType: String?, fileName: String): MoodleFileKind =
            byType(mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty())
                ?: byExtension(fileName.substringAfterLast('.', "").lowercase())
                ?: OTHER

        private fun byType(type: String): MoodleFileKind? = when {
            type.isEmpty() -> null
            type == "application/pdf" -> PDF
            "presentation" in type || "powerpoint" in type -> PRESENTATION
            "spreadsheet" in type || "excel" in type || type == "text/csv" -> SPREADSHEET
            "wordprocessing" in type || type == "application/msword" -> DOCUMENT
            type.startsWith("image/") -> IMAGE
            type.startsWith("video/") -> VIDEO
            type.startsWith("audio/") -> AUDIO
            type in ARCHIVE_TYPES -> ARCHIVE
            type == "text/plain" -> TEXT
            else -> null
        }

        private fun byExtension(extension: String): MoodleFileKind? = when (extension) {
            "pdf" -> PDF
            "doc", "docx", "odt", "rtf", "pages" -> DOCUMENT
            "ppt", "pptx", "odp", "key" -> PRESENTATION
            "xls", "xlsx", "ods", "csv", "numbers" -> SPREADSHEET
            "png", "jpg", "jpeg", "gif", "heic", "webp", "bmp", "tif", "tiff", "svg" -> IMAGE
            "mp4", "mov", "m4v", "avi", "mkv", "webm" -> VIDEO
            "mp3", "m4a", "wav", "aac", "flac", "ogg" -> AUDIO
            "zip", "rar", "7z", "gz", "tar", "tgz" -> ARCHIVE
            "txt", "md" -> TEXT
            else -> null
        }

        private val ARCHIVE_TYPES = setOf(
            "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
            "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-tar",
        )
    }
}
