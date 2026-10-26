package org.ntust.app.tigerduck.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageParser.Postback
import java.time.LocalDate

/**
 * [ClassroomUsageParser] against saved pages in the shape of cour01's room-use page
 * (`src/test/resources/cour01/`): the ASP.NET form, the date calendar, the grid with a small
 * table in every cell, the stray `</br>` the site writes, the booking colour on the wrong cells of
 * a second page, and the pager. Courses, teachers and bookers are made up.
 */
class ClassroomUsageParserTest {

    private fun sample(name: String): String =
        requireNotNull(javaClass.getResource("/cour01/$name")) { "missing sample $name" }.readText()

    @Test
    fun `a cell is free, a class with its teachers, or booked, and a booking keeps no name`() {
        val grid = requireNotNull(ClassroomUsageParser.page(sample("campus_page1.html")).grid)
        val room = grid.rooms.first()

        assertEquals("TR-214", room.room)
        assertEquals(
            listOf(
                RoomSlotUse.Course("程式設計", "王大明"),
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Course("工程數學", "林志明 黃雅婷"),
                RoomSlotUse.Free,
            ),
            room.slots.take(6),
        )
        assertEquals(14, room.slots.size)
        assertFalse(grid.toString().contains("陳小華"))
    }

    @Test
    fun `a booking whose status is wrapped or worded otherwise still keeps no name`() {
        val row = "<tr nowrap=\"nowrap\"><td nowrap=\"nowrap\">TR-214   </td>" +
            ClassroomPageFixtures.cell("林小美</br><font color=\"red\">已借用</font>") +
            ClassroomPageFixtures.cell("<span>林小美</span></br><span>審核中</span>") +
            ClassroomPageFixtures.cell("林小美</br> 申請中") +
            ClassroomPageFixtures.cell("林小美</br> 已核准") +
            ClassroomPageFixtures.cell("林小美</br> 保留") +
            ClassroomPageFixtures.cell("<font>微積分</font></br><span>王大明</span>") +
            ClassroomPageFixtures.cell("</br>").repeat(8) +
            "</tr>"
        val grid = requireNotNull(ClassroomUsageParser.page(ClassroomPageFixtures.page(rows = listOf(row))).grid)

        assertEquals(
            listOf(
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Booked,
                RoomSlotUse.Course("微積分", "王大明"),
                RoomSlotUse.Free,
            ),
            grid.rooms.single().slots.take(7),
        )
        assertFalse(grid.toString().contains("林小美"))
    }

    @Test
    fun `a grid whose rows changed shape is unreadable, not a day without rooms`() {
        val wider = "<tr nowrap=\"nowrap\"><td nowrap=\"nowrap\">TR-214   </td><td>60</td>" +
            ClassroomPageFixtures.cell("</br>").repeat(14) + "</tr>"
        assertNull(ClassroomUsageParser.page(ClassroomPageFixtures.page(rows = listOf(wider))).grid)

        val message = "<tr><td colspan=\"15\">查無資料</td></tr>"
        val empty = requireNotNull(ClassroomUsageParser.page(ClassroomPageFixtures.page(rows = listOf(message))).grid)
        assertTrue(empty.rooms.isEmpty())
    }

    @Test
    fun `the rows between the header and the pager are rooms, in the page's order`() {
        val grid = requireNotNull(ClassroomUsageParser.page(sample("campus_page1.html")).grid)

        assertEquals(listOf("TR-214", "TR-310-1", "E1-306"), grid.rooms.map { it.room })
        assertEquals(RoomSlotUse.Booked, grid.rooms[2].slots[4])
        assertEquals(3, grid.rooms[2].freeUntil(2))
        assertNull(grid.rooms[2].freeUntil(1))
        assertEquals(13, grid.rooms[1].freeUntil(0))
    }

    @Test
    fun `a booking is read from its words, not from the colour the site paints on the wrong cells`() {
        val grid = requireNotNull(ClassroomUsageParser.page(sample("campus_page2.html")).grid)
        val ib = grid.rooms.first { it.room == "IB-508" }

        assertEquals(RoomSlotUse.Free, ib.slots[0])
        assertEquals(RoomSlotUse.Booked, ib.slots[2])
        assertEquals(RoomSlotUse.Course("程式設計", "王大明"), ib.slots[6])
        assertTrue(grid.rooms.first { it.room == "T4-B1" }.slots.all { it == RoomSlotUse.Free })
    }

