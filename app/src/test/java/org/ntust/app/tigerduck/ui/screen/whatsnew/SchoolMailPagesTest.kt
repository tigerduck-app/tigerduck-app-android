package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AppFeature.ANNOUNCEMENTS
import org.ntust.app.tigerduck.data.model.AppFeature.CALENDAR
import org.ntust.app.tigerduck.data.model.AppFeature.CLASS_TABLE
import org.ntust.app.tigerduck.data.model.AppFeature.HOME
import org.ntust.app.tigerduck.data.model.AppFeature.LIBRARY
import org.ntust.app.tigerduck.data.model.AppFeature.SCHOOL_MAIL
import org.ntust.app.tigerduck.data.model.AppFeature.SCORE

class SchoolMailPagesTest {

    @Test
    fun `only calendar changes`() {
        assertEquals(
            listOf(HOME, CLASS_TABLE, ANNOUNCEMENTS, SCHOOL_MAIL),
            recommendedTabsWithMail(listOf(HOME, CLASS_TABLE, ANNOUNCEMENTS, CALENDAR)),
        )
    }

    @Test
    fun `mail takes calendar's slot`() {
        // The pre-2.3.0 default becomes the 2.3.0 one.
        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL), recommendedTabsWithMail(AppFeature.previousDefaultTabs))
        assertEquals(
            listOf(SCHOOL_MAIL, HOME, SCORE, ANNOUNCEMENTS),
            recommendedTabsWithMail(listOf(CALENDAR, HOME, SCORE, ANNOUNCEMENTS)),
        )
    }

    @Test
    fun `without calendar, mail is appended when there is room`() {
        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL), recommendedTabsWithMail(listOf(HOME, CLASS_TABLE)))
        assertEquals(
            listOf(HOME, CLASS_TABLE, SCORE, SCHOOL_MAIL),
            recommendedTabsWithMail(listOf(HOME, CLASS_TABLE, SCORE)),
        )
    }

    @Test
    fun `a full bar without calendar is not asked`() {
        assertNull(recommendedTabsWithMail(listOf(HOME, CLASS_TABLE, SCORE, ANNOUNCEMENTS)))
    }

    @Test
    fun `a bar that already has mail is not asked`() {
        assertNull(recommendedTabsWithMail(listOf(HOME, SCHOOL_MAIL, CALENDAR)))
        assertNull(recommendedTabsWithMail(listOf(HOME, SCHOOL_MAIL)))
    }

    @Test
    fun `a library tab takes a slot like any other`() {
        assertNull(recommendedTabsWithMail(listOf(HOME, CLASS_TABLE, SCORE, LIBRARY)))
    }

    @Test
    fun `a bar without mail gets the mail pages`() {
        assertTrue(offersMailPages(listOf(HOME, CLASS_TABLE, CALENDAR)))
        assertTrue(offersMailPages(listOf(HOME, ANNOUNCEMENTS, SCORE, LIBRARY)))
    }

    @Test
    fun `a bar that already has mail skips the mail pages`() {
        assertFalse(offersMailPages(AppFeature.defaultTabs))
        assertFalse(offersMailPages(listOf(HOME, SCORE, SCHOOL_MAIL, CALENDAR)))
    }

    @Test
    fun `the preview tells a screen reader which tab leaves`() {
        val before = listOf("Home", "Class table", "Calendar", "More")
        val after = listOf("Home", "Class table", "Mail", "More")
        assertEquals(
            "Before: Home, Class table, Calendar, More. After: Home, Class table, Mail, More.",
            spokenBarChange(before, after, WhatsNewLanguage.En),
        )
        assertEquals(
            "調整前：首頁、課表、行事曆、更多。調整後：首頁、課表、信箱、更多。",
            spokenBarChange(
                listOf("首頁", "課表", "行事曆", "更多"),
                listOf("首頁", "課表", "信箱", "更多"),
                WhatsNewLanguage.ZhHant,
            ),
        )
        val unchanged = listOf("Home", "Mail", "More")
        assertEquals("Your bottom bar: Home, Mail, More", spokenBarChange(unchanged, unchanged, WhatsNewLanguage.En))
    }
}
