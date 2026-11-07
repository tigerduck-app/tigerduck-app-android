package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.model.PortalCategory
import org.ntust.app.tigerduck.network.model.PortalLink
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Information System's search across the portal's Chinese and English pages, as Add course's
 * search is: a query matches either page's name, a Simplified one as Traditional, and a result shows
 * the other page's name in parentheses when the query's language is not the UI's. Robolectric for
 * ICU's Simplified-Traditional transliterator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PortalSearchTest {

    private fun link(name: String, url: String, category: PortalCategory = PortalCategory.CURRICULUM) =
        PortalLink(category, name, url)

    private val zh = listOf(
        link("選課系統", "https://courseselection.ntust.edu.tw/"),
        link("宿舍系統", "https://dorm.ntust.edu.tw/student", PortalCategory.CAMPUS_LIFE),
        link("校園授權軟體下載", "https://software.ntust.edu.tw/websoftware/login.aspx?lang=zh-tw", PortalCategory.RESOURCES),
    )
    private val en = listOf(
        link("Course Selection", "https://courseselection.ntust.edu.tw/"),
        link("Dormitory", "https://dorm.ntust.edu.tw/EN/student", PortalCategory.CAMPUS_LIFE),
        link("Licensed Software", "https://software.ntust.edu.tw/websoftware/login.aspx?lang=en", PortalCategory.RESOURCES),
    )

    private fun names(links: List<PortalLink>) = links.map { it.name }

    @Test
    fun `English in the Chinese UI finds the Chinese link with its English name`() {
        val pairs = PortalSearch.pairs(shown = zh, other = en)

        assertEquals(listOf("宿舍系統 (Dormitory)"), names(PortalSearch.search(pairs, "dorm", uiEnglish = false)))
    }

    @Test
    fun `Simplified in the Chinese UI finds the Traditional link alone`() {
        val pairs = PortalSearch.pairs(shown = zh, other = en)

        assertEquals(listOf("選課系統"), names(PortalSearch.search(pairs, "选课", uiEnglish = false)))
    }

    @Test
    fun `Simplified in the English UI finds the English link with its Traditional name`() {
        val pairs = PortalSearch.pairs(shown = en, other = zh)

        assertEquals(listOf("Dormitory (宿舍系統)"), names(PortalSearch.search(pairs, "宿舍系统", uiEnglish = true)))
    }

    @Test
    fun `Traditional in the English UI finds the English link with its Traditional name`() {
        val pairs = PortalSearch.pairs(shown = en, other = zh)

        assertEquals(listOf("Licensed Software (校園授權軟體下載)"), names(PortalSearch.search(pairs, "授權", uiEnglish = true)))
    }

    @Test
    fun `a query in the UI's language shows no other name`() {
        assertEquals(listOf("選課系統"), names(PortalSearch.search(PortalSearch.pairs(zh, en), "選課", uiEnglish = false)))
        assertEquals(listOf("Course Selection"), names(PortalSearch.search(PortalSearch.pairs(en, zh), "course", uiEnglish = true)))
    }

    @Test
    fun `a result opens the UI page's address`() {
        val hit = PortalSearch.search(PortalSearch.pairs(shown = en, other = zh), "宿舍", uiEnglish = true).single()

        assertEquals("https://dorm.ntust.edu.tw/EN/student", hit.url)
    }

    @Test
    fun `links whose addresses differ pair by their place in the category`() {
        val shown = listOf(link("學籍資料", "https://stuinfo.ntust.edu.tw/zh"), link("成績查詢", "https://stuinfo.ntust.edu.tw/score/zh"))
        val other = listOf(link("Student Records", "https://stuinfo.ntust.edu.tw/en"), link("Grades", "https://stuinfo.ntust.edu.tw/score/en"))

        assertEquals(listOf("成績查詢 (Grades)"), names(PortalSearch.search(PortalSearch.pairs(shown, other), "grade", uiEnglish = false)))
    }

    @Test
    fun `a link with no counterpart still shows, alone`() {
        val shown = zh + link("畢業生離校手續單", "https://leave.ntust.edu.tw/", PortalCategory.PERSON_INFO)

        val pairs = PortalSearch.pairs(shown, en)

        assertEquals(listOf("畢業生離校手續單"), names(PortalSearch.search(pairs, "離校", uiEnglish = false)))
        // Unpaired on the English side: an English query finds nothing in the Chinese UI for it.
        assertEquals(emptyList<String>(), names(PortalSearch.search(pairs, "leave", uiEnglish = false)))
    }

    @Test
    fun `an other-language link with no counterpart shows under its own name`() {
        val other = en + link("Exchange Students", "https://oia.ntust.edu.tw/exchange", PortalCategory.ACTIVITIES)

        assertEquals(listOf("Exchange Students"), names(PortalSearch.search(PortalSearch.pairs(zh, other), "exchange", uiEnglish = false)))
    }

    @Test
    fun `an empty query lists the UI page as it is`() {
        val other = en + link("Exchange Students", "https://oia.ntust.edu.tw/exchange", PortalCategory.ACTIVITIES)

        assertEquals(names(zh), names(PortalSearch.search(PortalSearch.pairs(zh, other), "  ", uiEnglish = false)))
    }

    @Test
    fun `with no other page the search matches the UI page alone`() {
        assertEquals(listOf("宿舍系統"), names(PortalSearch.search(PortalSearch.pairs(zh, emptyList()), "宿舍", uiEnglish = false)))
        assertEquals(emptyList<String>(), names(PortalSearch.search(PortalSearch.pairs(zh, emptyList()), "dorm", uiEnglish = false)))
    }
}
