package org.ntust.app.tigerduck.data.sharing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Where the class table switcher lists each imported person: the double tap's rotation, the
 * long-press menu or Other, kept in the store's file. Ported from iOS `ImportedClassTableStoreTests`.
 */
class ImportedClassTablePlacementTest {

    private val context: Context = JvmContext()
    private val file get() = ImportedClassTableStore.fileIn(context)

    private fun store() = ImportedClassTableStore({ file }, { "B11300000" }, Dispatchers.Unconfined)

    private val fall = Term("1151", listOf("CS1010001"))

    private suspend fun ImportedClassTableStore.import(studentId: String, nickname: String) =
        add(ClassTableShareCode(Format.TIGER_DUCK, studentId, null, listOf(fall)), nickname, emptyMap())!!

    private fun ImportedClassTableStore.placements() = tables.value.people.map { it.nickname to it.placement }

    @Test
    fun `a new import goes in the rotation, and an import again, a rename and a new lookup keep its placement`() = runTest {
        val store = store()
        val ming = store.import("B11300001", "Ming")
        assertEquals(Placement.ROTATION, ming.placement)

        store.move(ming.id, Placement.OTHER, before = null)
        store.import("B11300001", "Someone else")
        store.rename(ming.id, "Xiao Ming")
        val importedAt = store.tables.value.people.single().importedAt
        store.updateCourses(ming.id, "1151", listOf("CS1010001"), listOf(Course(courseNo = "CS1010001", courseName = "C")), importedAt)

        assertEquals(listOf("Xiao Ming" to Placement.OTHER), store.placements())
    }

    @Test
    fun `a move puts someone right before another of that placement, or after everyone there`() = runTest {
        val store = store()
        val ming = store.import("B11300001", "Ming")
        val hua = store.import("B11300002", "Hua")
        val wen = store.import("B11300003", "Wen")
        val lin = store.import("B11300004", "Lin")

        assertEquals(ImportedClassTableChange.SAVED, store.move(wen.id, Placement.LONG_PRESS_MENU, before = null))
        store.move(ming.id, Placement.LONG_PRESS_MENU, before = wen.id)
        // Hua is not in the long-press menu, so Lin goes after everyone there.
        store.move(lin.id, Placement.LONG_PRESS_MENU, before = hua.id)
        assertEquals(
            listOf(
                "Hua" to Placement.ROTATION, "Ming" to Placement.LONG_PRESS_MENU,
                "Wen" to Placement.LONG_PRESS_MENU, "Lin" to Placement.LONG_PRESS_MENU,
            ),
            store.placements(),
        )
        assertEquals(ImportedClassTableChange.SKIPPED, store.move(hua.id, Placement.OTHER, before = hua.id))
        assertEquals(ImportedClassTableChange.SKIPPED, store.move("gone", Placement.OTHER, before = null))
        store.move(wen.id, Placement.ROTATION, before = hua.id)

        assertEquals(
            listOf(
                "Wen" to Placement.ROTATION, "Hua" to Placement.ROTATION,
                "Ming" to Placement.LONG_PRESS_MENU, "Lin" to Placement.LONG_PRESS_MENU,
            ),
            store.placements(),
        )
        assertEquals(store.tables.value.people, store().load().people)
    }

    @Test
    fun `a file from before placements, or with one this build does not know, reads as the rotation`() = runTest {
        val store = store()
        store.import("B11300001", "Ming")
        store.import("B11300002", "Hua")
        val text = file.readText()
        val people = store.tables.value.people
        file.writeText(
            text.replaceFirst(",\"placement\":\"rotation\"", "")
                .replaceFirst("\"placement\":\"rotation\"", "\"placement\":\"pinned\""),
        )
        assertEquals(1, Regex("\"placement\"").findAll(file.readText()).count())

        assertEquals(people.map { it.copy(placement = Placement.ROTATION) }, store().load().people)
    }

    @Test
    fun `next goes through the user's own class table and the rotation only`() = runTest {
        val store = store()
        val ming = store.import("B11300001", "Ming")
        val hua = store.import("B11300002", "Hua")
        val wen = store.import("B11300003", "Wen")
        store.move(hua.id, Placement.LONG_PRESS_MENU, before = null)
        store.move(wen.id, Placement.OTHER, before = null)

        val shown = (1..3).map {
            store.showNext()
            store.tables.value.shownPersonId
        }
        assertEquals(listOf(ming.id, null, ming.id), shown)

        // From someone outside the rotation, the next is the rotation's first.
        store.show(wen.id)
        store.showNext()
        assertEquals(ming.id, store.tables.value.shownPersonId)
        store.move(ming.id, Placement.OTHER, before = null)
        store.show(hua.id)
        store.showNext()
        assertNull(store.tables.value.shownPersonId)
    }

    @Test
    fun `an unknown placement on a person read back is the rotation, as is a missing one`() {
        assertEquals(Placement.ROTATION, Placement.stored(null))
        assertEquals(Placement.ROTATION, Placement.stored("pinned"))
        assertEquals(Placement.LONG_PRESS_MENU, Placement.stored("longPressMenu"))
        assertEquals(Placement.OTHER, Placement.stored(Placement.OTHER.stored))
    }
}
