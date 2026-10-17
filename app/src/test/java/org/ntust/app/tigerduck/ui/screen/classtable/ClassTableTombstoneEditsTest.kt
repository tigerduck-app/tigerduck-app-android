package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.CourseTombstoneKeys

/**
 * The class table's tombstone edits between the tap and their write: a fetch folding its
 * answers in that gap has to see a course deleted a moment ago as deleted.
 */
class ClassTableTombstoneEditsTest {

    private val edits = ClassTableTombstoneEdits()
    private val gone = CourseTombstoneKeys.key("1151", "GONE")

    @Test
    fun `a delete is in the tombstones before its write lands, and adds nothing once it has`() {
        val id = edits.edit { CourseTombstoneKeys.hide("GONE", "1151", it) }
        assertTrue(CourseTombstoneKeys.isHidden("GONE", "1151", edits.over(emptySet())))
        edits.saved(id)
        // The file carries it now; the edit must not be applied a second time over a later file.
        assertEquals(emptySet<String>(), edits.over(emptySet()))
    }

    @Test
    fun `an add of a hidden course lifts its tombstone before the write lands`() {
        edits.edit { CourseTombstoneKeys.unhide("GONE", "1151", it) }
        assertFalse(CourseTombstoneKeys.isHidden("GONE", "1151", edits.over(setOf(gone))))
    }

    @Test
    fun `edits apply in the order they were made, and a sign-out drops them`() {
        edits.edit { CourseTombstoneKeys.hide("GONE", "1151", it) }
        edits.edit { CourseTombstoneKeys.unhide("GONE", "1151", it) }
        assertFalse(CourseTombstoneKeys.isHidden("GONE", "1151", edits.over(emptySet())))
        edits.edit { CourseTombstoneKeys.hide("GONE", "1151", it) }
        assertTrue(CourseTombstoneKeys.isHidden("GONE", "1151", edits.over(emptySet())))
        edits.clear()
        assertEquals(setOf("other"), edits.over(setOf("other")))
    }
}
