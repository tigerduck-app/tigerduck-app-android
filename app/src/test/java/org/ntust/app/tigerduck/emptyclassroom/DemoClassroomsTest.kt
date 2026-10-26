package org.ntust.app.tigerduck.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The demo account's rooms from `assets/demo.json`, which the store reviewer sees in the finder. */
class DemoClassroomsTest {
    private val json = File("src/main/assets/demo.json").readText()

    @Test
    fun `the demo week gives rooms on a weekday and none on a weekend`() {
        val demo = requireNotNull(DemoClassrooms.parse(json, "en"))
        val wednesday = demo.usage(LocalDate.of(2026, 10, 14))

        assertTrue(wednesday.rooms.size >= 20)
        assertTrue(wednesday.rooms.all { it.slots.size == 14 })
        assertEquals("研揚大樓", wednesday.buildingNames["TR"])
        assertTrue(demo.usage(LocalDate.of(2026, 10, 17)).rooms.isEmpty())
    }

    @Test
    fun `the demo student's own classes sit in their rooms, in the language asked for`() {
        val en = requireNotNull(DemoClassrooms.parse(json, "en")).usage(LocalDate.of(2026, 10, 14))
        val zh = requireNotNull(DemoClassrooms.parse(json, "zh")).usage(LocalDate.of(2026, 10, 14))

        // Data Structures meets in TR-215 on Wednesdays at periods 6 and 7.
        assertEquals(RoomSlotUse.Course("Data Structures", "Wang Hsiao-Ming"), en.rooms.first { it.room == "TR-215" }.slots[5])
        assertEquals(RoomSlotUse.Course("資料結構", "王小明"), zh.rooms.first { it.room == "TR-215" }.slots[6])
        assertEquals(RoomSlotUse.Booked, en.rooms.first { it.room == "TR-209" }.slots[4])
        assertEquals(RoomSlotUse.Free, en.rooms.first { it.room == "IB-508" }.slots[0])
    }

    @Test
    fun `a file without the section has no demo rooms`() {
        assertNull(DemoClassrooms.parse("{}", "en"))
    }
}
