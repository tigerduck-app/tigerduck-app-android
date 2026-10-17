package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.util.Locale

class MoodleLogicTest {

    private fun grade(id: Int, type: String = "mod", grade: String? = "90", hidden: Boolean = false, max: Double? = 100.0) =
        MoodleGradeItem(id, "Item $id", type, "assign", 30 + id, grade, max, hidden, "")

    private fun member(id: Int, fullName: String, vararg roles: String, idNumber: String? = null) = MoodleMember(
        id = id, fullName = fullName, idNumber = idNumber, department = null, email = null,
        groups = emptyList(), roles = roles.map { MoodleMemberRole(it, "", 0) }, pictureUrl = null,
        description = "", interests = emptyList(), fields = emptyList(),
    )

    @Test
    fun `a section row shows only for a kind with something in it`() {
        val term = MoodleTermForums(
            listOf(5, 6),
            listOf(
                testForum(id = 1, courseId = 5, type = "news", count = 3, unread = 2),
                testForum(id = 2, courseId = 6, type = "news", count = 9, unread = 0),
            ),
        )
        val grades = MoodleCourseGrades(listOf(grade(1), grade(2, grade = null), grade(3, type = "course")))
        val onlyMe = MoodleMemberList(ownId = 1, members = listOf(member(1, "Me")))

        val rows = MoodleSections.rows(term, courseId = 5, grades = grades, members = onlyMe)

        assertEquals(
            listOf(
                MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, 3, hasUnread = true),
                MoodleSectionRow(MoodleSectionRow.Kind.GRADES, 1, hasUnread = false),
            ),
            rows,
        )
        assertTrue("a term copy that does not cover the course shows nothing",
            MoodleSections.rows(term, courseId = 7, grades = null, members = null).isEmpty())
    }

    @Test
    fun `a course with a total but no graded activity shown keeps its grades row`() {
        val onlyTotal = MoodleCourseGrades(listOf(grade(2, grade = null), grade(3, type = "course")))

        assertEquals(
            MoodleSectionRow(MoodleSectionRow.Kind.GRADES, 0, false),
            MoodleSections.rows(null, 5, onlyTotal, null).single(),
        )
        assertTrue(
            "nothing shown, total included, has no row",
            MoodleSections.rows(null, 5, MoodleCourseGrades(listOf(grade(3, grade = null, type = "course"))), null).isEmpty(),
        )
    }

    @Test
    fun `the members row needs someone besides the account`() {
        val list = MoodleMemberList(ownId = 1, members = listOf(member(1, "Me"), member(2, "You")))
        val rows = MoodleSections.rows(null, 5, null, list)
        assertEquals(MoodleSectionRow(MoodleSectionRow.Kind.MEMBERS, 2, false), rows.single())
    }

    @Test
    fun `a summary that only repeats the course's names or codes is not shown`() {
        val names = listOf("資料結構 Data Structures")
        assertFalse(MoodleSections.isWorthShowing(MoodleCourseSummary("", "1151CS3005301", emptyList()), listOf("1151CS3005301")))
        assertFalse(MoodleSections.isWorthShowing(MoodleCourseSummary("", "資料結構  data structures", names), emptyList()))
        assertFalse("digits only", MoodleSections.isWorthShowing(MoodleCourseSummary("", "2026", emptyList()), emptyList()))
        assertTrue(MoodleSections.isWorthShowing(MoodleCourseSummary("", "Weekly quizzes", names), listOf("CS3005301")))
    }

    @Test
    fun `discussions sort pinned first, then newest`() {
        val list = MoodleDiscussionList(
            1,
            listOf(
                testDiscussion(1, created = 100),
                testDiscussion(2, created = 300),
                testDiscussion(3, created = 50, pinned = true),
            ),
            pageCount = 1, hasMore = false,
        )
        assertEquals(listOf(3, 2, 1), MoodleDiscussions.sorted(list).map { it.id })
    }

    @Test
    fun `a full page means there may be more, and a repeat is not listed twice`() {
        val first = MoodleDiscussions.append(MoodleDiscussions.empty(1), (1..20).map { testDiscussion(it) })
        assertTrue(first.hasMore)
        assertEquals(1, first.pageCount)
        val second = MoodleDiscussions.append(first, listOf(testDiscussion(20), testDiscussion(21)))
        assertFalse(second.hasMore)
        assertEquals(21, second.discussions.size)
    }

    @Test
    fun `members group by role, staff by name and students by ID`() {
        val members = listOf(
            member(1, "B2 @ Zed", "student", idNumber = "B2"),
            member(2, "B1 @ Amy", "student", idNumber = "B1"),
            member(3, "Ng", "editingteacher"),
            member(4, "Lee", "teacher"),
            member(5, "Ann", "editingteacher", "student"),
            member(6, "Odd", "observer"),
            member(7, "No role"),
        )
        val sections = MoodleMembers.sections(members, locale = Locale.ENGLISH)

        assertEquals(
            listOf(
                MoodleMemberPlacement.Instructors,
                MoodleMemberPlacement.Assistants,
                MoodleMemberPlacement.Other(0, "observer"),
                MoodleMemberPlacement.Students,
            ),
            sections.map { it.placement },
        )
        assertEquals("a teacher who is also a student shows once, as a teacher",
            listOf(5, 3), sections[0].members.map { it.id })
        assertEquals("students by ID, one without after", listOf(2, 1, 7), sections[3].members.map { it.id })
    }

    @Test
    fun `the search matches name, student ID or the department name the row shows`() {
        val members = listOf(
            member(1, "B00015001 @ Amy", "student"),
            // Moodle's own field: the degree level, which the row no longer shows.
            member(2, "B00000002 @ Bob", "student").copy(department = "大學部"),
        )
        val names = mapOf("cs" to "Computer Science and Information Engineering")
        fun found(query: String) = MoodleMembers.sections(members, query, departmentNames = names).flatMap { it.members }.map { it.id }

        assertEquals(listOf(2), found("bob"))
        assertEquals(listOf(1), found("15001"))
        assertEquals("the name shown beside the ID", listOf(1), found("computer science"))
        assertTrue("Moodle's department field is not searched", found("大學部").isEmpty())
        assertTrue(found("zzz").isEmpty())
    }

    @Test
    fun `a bachelor's student ID names the department its code stands for`() {
        assertEquals("15", MoodleDepartment.code("B00015999"))
        assertEquals("cs", MoodleDepartment.key("B00015999"))
        // The 12 confirmed codes, and no other (#697).
        val confirmed = mapOf(
            "B00001999" to "im", "B00002999" to "et", "B00003999" to "me", "B00004999" to "tx",
            "B00005999" to "ct", "B00006999" to "ch", "B00007999" to "ee", "B00008999" to "ba",
            "B00010999" to "dt", "B00013999" to "ad", "B00015999" to "cs", "B00030999" to "hc",
        )
        assertEquals(confirmed, confirmed.keys.associateWith(MoodleDepartment::key))
        assertEquals(confirmed.values.toSet(), MoodleDepartment.keys)
    }

    @Test
    fun `only a bachelor's ID has a department code`() {
        for (id in listOf("M00015999", "D00015999", "T00015999", "b00015999")) {
            assertNull(id, MoodleDepartment.code(id))
            assertNull(id, MoodleDepartment.key(id))
        }
        assertNull(MoodleDepartment.key(null))
        assertNull(MoodleDepartment.key(""))
        assertNull("too short to carry a code", MoodleDepartment.key("B000"))
    }

    @Test
    fun `a code the app has no name for shows no department`() {
        assertEquals("09", MoodleDepartment.code("B00009999"))
        assertNull(MoodleDepartment.key("B00009999"))
        assertNull(MoodleDepartment.key("B00099999"))
    }

    @Test
    fun `an ID that is not B and eight digits has no department, whatever its 5th and 6th characters`() {
        // Cut short at 6, 7 and 8 characters, or one too long, each with a confirmed code in place.
        for (id in listOf("B00015", "B000159", "B0001599", "B000159999")) {
            assertNull(id, MoodleDepartment.code(id))
            assertNull(id, MoodleDepartment.key(id))
        }
        // Nine characters, but not digits after the B: letters, a space, or another script's digits.
        for (id in listOf("B000AB999", "BX0015999", "B0001599X", "B000 5999", "B000１５999")) {
            assertNull(id, MoodleDepartment.code(id))
            assertNull(id, MoodleDepartment.key(id))
        }
    }

    @Test
    fun `names read from NTUST's ID @ name form`() {
        assertEquals("Amy", MoodlePersonName.displayName("B00000001 @ Amy"))
        assertEquals("B00000001", MoodlePersonName.studentId("B00000001 @ Amy"))
        assertEquals("Teacher Wang", MoodlePersonName.displayName("Teacher Wang"))
        assertNull(MoodlePersonName.studentId("Teacher Wang"))
    }

    @Test
    fun `the maximum shows only beside a number`() {
        assertEquals("100", MoodleGrades.maxText(grade(1, grade = "95.00")))
        assertEquals("12.5", MoodleGrades.maxText(grade(1, grade = "10", max = 12.5)))
        assertNull(MoodleGrades.maxText(grade(1, grade = "A+")))
        assertNull(MoodleGrades.maxText(grade(1, grade = "95 %")))
    }

    @Test
    fun `an item from an activity opens it, a manual one has nothing to open`() {
        assertEquals("/mod/assign/view.php?id=31", MoodleGrades.activityPath(grade(1)))
        assertNull(MoodleGrades.activityPath(grade(1).copy(module = null)))
        assertNull(MoodleGrades.activityPath(grade(1).copy(module = "../x")))
    }

    @Test
    fun `groups are listed with the language's own separator`() {
        assertEquals("A 組、B 組", MoodleMembers.groupsText(listOf("A 組", "B 組"), Locale.TRADITIONAL_CHINESE))
        assertEquals("Team A, Team B", MoodleMembers.groupsText(listOf("Team A", "Team B"), Locale.ENGLISH))
    }

    @Test
    fun `the section lists announcements, materials, grades, members and forums, in that order`() {
        val term = MoodleTermForums(
            listOf(5),
            listOf(
                testForum(id = 1, courseId = 5, type = "news", count = 3),
                testForum(id = 2, courseId = 5, type = "general", count = 4, unread = 1),
                testForum(id = 3, courseId = 5, type = "general", count = 0),
            ),
        )
        val members = MoodleMemberList(ownId = 1, members = listOf(member(1, "Me"), member(2, "You")))

        val rows = MoodleSections.rows(term, 5, MoodleCourseGrades(listOf(grade(1))), members, testMaterials())

        assertEquals(
            listOf(
                MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, 3, false),
                MoodleSectionRow(MoodleSectionRow.Kind.MATERIALS, 4, false),
                MoodleSectionRow(MoodleSectionRow.Kind.GRADES, 1, false),
                MoodleSectionRow(MoodleSectionRow.Kind.MEMBERS, 2, false),
                MoodleSectionRow(MoodleSectionRow.Kind.FORUMS, 4, true),
            ),
            rows,
        )
    }

    @Test
    fun `a folder heads its files, and a link keeps its site without www`() {
        val sections = testMaterials().sections
        assertEquals(
            listOf("f101/syllabus.pdf", "l102"),
            MoodleMaterials.rows(sections[0]).map { it.key },
        )
        val week = MoodleMaterials.rows(sections[1])
        assertEquals(listOf("d103", "f103/a/lab1.zip", "f103/b/lab2.zip"), week.map { it.key })
        assertEquals(2, (week[0] as MoodleMaterialRow.Folder).fileCount)
        assertTrue((week[1] as MoodleMaterialRow.File).inFolder)
        assertEquals("example.com", MoodleMaterials.site("https://www.example.com/ds"))
        assertEquals(3, MoodleMaterials.fileRefs(testMaterials()).size)
    }

    @Test
    fun `replies nest under the post they answer, three levels at most`() {
        val discussion = testDiscussion(1)
        val posts = listOf(
            testPost(10),
            testPost(11, parent = 10),
            testPost(12, parent = 11),
            testPost(13, parent = 12),
            testPost(14, parent = 13),
            testPost(15, parent = 10),
        )
        val entries = MoodleThreads.entries(discussion, org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionThread(1, posts))

        assertEquals(listOf(10, 11, 12, 13, 14, 15), entries.map { it.post.id })
        assertEquals(listOf(0, 1, 2, 3, 3, 1), entries.map { it.depth })
        assertEquals("Author 13", entries[4].replyingTo)
        assertNull(entries[3].replyingTo)
    }

    @Test
    fun `a reply whose parent is missing or loops still shows`() {
        val posts = listOf(
            testPost(10),
            testPost(11, parent = 99),
            testPost(12, parent = 13),
            testPost(13, parent = 12),
        )
        val entries = MoodleThreads.entries(testDiscussion(1), org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionThread(1, posts))

        assertEquals(setOf(10, 11, 12, 13), entries.map { it.post.id }.toSet())
        assertEquals(4, entries.size)
        assertEquals(0, entries.first().depth)
    }

    @Test
    fun `before its thread arrives a discussion shows its first post from the list`() {
        val entries = MoodleThreads.entries(testDiscussion(7), null)

        assertEquals(70, entries.single().post.id)
        assertEquals("D7", entries.single().post.subject)
        assertFalse(entries.single().post.isUnread)
    }

    @Test
    fun `a file's kind comes from its type, else its extension`() {
        assertEquals(MoodleFileKind.PDF, MoodleFileKind.of("application/pdf", "x"))
        assertEquals(
            MoodleFileKind.PRESENTATION,
            MoodleFileKind.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "x"),
        )
        assertEquals(MoodleFileKind.SPREADSHEET, MoodleFileKind.of("document/unknown", "grades.XLSX"))
        assertEquals(MoodleFileKind.ARCHIVE, MoodleFileKind.of(null, "lab.zip"))
        assertEquals(MoodleFileKind.OTHER, MoodleFileKind.of("", "README"))
    }
}
