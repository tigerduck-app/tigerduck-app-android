package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dual bare/scoped read is what lets the store change shape without a
 * DataMigration step, so every case that mixes the two forms is load-bearing:
 * lose it and an upgrading user's deletions stop applying.
 */
class CourseTombstoneKeysTest {

    @Test
    fun `a scoped entry hides its own term and no other`() {
        val store = setOf("1142:CS101")
        assertTrue(CourseTombstoneKeys.isHidden("CS101", "1142", store))
        assertFalse("a retake in another term is a different enrolment",
            CourseTombstoneKeys.isHidden("CS101", "1151", store))
    }

    @Test
    fun `a legacy bare entry still hides in every term`() {
        val store = setOf("CS101")
        assertTrue(CourseTombstoneKeys.isHidden("CS101", "1142", store))
        assertTrue(CourseTombstoneKeys.isHidden("CS101", "1151", store))
    }

    @Test
    fun `hiddenIn resolves both forms to bare numbers for one term`() {
        val store = setOf("LEGACY", "1142:CS101", "1151:EE201")
        assertEquals(setOf("LEGACY", "CS101"), CourseTombstoneKeys.hiddenIn("1142", store))
        assertEquals(setOf("LEGACY", "EE201"), CourseTombstoneKeys.hiddenIn("1151", store))
        assertEquals(setOf("LEGACY"), CourseTombstoneKeys.hiddenIn("1132", store))
    }

    @Test
    fun `unhide drops the legacy bare entry too, or it would keep hiding`() {
        val store = setOf("CS101", "1142:CS101", "1151:CS101")
        val after = CourseTombstoneKeys.unhide("CS101", "1142", store)
        assertFalse(CourseTombstoneKeys.isHidden("CS101", "1142", after))
        assertTrue("other terms keep their own scoped entry",
            CourseTombstoneKeys.isHidden("CS101", "1151", after))
    }

    @Test
    fun `resetting a term lifts its keys and every bare one`() {
        val store = setOf("LEGACY", "1142:CS101", "1151:EE201")
        assertEquals(
            setOf("LEGACY", "1142:CS101"),
            CourseTombstoneKeys.entriesResetting("1142", store),
        )
    }

    @Test
    fun `migration pins a bare entry to the terms whose roster carries it`() {
        val migrated = CourseTombstoneKeys.migrateLegacyEntries(
            entries = setOf("CS101"),
            rosters = mapOf(
                "1151" to setOf("CS101", "EE201"),
                "1142" to setOf("CS101"),
                "1132" to setOf("MA100"),
            ),
        )
        assertEquals(setOf("1151:CS101", "1142:CS101"), migrated)
    }

    @Test
    fun `an entry no roster knows stays bare rather than being guessed away`() {
        // A term the student has not opened yet has no cached roster. Dropping
        // the entry would resurrect the course; scoping it to a term we cannot
        // name would hide the wrong one.
        val migrated = CourseTombstoneKeys.migrateLegacyEntries(
            entries = setOf("CS101"),
            rosters = mapOf("1151" to setOf("EE201")),
        )
        assertEquals(setOf("CS101"), migrated)
        assertTrue(CourseTombstoneKeys.isHidden("CS101", "1142", migrated))
    }

    @Test
    fun `migration leaves already-scoped entries alone and is idempotent`() {
        val rosters = mapOf("1142" to setOf("CS101"))
        val once = CourseTombstoneKeys.migrateLegacyEntries(setOf("CS101"), rosters)
        val twice = CourseTombstoneKeys.migrateLegacyEntries(once, rosters)
        assertEquals(setOf("1142:CS101"), once)
        assertEquals(once, twice)
    }
}
