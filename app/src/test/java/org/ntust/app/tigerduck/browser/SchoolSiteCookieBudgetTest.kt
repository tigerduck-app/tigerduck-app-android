package org.ntust.app.tigerduck.browser

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.browser.sites.SchoolSiteCookieBudget

class SchoolSiteCookieBudgetTest {
    private val page = "https://stuinfosys.ntust.edu.tw/StudentInformation"

    private fun cookie(name: String, domain: String, size: Int, path: String = "/"): Cookie =
        Cookie.Builder().name(name).value("v".repeat(size)).domain(domain).path(path).secure().build()

    @Test
    fun `Moodle keeps its session even when its cookies exceed the budget`() {
        val session = cookie("MoodleSession", "moodle2.ntust.edu.tw", 20)
        val large = cookie("Other", "moodle2.ntust.edu.tw", 8190)
        assertTrue(SchoolSiteCookieBudget.cookiesToDrop(listOf(session, large), "https://moodle2.ntust.edu.tw/my/").isEmpty())
    }

    @Test
    fun `over eight KB drops only the opening hosts cookies and preserves SSO`() {
        val sso = cookie("AuthServer", "ntust.edu.tw", 1730)
        val own = listOf(cookie("AppA", "stuinfosys.ntust.edu.tw", 3990), cookie("AppB", "stuinfosys.ntust.edu.tw", 3990))
        val other = cookie("Elsewhere", "cour01.ntust.edu.tw", 3990)
        assertEquals(own, SchoolSiteCookieBudget.cookiesToDrop(listOf(sso, other) + own, page))
        assertTrue(SchoolSiteCookieBudget.cookiesToDrop(listOf(sso, own[0], other), page).isEmpty())
    }

    @Test
    fun `the budget keeps exactly eight KB and drops cookies one byte over`() {
        val atLimit = cookie("A", "stuinfosys.ntust.edu.tw", 8189)
        assertTrue(SchoolSiteCookieBudget.cookiesToDrop(listOf(atLimit), page).isEmpty())
        val over = cookie("A", "stuinfosys.ntust.edu.tw", 8190)
        assertEquals(listOf(over), SchoolSiteCookieBudget.cookiesToDrop(listOf(over), page))
    }

    @Test
    fun `only cookies sent to the opening path count`() {
        val own = cookie("A", "querycourse.ntust.edu.tw", 8190, "/QueryCourse")
        assertTrue(SchoolSiteCookieBudget.cookiesToDrop(listOf(own), "https://querycourse.ntust.edu.tw/").isEmpty())
        assertEquals(listOf(own), SchoolSiteCookieBudget.cookiesToDrop(listOf(own), "https://querycourse.ntust.edu.tw/QueryCourse/"))
        assertTrue(SchoolSiteCookieBudget.cookiesToDrop(listOf(own), "https://querycourse.ntust.edu.tw/QueryCourses/").isEmpty())
    }
}
