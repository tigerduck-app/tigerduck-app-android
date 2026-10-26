package org.ntust.app.tigerduck.network.moodle

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodleHtmlTextTest {

    @Test
    fun `plain text runs blocks and breaks together with single spaces`() {
        assertEquals(
            "Title First line second line Item",
            MoodleHtmlText.plainText("<h3>Title</h3><p>First line<br>second&nbsp;line</p><ul><li>Item</li></ul><script>x()</script>"),
        )
    }

    @Test
    fun `markdown keeps structure and escapes the text`() {
        val md = MoodleHtmlText.markdown(
            "<h2>Exam</h2><p>Bring a <strong>pen</strong> and *paper*.</p><ol><li>Part A</li><li>Part B</li></ol>" +
                "<blockquote><p>Quote</p></blockquote>",
        )
        assertEquals(
            "## Exam\n\nBring a **pen** and \\*paper\\*.\n\n1. Part A\n2. Part B\n\n> Quote",
            md,
        )
    }

    @Test
    fun `links resolve against the site and only safe schemes stay links`() {
        assertEquals(
            "[Notes](https://moodle2.ntust.edu.tw/mod/page/view.php?id=1) and run",
            MoodleHtmlText.markdown("""<p><a href="/mod/page/view.php?id=1">Notes</a> and <a href="javascript:alert(1)">run</a></p>"""),
        )
        assertEquals(
            "[a@b.tw](mailto:a@b.tw)",
            MoodleHtmlText.markdown("""<a href="mailto:a@b.tw">a@b.tw</a>"""),
        )
    }

    @Test
    fun `an image becomes a link to it`() {
        assertEquals(
            "[chart](https://moodle2.ntust.edu.tw/pluginfile.php/1/chart.png)",
            MoodleHtmlText.markdown("""<img src="https://moodle2.ntust.edu.tw/pluginfile.php/1/chart.png" alt="chart">"""),
        )
    }

    @Test
    fun `a destination with brackets or spaces stays one link`() {
        assertEquals(
            "[Week 1](https://moodle2.ntust.edu.tw/pluginfile.php/1/Week%201%28a%29.pdf)",
            MoodleHtmlText.markdown("""<a href="https://moodle2.ntust.edu.tw/pluginfile.php/1/Week 1(a).pdf">Week 1</a>"""),
        )
    }

    @Test
    fun `a line that would read as a list is escaped`() {
        assertEquals("1\\. not a list", MoodleHtmlText.markdown("<p>1. not a list</p>"))
        assertEquals("\\- not a bullet", MoodleHtmlText.markdown("<p>- not a bullet</p>"))
    }

    @Test
    fun `a code block holding a fence of its own gets a longer fence`() {
        assertEquals("````\nshell\n```\ndone\n````", MoodleHtmlText.markdown("<pre>shell\n```\ndone</pre>"))
        assertEquals("```\nplain\n```", MoodleHtmlText.markdown("<pre>plain</pre>"))
    }

    @Test
    fun `online text points its own files at their links, drops an image with no file and unwraps a link with none`() {
        val file = { name: String ->
            org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
                name = name, size = 1, mimeType = null, modified = null, path = "/",
                url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/assignsubmission_onlinetext/submissions_onlinetext/5/$name",
            )
        }
        val resolved = MoodleHtmlText.resolvingPluginFiles(
            "<p>Chart: <img src=\"@@PLUGINFILE@@/chart.png\" alt=\"chart\"> <img src=\"@@PLUGINFILE@@/gone.png\" alt=\"gone\"></p>" +
                "<p><a href=\"@@PLUGINFILE@@/notes%20v2.pdf?forcedownload=1\">Notes</a> <a href=\"@@PLUGINFILE@@/old.pdf\">Old notes</a></p>",
            listOf(file("chart.png"), file("notes v2.pdf")),
        )

        assertEquals(
            "<p>Chart: <img src=\"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/assignsubmission_onlinetext/submissions_onlinetext/5/chart.png\" alt=\"chart\"> </p>" +
                "<p><a href=\"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/assignsubmission_onlinetext/submissions_onlinetext/5/notes v2.pdf\">Notes</a> Old notes</p>",
            resolved,
        )
        assertEquals("text without the placeholder is left as it is", "<p>x</p>", MoodleHtmlText.resolvingPluginFiles("<p>x</p>", emptyList()))
    }

    @Test
    fun `a text that is one image and nothing else still points the image at its file`() {
        // iOS #728: its HTML library left the placeholder in place when nothing else in the text changed.
        val chart = org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
            name = "chart.png", size = 1, mimeType = "image/png", modified = null, path = "/",
            url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/assignsubmission_onlinetext/submissions_onlinetext/5/chart.png",
        )

        val resolved = MoodleHtmlText.resolvingPluginFiles("<img src=\"@@PLUGINFILE@@/chart.png\" alt=\"chart\">", listOf(chart))

        assertEquals("<img src=\"${chart.url}\" alt=\"chart\">", resolved)
        assertEquals("[chart](${chart.url})", MoodleHtmlText.markdown(resolved))
    }

    // The renderer draws each list and quote level inside the last, so past five they are plain blocks.

    @Test
    fun `lists nest five deep, and a list deeper than that is written as its items`() {
        val html = (1..6).joinToString("") { "<ul><li>$it" } + "</li></ul>".repeat(6)

        assertEquals(
            "- 1\n  - 2\n    - 3\n      - 4\n        - 5\n          6",
            MoodleHtmlText.markdown(html),
        )
    }

    @Test
    fun `quotes nest five deep, and a quote deeper than that is written as what it holds`() {
        val html = (1..6).joinToString("") { "<blockquote>$it" } + "</blockquote>".repeat(6)

        assertEquals(
            "> 1\n>\n> > 2\n> >\n> > > 3\n> > >\n> > > > 4\n> > > >\n> > > > > 5\n> > > > >\n> > > > > 6",
            MoodleHtmlText.markdown(html),
        )
    }

    @Test
    fun `lists and quotes count against one cap`() {
        val html = "<blockquote><ul><li>x".repeat(3) + "</li></ul></blockquote>".repeat(3)

        assertEquals("> - x\n>   > - x\n>   >   > x", MoodleHtmlText.markdown(html))
    }

    @Test
    fun `past the cap, a list keeps its items apart, and a list or text it holds outside them`() {
        val html = (1..5).joinToString("") { "<ul><li>$it" } + "<ul><li>a</li><ul><li>b</li></ul>c</ul>" +
            "</li></ul>".repeat(5)

        assertEquals(
            "- 1\n  - 2\n    - 3\n      - 4\n        - 5\n          a\n          b\n          c",
            MoodleHtmlText.markdown(html),
        )
    }

    @Test
    fun `a document nested thousands deep converts, keeps its text in order and nests no deeper than the cap`() {
        val depth = 5_000
        val html = (1..depth).joinToString("") { "<blockquote><ul><li>$it" } + "</li></ul></blockquote>".repeat(depth)

        val markdown = MoodleHtmlText.markdown(html)

        assertEquals((1..depth).toList(), Regex("\\d+").findAll(markdown).map { it.value.toInt() }.toList())
        val nesting = nestingOf(markdown)
        assertTrue("nested $nesting deep", nesting <= 5)
    }

    /** How deep the renderer's parser, which reads this Markdown on screen, finds lists and quotes nested. */
    private fun nestingOf(markdown: CharSequence): Int {
        val nests = setOf(MarkdownElementTypes.BLOCK_QUOTE, MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST)
        fun depth(node: ASTNode): Int = (if (node.type in nests) 1 else 0) + (node.children.maxOfOrNull { depth(it) } ?: 0)
        return depth(MarkdownParser(GFMFlavourDescriptor(), true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(markdown))
    }
}
