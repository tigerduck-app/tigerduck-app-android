package org.ntust.app.tigerduck.ui.screen.announcements

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.BulletinDetail

/**
 * Which field the bulletin detail renders, and the markdown fix-ups it makes first. Both follow
 * the iPhone's `BulletinBodyRenderer.swift`, so the two apps show the same body the same way.
 */
class BulletinBodyRendererTest {

    private fun detail(
        bodyClean: String? = null,
        bodyMd: String? = null,
        summary: String? = null,
    ) = BulletinDetail(
        id = 1,
        externalId = "e1",
        title = "Bulletin",
        titleClean = null,
        canonicalOrg = null,
        importance = null,
        summary = summary,
        sourceUrl = null,
        postedAt = null,
        bodyClean = bodyClean,
        bodyMd = bodyMd,
        rawPublisher = null,
    )

    @Test
    fun `fences close only on their own kind, and literals hold their exact shape`() {
        val mixed = "~~~\n+ value\n```\n+ still code\n~~~"
        assertEquals(mixed, BulletinBodyRenderer.normalize(mixed))
        val doubled = "見 ``a ` **重點**：b`` 一節"
        assertEquals(doubled, BulletinBodyRenderer.normalize(doubled))
        val nested = "[文件](path(part)**重點**：v1)"
        assertEquals(nested, BulletinBodyRenderer.normalize(nested))
        // A URL ends at full-width punctuation, and the prose after it is still fixed up.
        assertEquals(
            "https://example.com。說明**截止日期：** 10/31",
            BulletinBodyRenderer.normalize("https://example.com。說明**截止日期：**10/31"),
        )
    }

    @Test
    fun `code, link destinations, URLs and thematic breaks are left as written`() {
        val fenced = "```\n+ value\n**重點**：時間\n```"
        assertEquals(fenced, BulletinBodyRenderer.normalize(fenced))
        val inline = "見 `**重點**：時間` 一節"
        assertEquals(inline, BulletinBodyRenderer.normalize(inline))
        val link = "[文件](https://example.com/**文件**：v1)"
        assertEquals(link, BulletinBodyRenderer.normalize(link))
        val bare = "https://example.com/**文件**：v1"
        assertEquals(bare, BulletinBodyRenderer.normalize(bare))
        assertEquals("* * *", BulletinBodyRenderer.normalize("* * *"))
        // Outside them, the fix-ups still apply.
        assertEquals("見 `x` 與 **重點** ：時間", BulletinBodyRenderer.normalize("見 `x` 與 **重點**：時間"))
    }

    @Test
    fun `the cleaned body wins over the scraped one`() {
        assertEquals("清理後", BulletinBodyRenderer.bodyMarkdown(detail(bodyClean = "清理後", bodyMd = "原文")))
    }

    @Test
    fun `an empty or blank field falls through to the next, down to the list summary`() {
        assertEquals("原文", BulletinBodyRenderer.bodyMarkdown(detail(bodyClean = "  \n", bodyMd = "原文")))
        assertEquals("摘要", BulletinBodyRenderer.bodyMarkdown(detail(bodyClean = "", bodyMd = " ", summary = "摘要")))
        assertEquals(
            "列表摘要",
            BulletinBodyRenderer.bodyMarkdown(detail(summary = "\t"), fallbackSummary = "列表摘要"),
        )
        assertEquals("", BulletinBodyRenderer.bodyMarkdown(detail(), fallbackSummary = " "))
    }

    @Test
    fun `the chosen body is trimmed`() {
        assertEquals("內文", BulletinBodyRenderer.bodyMarkdown(detail(bodyClean = "\n  內文 \n")))
    }

    @Test
    fun `asterisk and plus list markers become dashes, indentation kept`() {
        assertEquals(
            "- 第一項\n- 第二項\n  - 子項",
            BulletinBodyRenderer.normalize("*   第一項\n+ 第二項\n  *   子項"),
        )
    }

    @Test
    fun `bold and italics at the start of a line are not list markers`() {
        val source = "**重點** 說明\n*斜體*"
        assertEquals(source, BulletinBodyRenderer.normalize(source))
    }

    @Test
    fun `a bare marker does not pull the next line up into it`() {
        val source = "*\n下一行"
        assertEquals(source, BulletinBodyRenderer.normalize(source))
    }

    @Test
    fun `bold flush against CJK punctuation is spaced off it`() {
        assertEquals("**重點** ：時間", BulletinBodyRenderer.normalize("**重點**：時間"))
        assertEquals("**截止日期：** 10/31", BulletinBodyRenderer.normalize("**截止日期：**10/31"))
        assertEquals("報名（ **免費** ）", BulletinBodyRenderer.normalize("報名（**免費**）"))
        assertEquals("「 **重要** 」", BulletinBodyRenderer.normalize("「**重要**」"))
    }

    /** The renderer's own parser: what `Markdown` gets handed must come out bold, not as `**`. */
    @Test
    fun `every shape the school writes renders bold once normalised`() {
        val shapes = listOf(
            "說明**截止日期：**10/31 前",
            "**重點**：時間",
            "報名（**免費**）",
            "請見「**附件一**」說明",
            "- 地點：**國際大樓**。",
        )
        for (shape in shapes) {
            assertEquals(shape, 1, strongNodes(BulletinBodyRenderer.normalize(shape)))
        }
    }

    // A CharSequence, for the parser's overload that is not deprecated.
    private fun strongNodes(markdown: CharSequence): Int {
        val tree = MarkdownParser(GFMFlavourDescriptor(), true, CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown)
        fun count(node: ASTNode): Int =
            (if (node.type == MarkdownElementTypes.STRONG) 1 else 0) + node.children.sumOf(::count)
        return count(tree)
    }
}