    @Test
    fun `the pager's targets are read from the page, and its unlinked number is the page shown`() {
        val first = requireNotNull(ClassroomUsageParser.page(sample("campus_page1.html")).grid)
        assertEquals(1, first.page)
        assertEquals(mapOf(2 to Postback("showinfo_grd\$_ctl54\$_ctl1", "")), first.pageLinks)

        val second = requireNotNull(ClassroomUsageParser.page(sample("campus_page2.html")).grid)
        assertEquals(2, second.page)
        assertEquals(mapOf(1 to Postback("showinfo_grd\$_ctl42\$_ctl0", "")), second.pageLinks)
    }

    @Test
    fun `a page without the grid is a failure, not an empty answer`() {
        assertNull(ClassroomUsageParser.page(sample("sso_login.html")).grid)
        assertNull(ClassroomUsageParser.page(sample("runtime_error.html")).grid)
        assertNull(ClassroomUsageParser.page(sample("query_initial.html")).grid)
        assertFalse(ClassroomUsageParser.isQueryPage(sample("runtime_error.html")))
        assertFalse(ClassroomUsageParser.isQueryPage(sample("sso_login.html")))
        assertTrue(ClassroomUsageParser.isQueryPage(sample("query_initial.html")))
    }

    @Test
    fun `a grid with no rows, as on a weekend, has no rooms`() {
        val grid = requireNotNull(ClassroomUsageParser.page(sample("weekend_empty.html")).grid)
        assertTrue(grid.rooms.isEmpty())
        assertTrue(grid.pageLinks.isEmpty())
    }

    @Test
    fun `the form sends back its state and chosen options, but no button or unticked box`() {
        val page = ClassroomUsageParser.page(sample("campus_page1.html"))
        val values = page.fields.associate { it.name to it.value }

        assertEquals("campus", values["__VIEWSTATE"])
        assertEquals("ZXZlbnQ=", values["__EVENTVALIDATION"])
        assertEquals("", values["__EVENTTARGET"])
        assertEquals("HQ", values["DropdownCampusListID"])
        assertEquals("", values["DropDownBuildingListID"])
        assertEquals("on", values["show_mine"])
        assertNull(values["show_all"])
        assertNull(values["query_btn"])
        assertEquals(9, page.fields.size)
        assertEquals("HQ", page.campus)
        assertEquals("", ClassroomUsageParser.page(sample("query_initial.html")).campus)
    }

    @Test
    fun `buildings are named as the site names them`() {
        assertEquals(
            mapOf("TR" to "研揚大樓", "IB" to "國際大樓", "T4" to "第四教學大樓", "E1" to "工程一館"),
            ClassroomUsageParser.page(sample("campus_page1.html")).buildingNames,
        )
    }

    @Test
    fun `the calendar's day and month links are read apart`() {
        val page = ClassroomUsageParser.page(sample("campus_page1.html"))
        assertEquals((9783..9807).toSet(), page.calendarDays)
        assertEquals(listOf(9740, 9801), page.monthSteps.sorted())
    }

    @Test
    fun `a date's number counts days from 2000-01-01`() {
        assertEquals(0, ClassroomUsageParser.dayNumber(LocalDate.of(2000, 1, 1)))
        assertEquals(1, ClassroomUsageParser.dayNumber(LocalDate.of(2000, 1, 2)))
        assertEquals(9783, ClassroomUsageParser.dayNumber(LocalDate.of(2026, 10, 14)))
        assertEquals(9801, ClassroomUsageParser.dayNumber(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `a linked day is posted at once, and a later or earlier one first steps the month toward it`() {
        fun step(day: Int, days: Set<Int> = (9783..9807).toSet(), months: List<Int> = listOf(9740, 9801)) =
            ClassroomUsageParser.calendarStep(day, days, months)?.argument

        assertEquals("9790", step(9790))
        assertEquals("9807", step(9807))
        assertEquals("V9801", step(9812))
        assertEquals("9812", step(9812, (9794..9835).toSet(), listOf(9770, 9831)))
        assertEquals("V9770", step(9789, (9794..9835).toSet(), listOf(9770, 9831)))
        assertEquals("V9770", step(9800, emptySet(), listOf(9770, 9831)))
        // A day the calendar leaves unlinked among the ones it links cannot be posted.
        assertNull(step(9784, setOf(9783, 9785)))
        assertEquals(
            Postback("date_cal", "9790"),
            ClassroomUsageParser.calendarStep(9790, (9783..9807).toSet(), listOf(9740, 9801)),
        )
    }

    @Test
    fun `a grid built by the fixture builder reads back the marks it was given`() {
        val html = ClassroomPageFixtures.page(rows = listOf(ClassroomPageFixtures.row("TR-209", "cc..b........c")))
        val room = requireNotNull(ClassroomUsageParser.page(html).grid).rooms.single()
        assertEquals(RoomSlotUse.Booked, room.slots[4])
        assertNotNull(room.slots[13] as? RoomSlotUse.Course)
    }
}
