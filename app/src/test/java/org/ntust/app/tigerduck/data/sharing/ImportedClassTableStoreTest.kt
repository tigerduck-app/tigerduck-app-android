package org.ntust.app.tigerduck.data.sharing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.shared.Course
import java.io.File
import java.time.Instant
import org.ntust.app.tigerduck.util.JvmContext
import android.content.Context

/**
 * [ImportedClassTableStore] over its real file, in each test's own app folders: the order
 * and replacement rules, the shown person, the file it keeps, and when it empties.
 */
class ImportedClassTableStoreTest {

    private val context: Context = JvmContext()
    private val file get() = ImportedClassTableStore.fileIn(context)

    /** The student ID the store reads as signed in; a test changes it to sign another account in. */
    private var account: String? = "B11300000"

    private fun store() = ImportedClassTableStore({ file }, { account }, Dispatchers.Unconfined)

    private fun code(studentId: String?, vararg terms: Term, sharedAt: Instant? = null) =
        ClassTableShareCode(if (studentId == null) Format.TAT2 else Format.TIGER_DUCK, studentId, sharedAt, terms.toList())

    private val fall = Term("1151", listOf("CS1010001", "MA1010001"))
    private val spring = Term("1142", listOf("EE1010002"))

    private fun ImportedClassTableStore.nicknames() = tables.value.people.map { it.nickname }

    @Test
    fun `the file is in the no-backup folder`() {
        assertEquals(context.noBackupFilesDir, file.parentFile)
    }

    @Test
    fun `people stay in the order they were imported`() = runTest {
        val store = store()

        store.add(code("B11300001", fall), "Ming", emptyMap())
        store.add(code("B11300002", fall), "B11300002", emptyMap())
        store.add(code(null, spring), "Hua", emptyMap())

        assertEquals(listOf("Ming", "B11300002", "Hua"), store.nicknames())
        assertTrue(store.tables.value.hasImportedClassTables)
    }

    @Test
    fun `importing a student ID again replaces its terms and keeps its nickname, entry and place`() = runTest {
        val store = store()
        val first = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.add(code("B11300002", fall), "Hua", emptyMap())
        val sharedAt = Instant.ofEpochSecond(1_791_871_500)
        val importedAt = Instant.ofEpochSecond(1_791_900_000)

        val again = store.add(
            code("B11300001", spring, sharedAt = sharedAt),
            "Someone else",
            mapOf("1142" to listOf(Course(courseNo = "EE1010002", courseName = "Circuits"))),
            importedAt,
        )

        assertEquals(listOf("Ming", "Hua"), store.nicknames())
        assertEquals(first.id, again?.id)
        val replaced = store.tables.value.person("B11300001")!!
        assertEquals(first.id, replaced.id)
        assertEquals(sharedAt, replaced.sharedAt)
        assertEquals(importedAt, replaced.importedAt)
        assertEquals(listOf("1142"), replaced.terms.map { it.semester })
        assertEquals(listOf("EE1010002"), replaced.terms.single().courseNos)
        assertEquals(listOf("Circuits"), replaced.terms.single().courses.map { it.courseName })
    }

    @Test
    fun `a new person needs a nickname, an update keeps its own, and a code without a student ID is always new`() = runTest {
        val store = store()

        assertNull(store.add(code("B11300001", fall), "  \n", emptyMap()))
        store.add(code("B11300001", fall), "Ming", emptyMap())
        assertNotNull(store.add(code("B11300001", spring), "", emptyMap()))
        store.add(code(null, fall), "Hua", emptyMap())
        store.add(code(null, fall), " Hua ", emptyMap())

        assertEquals(listOf("Ming", "Hua", "Hua"), store.nicknames())
    }

    @Test
    fun `rename changes the nickname but not to a blank one, and delete removes the person`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

        store.rename(ming.id, " Xiao Ming ")
        store.rename(hua.id, " ")
        assertEquals(listOf("Xiao Ming", "Hua"), store.nicknames())

