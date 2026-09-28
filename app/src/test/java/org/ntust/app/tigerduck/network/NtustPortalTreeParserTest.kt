package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.PortalCategory

class NtustPortalTreeParserTest {

    private fun serviceHtml(vararg links: Pair<String, String>): String {
        val anchors = links.joinToString("") { (name, href) -> """<a href="$href">$name</a>""" }
        return """<div id="service"><div id="service-2">$anchors</div></div>"""
    }

    @Test
    fun `parse scrapes a link into its category`() {
        val html = serviceHtml("個人資訊" to "https://i.ntust.edu.tw/student/profile")
        val links = NtustPortalTreeParser.parse(html, "https://i.ntust.edu.tw/student")

        assertEquals(1, links.size)
        assertEquals(PortalCategory.PERSON_INFO, links[0].category)
        assertEquals("https://i.ntust.edu.tw/student/profile", links[0].url)
    }

    @Test
    fun `parse rewrites the stale ae ntust edu tw host to ae cge ntust edu tw`() {
        val html = serviceHtml("學術研究倫理課程報名" to "https://ae.ntust.edu.tw/index.php?lang=zh")
        val links = NtustPortalTreeParser.parse(html, "https://i.ntust.edu.tw/student")

        assertEquals(1, links.size)
        assertEquals("https://ae.cge.ntust.edu.tw/index.php?lang=zh", links[0].url)
    }

    @Test
    fun `parse leaves every other host untouched`() {
        val html = serviceHtml("課程資訊" to "https://courseselection.ntust.edu.tw/")
        val links = NtustPortalTreeParser.parse(html, "https://i.ntust.edu.tw/student")

        assertTrue(links.single().url == "https://courseselection.ntust.edu.tw/")
    }
}
