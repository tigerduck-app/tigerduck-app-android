package org.ntust.app.tigerduck.ui.screen.announcements

import org.ntust.app.tigerduck.network.model.BulletinDetail

/**
 * What the bulletin detail renders as its body. Ports the iPhone's `BulletinBodyRenderer.swift`
 * so both apps pick the same field and make the same fix-ups to the server's markdown.
 */
internal object BulletinBodyRenderer {

    /**
     * The first non-blank of `body_clean` (the LLM's cleaned markdown, every fact kept), `body_md`
     * (the raw scrape), the detail's summary, and [fallbackSummary] (the list row's); trimmed, or
     * empty when there is none.
     */
    fun bodyMarkdown(detail: BulletinDetail, fallbackSummary: String? = null): String =
        sequenceOf(detail.bodyClean, detail.bodyMd, detail.summary, fallbackSummary)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            .orEmpty()

    /**
     * Rewrites the shapes the CommonMark parser leaves as raw text. The LLM writes `*   ` and `+ `
     * list markers, and the school puts `**` flush against full-width punctuation, where the
     * closing run fails the flanking rules and both `**` show. A space between the run and the
     * punctuation is what lets the bold through.
     *
     * The list-marker pattern stays within a line (`[ \t]`, where the iPhone's has `\s`), so a
     * bare `*` cannot pull the line below it up into a list item.
     */
    fun normalize(source: String): String {
        // The open fence's character and length: only a run of the same character, at least as
        // long, closes it.
        var fence: String? = null
        return source.split('\n').joinToString("\n") { line ->
            val run = FENCE.find(line)?.groupValues?.get(1)
            val open = fence
            when {
                open != null -> line.also {
                    if (run != null && run[0] == open[0] && run.length >= open.length && line.trim() == run) fence = null
                }
                run != null -> line.also { fence = run }
                // A `* * *` line is a thematic break, not a list.
                THEMATIC_BREAK.matches(line) -> line
                else -> outsideLiterals(line.replace(LIST_MARKER, "$1- "))
            }
        }
    }

    // The bold fix-ups, on the parts of [line] outside inline code, link destinations and URLs,
    // where a space added inside would change the code or break the link.
    private fun outsideLiterals(line: String): String {
        val out = StringBuilder()
        var at = 0
        for (literal in LITERAL.findAll(line)) {
            out.append(bold(line.substring(at, literal.range.first))).append(literal.value)
            at = literal.range.last + 1
        }
        return out.append(bold(line.substring(at))).toString()
    }

    private fun bold(text: String): String = text
        .replace(BOLD_BEFORE_PUNCTUATION, "**$1** $2")
        .replace(BOLD_ENDING_IN_PUNCTUATION, "**$1** $2")
        .replace(BOLD_AFTER_OPENING_BRACKET, "$1 **$2**")

    private val LIST_MARKER = Regex("""^([ \t]*)[*+][ \t]+""")
    private val FENCE = Regex("""^[ \t]{0,3}(`{3,}|~{3,})""")
    private val THEMATIC_BREAK = Regex("""[ \t]{0,3}([*_-])([ \t]*\1){2,}[ \t]*""")
    private val LITERAL = Regex(
        """(`+)[\s\S]*?(?<!`)\1(?!`)|\]\((?:[^()\\]|\\.|\([^()]*\))*\)|<https?://[^>]*>|https?://[^\s、。，．：；！？「」『』（）]+""",
    )
    private val BOLD_BEFORE_PUNCTUATION = Regex("""\*\*([^*\n]+)\*\*([、。，．：；！？」』）])""")
    private val BOLD_ENDING_IN_PUNCTUATION = Regex("""\*\*([^*\n]*?[、。，．：；！？])\*\*(\S)""")
    private val BOLD_AFTER_OPENING_BRACKET = Regex("""([「『（])\*\*([^*\n]+)\*\*""")
}