        store.delete(ming.id)
        assertEquals(listOf(hua.id), store.tables.value.people.map { it.id })
        assertEquals(listOf("Hua"), store().load().people.map { it.nickname })
    }

    @Test
    fun `deleting the shown person shows the user's own class table again, as a relaunch does`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

        store.show(hua.id)
        store.show("someone never imported")
        assertEquals(hua.id, store.tables.value.shownPerson?.id)
        assertNull(store().load().shownPersonId)

        store.delete(ming.id)
        assertEquals(hua.id, store.tables.value.shownPersonId)
        store.delete(hua.id)
        assertNull(store.tables.value.shownPersonId)
        assertNull(store.tables.value.shownPerson)
    }

    @Test
    fun `a new store reads back everything the last one kept`() = runTest {
        val store = store()
        val importedAt = Instant.ofEpochMilli(1_791_900_000_500)
        val sharedAt = Instant.ofEpochSecond(1_791_871_500)
        val course = Course.fromSchedule(
            courseNo = "CS1010001",
            // Data structures, in Chinese, as most course names and nicknames are.
            courseName = "\u8CC7\u6599\u7D50\u69CB",
            instructor = "Teacher",
            credits = 3f,
            classroom = "TR-313",
            schedule = mapOf(2 to listOf("3", "4")),
            classroomMap = mapOf("2-3" to "TR-313", "2-4" to "TR-313"),
        )
        store.add(
            code("B11300001", fall, spring, sharedAt = sharedAt), "\u5C0F\u660E", mapOf("1151" to listOf(course)), importedAt,
        )
        store.add(code(null, spring), "Hua", emptyMap(), importedAt)

        val reloaded = store().load().people

        assertEquals(store.tables.value.people, reloaded)
        assertEquals(listOf("\u5C0F\u660E", "Hua"), reloaded.map { it.nickname })
        assertEquals(listOf("B11300001", null), reloaded.map { it.studentId })
        assertEquals(listOf(importedAt, importedAt), reloaded.map { it.importedAt })
        assertEquals(listOf(sharedAt, null), reloaded.map { it.sharedAt })
        val terms = reloaded.first().terms
        assertEquals(listOf("1151", "1142"), terms.map { it.semester })
        assertEquals(listOf(listOf("CS1010001", "MA1010001"), listOf("EE1010002")), terms.map { it.courseNos })
        val detail = terms.first().courses.single()
        assertEquals("\u8CC7\u6599\u7D50\u69CB", detail.courseName)
        assertEquals(mapOf(2 to listOf("3", "4")), detail.schedule)
        assertEquals("TR-313", detail.classroom(2, "4"))
        assertTrue(terms.last().courses.isEmpty())
    }

    @Test
    fun `lists the caller changes after an import do not change the person imported`() = runTest {
        val store = store()
        val courseNos = mutableListOf("CS1010001")
        val courses = mutableListOf(Course(courseNo = "CS1010001", courseName = "Circuits"))

        store.add(code("B11300001", Term("1151", courseNos)), "Ming", mapOf("1151" to courses))
        courseNos.clear()
        courses.clear()

        val term = store.tables.value.people.single().terms.single()
        assertEquals(listOf("CS1010001"), term.courseNos)
        assertEquals(listOf("Circuits"), term.courses.map { it.courseName })
        assertEquals(store.tables.value.people, store().load().people)
    }

    @Test
    fun `a file that does not decode, as one from a later layout might not, is not written over`() = runTest {
        for (kept in listOf(
            """{"version":1,"people":"written by a later version"}""",
            """{"version":2,"owner":null,"people":[]}""",
            """{"version":1,"owner":null,"people":[{"id":"x","importedAtMillis":0,"terms":[]}]}""",
            "not JSON",
        )) {
            file.parentFile!!.mkdirs()
            file.writeText(kept)
            val store = store()

            // Refused rather than kept in memory only, where a relaunch would lose it.
            assertNull(store.add(code("B11300001", fall), "Ming", emptyMap()))

            assertEquals(kept, file.readText())
            assertTrue(store.nicknames().isEmpty())
        }
    }

    @Test
    fun `a file that cannot be read is not written over`() = runTest {
        val kept = """{"version":1,"owner":null,"people":[]}"""
        file.parentFile!!.mkdirs()
        file.writeText(kept)
        // Skipped where the test runs as a user who reads every file anyway.
        assumeTrue(file.setReadable(false) && !file.canRead())
        try {
            assertNull(store().add(code("B11300001", fall), "Ming", emptyMap()))
        } finally {
            file.setReadable(true)
        }

        assertEquals(kept, file.readText())
    }

    // --- When the store empties ---

    @Test
    fun `signing out empties the store and its file`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.show(ming.id)

        store.clearForSignOut()

        assertEquals(ImportedClassTables(), store.tables.value)
        assertFalse(store.tables.value.hasImportedClassTables)
        assertFalse(file.exists())
        assertTrue(store().load().people.isEmpty())
    }

    @Test
    fun `a sign-out before the store was read still deletes what an earlier run kept`() = runTest {
        store().add(code("B11300001", fall), "Ming", emptyMap())

        store().clearForSignOut()

        assertFalse(file.exists())
    }

    // A process death between the write's temporary file and its rename leaves that file, which
    // holds the departing student's classmates.
    @Test
    fun `a sign-out deletes what a write cut short left behind`() = runTest {
        val unfinished = File(file.parentFile, "${file.name}.tmp").apply { writeText("{}") }

        store().clearForSignOut()

        assertFalse(unfinished.exists())
    }

    @Test
    fun `a file whose account is no longer the one signed in is emptied, not read back`() = runTest {
        // The app stopped before a sign-out emptied the store, or before another account's sign-in did.
        for (next in listOf(null, "B11399999")) {
            account = "B11300000"
            store().add(code("B11300001", fall), "Ming", emptyMap())

            account = next
            assertTrue(store().load().people.isEmpty())
            assertFalse(file.exists())
        }
    }

    @Test
    fun `people imported while no one was signed in are read back`() = runTest {
        account = null
        store().add(code("B11300001", fall), "Ming", emptyMap())

        assertEquals(listOf("Ming"), store().load().people.map { it.nickname })
        account = "B11300000"
        assertEquals(listOf("Ming"), store().load().people.map { it.nickname })
    }

    @Test
    fun `another student ID signing in empties the store, and the same one signing in again keeps it`() = runTest {
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        store.accountDidSignIn("B11300000")
        assertEquals(1, store.tables.value.people.size)
        assertEquals(1, store().load().people.size)

        store.accountDidSignIn("B11399999")
        assertTrue(store.tables.value.people.isEmpty())
        assertTrue(store().load().people.isEmpty())
    }

    @Test
    fun `a store owned by one account is read as that account's after a relaunch`() = runTest {
        store().add(code("B11300001", fall), "Ming", emptyMap())

        val relaunched = store()
        relaunched.accountDidSignIn("B11399999")

        assertTrue(relaunched.tables.value.people.isEmpty())
    }

    @Test
    fun `people imported while signed out stay with the first account that signs in`() = runTest {
        account = null
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        store.accountDidSignIn("B11300000")
        assertEquals(1, store.tables.value.people.size)

        store.accountDidSignIn("B11399999")
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `once its last person is deleted, the store belongs to whoever imports next`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.delete(ming.id)

        account = "B11399999"
        store.accountDidSignIn("B11399999")
        store.add(code("B11300002", fall), "Hua", emptyMap())
        store().accountDidSignIn("B11399999")

        assertEquals(listOf("Hua"), store().load().people.map { it.nickname })
    }

    @Test
    fun `an import under another account than the store's drops the earlier account's people`() = runTest {
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        account = "B11399999"
        store.add(code("B11300002", fall), "Hua", emptyMap())

        assertEquals(listOf("Hua"), store.nicknames())
    }

    // --- Saves that fail (#213) ---

    @Test
    fun `an import whose first write is blocked reports it, keeps nothing, and a retry once unblocked keeps the person`() =
        runTest {
            // A folder that cannot be made, as a file already has its name.
            val obstacle = File(context.noBackupFilesDir, "obstacle").apply { parentFile!!.mkdirs(); writeText("") }
            val blocked = File(obstacle, "imported_class_tables.json")
            val store = ImportedClassTableStore({ blocked }, { account }, Dispatchers.Unconfined)

            assertNull(store.add(code("B11300001", fall), "Ming", emptyMap()))
            assertTrue(store.nicknames().isEmpty())
            assertFalse(blocked.exists())

            assertTrue(obstacle.delete())
            assertNotNull(store.add(code("B11300001", fall), "Ming", emptyMap()))
            assertEquals(listOf("Ming"), store.nicknames())
            val relaunched = ImportedClassTableStore({ blocked }, { account }, Dispatchers.Unconfined)
            assertEquals(listOf("Ming"), relaunched.load().people.map { it.nickname })
        }

    @Test
    fun `a failed replacement of the file leaves the people, the shown one and the file as they were`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.show(ming.id)
        val before = store.tables.value
        // A folder with something in it where the file goes, which the written copy cannot be moved over.
        assertTrue(file.delete())
        assertTrue(File(file, "kept").apply { parentFile!!.mkdirs() }.createNewFile())

        assertNull(store.add(code("B11300002", spring), "Hua", emptyMap()))
        assertNull(store.add(code("B11300001", spring), "", emptyMap()))
        assertEquals(ImportedClassTableChange.FAILED, store.rename(ming.id, "Xiao Ming"))
        assertEquals(
            ImportedClassTableChange.FAILED,
            store.updateCourses(ming.id, "1151", fall.courseNos, emptyList(), ming.importedAt),
        )
        assertEquals(ImportedClassTableChange.FAILED, store.delete(ming.id))

        assertEquals(before, store.tables.value)
        assertTrue(file.isDirectory)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())

        // Once the obstacle is gone the same changes go through, and a relaunch reads them back.
        assertTrue(file.deleteRecursively())
        assertEquals(ImportedClassTableChange.SAVED, store.rename(ming.id, "Xiao Ming"))
        assertEquals(listOf("Xiao Ming"), store().load().people.map { it.nickname })
    }

    @Test
    fun `changes to someone no longer here are skipped, not failed`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.delete(ming.id)

        assertEquals(ImportedClassTableChange.SKIPPED, store.rename(ming.id, "Xiao Ming"))
        assertEquals(ImportedClassTableChange.SKIPPED, store.delete(ming.id))
        assertEquals(
            ImportedClassTableChange.SKIPPED,
            store.updateCourses(ming.id, "1151", fall.courseNos, emptyList(), ming.importedAt),
        )
    }

    // --- Looking a term up again ---

    @Test
    fun `a new lookup replaces that term's courses and keeps the rest`() = runTest {
        val store = store()
        val circuits = Course(courseNo = "EE1010002", courseName = "Circuits")
        val ming = store.add(
            code("B11300001", fall, spring), "Ming", mapOf("1142" to listOf(circuits)),
        )!!
        val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")

        val change = store.updateCourses(ming.id, "1151", fall.courseNos, listOf(calculus), ming.importedAt)

        assertEquals(ImportedClassTableChange.SAVED, change)
        val person = store().load().people.single()
        assertEquals(listOf("Calculus"), person.courses("1151").map { it.courseName })
        assertEquals(listOf("Circuits"), person.courses("1142").map { it.courseName })
        assertEquals(ming.importedAt, person.importedAt)
    }

    @Test
    fun `a lookup that started before the person was imported again is skipped`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap(), Instant.ofEpochSecond(1_000))!!
        store.add(code("B11300001", fall), "", emptyMap(), Instant.ofEpochSecond(2_000))
        val stale = Course(courseNo = "CS1010001", courseName = "Old")

        val change = store.updateCourses(ming.id, "1151", fall.courseNos, listOf(stale), ming.importedAt)

        assertEquals(ImportedClassTableChange.SKIPPED, change)
        assertTrue(store.tables.value.people.single().courses("1151").isEmpty())
    }

    // --- Switching ---

    @Test
    fun `next goes through the user's own class table and then each person in import order`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

        val shown = (1..4).map {
            store.showNext()
            store.tables.value.shownPersonId
        }

        assertEquals(listOf(ming.id, hua.id, null, ming.id), shown)
    }

    @Test
    fun `previous swaps between the last two class tables, and goes on to the next with none to go back to`() =
        runTest {
            val store = store()
            val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
            val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

            // Before the first switch there is nothing to go back to.
            store.showPrevious()
            assertEquals(ming.id, store.tables.value.shownPersonId)

            store.show(hua.id)
            store.showPrevious()
            assertEquals(ming.id, store.tables.value.shownPersonId)
            store.showPrevious()
            assertEquals(hua.id, store.tables.value.shownPersonId)

            // Back to the user's own, which counts as a class table to go back to.
            store.show(null)
            store.showPrevious()
            assertEquals(hua.id, store.tables.value.shownPersonId)
            store.showPrevious()
            assertNull(store.tables.value.shownPersonId)

            // Once the one to go back to is deleted, the next one shows.
            store.show(ming.id)
            store.show(hua.id)
            store.delete(ming.id)
            store.showPrevious()
            assertNull(store.tables.value.shownPersonId)
        }

    @Test
    fun `deleting the shown person makes it the one to go back to, which then is gone`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.add(code("B11300002", fall), "Hua", emptyMap())
        store.show(ming.id)

        store.delete(ming.id)
        assertNull(store.tables.value.shownPersonId)
        store.showPrevious()

        assertEquals("Hua", store.tables.value.shownPerson?.nickname)
    }

    @Test
    fun `a person opens on the term on screen when they shared it, else on their latest`() {
        val person = ImportedPerson(
            id = "x", studentId = null, nickname = "Ming", importedAt = Instant.EPOCH, sharedAt = null,
            terms = listOf(
                ImportedPerson.Term("1142", emptyList(), emptyList()),
                ImportedPerson.Term("114H", emptyList(), emptyList()),
                ImportedPerson.Term("1151", emptyList(), emptyList()),
            ),
        )

        assertEquals(listOf("1151", "114H", "1142"), person.semesters)
        assertEquals("1142", person.semester(keeping = "1142"))
        assertEquals("1151", person.semester(keeping = "1132"))
    }

    @Test
    fun `the sign-out asks first only when it would delete imported class tables`() = runTest {
        val store = store()
        val asked = mutableListOf<String>()

        store.tables.value.requestSignOut(confirm = { asked += "confirm" }, signOut = { asked += "sign out" })
        store.add(code("B11300001", fall), "Ming", emptyMap())
        store.tables.value.requestSignOut(confirm = { asked += "confirm" }, signOut = { asked += "sign out" })

        assertEquals(listOf("sign out", "confirm"), asked)
    }
}

