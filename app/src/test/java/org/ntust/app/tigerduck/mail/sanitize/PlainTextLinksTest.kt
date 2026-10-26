package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextLinksTest {
    private fun links(text: String) = PlainTextLinks.find(text).map { text.substring(it.start, it.end) to it.href }

    @Test
    fun `an http or https URL is a link, ending where the URL characters do`() {
        assertEquals(
            listOf("https://www.lib.ntust.edu.tw/hours?x=1" to "https://www.lib.ntust.edu.tw/hours?x=1"),
            links("詳見https://www.lib.ntust.edu.tw/hours?x=1。謝謝"),
        )
        assertEquals(listOf("http://a.example" to "http://a.example"), links("Go to http://a.example now"))
    }

    @Test
    fun `punctuation that ends the sentence is not part of the link`() {
        assertEquals(listOf("https://a.example/p" to "https://a.example/p"), links("See https://a.example/p."))
        assertEquals(listOf("https://a.example" to "https://a.example"), links("(see https://a.example)"))
        assertEquals(listOf("https://a.example/x_(y)" to "https://a.example/x_(y)"), links("at https://a.example/x_(y)!"))
    }

    @Test
    fun `addresses become mailto links, written out or bare`() {
        assertEquals(
            listOf("library@mail.ntust.edu.tw" to "mailto:library@mail.ntust.edu.tw"),
            links("請寄信至library@mail.ntust.edu.tw。"),
        )
        assertEquals(
            listOf("mailto:a@b.example?subject=Hi" to "mailto:a@b.example?subject=Hi"),
            links("Write to mailto:a@b.example?subject=Hi."),
        )
    }

    @Test
    fun `an address inside a URL is part of that URL, not a second link`() {
        assertEquals(listOf("https://u@h.example/p" to "https://u@h.example/p"), links("https://u@h.example/p"))
    }

    @Test
    fun `other schemes and lookalikes are left as text`() {
        assertEquals(emptyList<Pair<String, String>>(), links("ftp://a.example javascript:alert(1) xhttps://a.example"))
        assertEquals(emptyList<Pair<String, String>>(), links("https:// nothing"))
    }

    @Test
    fun `a mailto link inside a URL is part of that URL, not a second link`() {
        assertEquals(
            listOf("https://a.example/r?next=mailto:evil@x.example" to "https://a.example/r?next=mailto:evil@x.example"),
            links("see https://a.example/r?next=mailto:evil@x.example now"),
        )
    }

    @Test
    fun `a URL longer than the cap is left as text`() {
        assertEquals(emptyList<Pair<String, String>>(), links("https://a.example/" + "x".repeat(PlainTextLinks.MAX_LINK_LENGTH)))
        val atCap = "https://a.example/" + "x".repeat(PlainTextLinks.MAX_LINK_LENGTH - "https://a.example/".length)
        assertEquals(listOf(atCap to atCap), links(atCap))
    }

    // A sender chooses the text, so a run of closing brackets or full stops after a URL must cost
    // time in proportion to its length, not its square.
    @Test(timeout = 2_000)
    fun `a long run of punctuation after a URL is trimmed in linear time`() {
        assertEquals(listOf("https://a" to "https://a"), links("https://a" + ")".repeat(200_000)))
        assertEquals(listOf("https://a" to "https://a"), links("https://a" + ".".repeat(200_000)))
        assertEquals(listOf("https://a" to "https://a"), links("https://a" + "]".repeat(200_000)))
    }

    @Test(timeout = 3_000)
    fun `many URLs and addresses are matched in linear time`() {
        val text = (1..50_000).joinToString(" ") { "https://h$it.example a$it@b.example" }
        val found = PlainTextLinks.find(text)
        assertEquals(100_000, found.size)
        assertEquals("https://h1.example", text.substring(found[0].start, found[0].end))
        assertEquals("mailto:a1@b.example", found[1].href)
    }

    @Test
    fun `links come back in the order they appear`() {
        assertEquals(
            listOf("a@b.example", "https://c.example"),
            links("a@b.example then https://c.example").map { it.first },
        )
    }
}
