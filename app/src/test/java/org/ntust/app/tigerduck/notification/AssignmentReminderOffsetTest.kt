package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the minute-based lookup this enum needs to act as the local half
 * of the `notification` settings document's `assignments` section
 * (`push/NotificationSettingsSync.kt`'s `resolveOffsets`) — the document
 * carries arbitrary integers in whole minutes, this enum carries a fixed
 * set of named cases, and the two have to convert losslessly for every
 * case this build actually has.
 */
class AssignmentReminderOffsetTest {

    /**
     * Every case must be an exact whole number of minutes and round-trip
     * through [AssignmentReminderOffset.reminderOffsetMinutes] /
     * [AssignmentReminderOffset.fromMinutes] back to itself — the guarantee
     * that a value *this build's own enum* can express is never the one
     * that goes missing across a push-then-pull round trip.
     */
    @Test
    fun `every case round trips through its minute value`() {
        for (offset in AssignmentReminderOffset.entries) {
            val minutes = offset.reminderOffsetMinutes
            assertEquals(
                "${offset.name}'s milliseconds must be an exact whole number of minutes",
                0L,
                offset.milliseconds % 60_000L,
            )
            assertEquals(
                "${offset.name} -> $minutes minutes -> back to ${offset.name}",
                offset,
                AssignmentReminderOffset.fromMinutes(minutes),
            )
        }
    }

    @Test
    fun `known minute values resolve to the expected case`() {
        assertEquals(AssignmentReminderOffset.HR48, AssignmentReminderOffset.fromMinutes(2_880))
        assertEquals(AssignmentReminderOffset.HR1, AssignmentReminderOffset.fromMinutes(60))
        assertEquals(AssignmentReminderOffset.MIN30, AssignmentReminderOffset.fromMinutes(30))
        assertEquals(AssignmentReminderOffset.MIN5, AssignmentReminderOffset.fromMinutes(5))
    }

    /**
     * A value the fixed enum has no case for -- the exact shape a document
     * carrying arbitrary integers can produce -- must resolve to `null`,
     * not throw and not coincidentally match the wrong case.
     */
    @Test
    fun `an unrepresented minute value resolves to null`() {
        assertNull(AssignmentReminderOffset.fromMinutes(999))
        assertNull(AssignmentReminderOffset.fromMinutes(0))
        assertNull(AssignmentReminderOffset.fromMinutes(-30))
    }
}
