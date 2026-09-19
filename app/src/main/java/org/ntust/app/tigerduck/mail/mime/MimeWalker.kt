package org.ntust.app.tigerduck.mail.mime

import jakarta.mail.Part
import jakarta.mail.internet.MimeMultipart

/**
 * Walks a MIME tree through each part's DataSource, never through
 * `getContent()`, so no DataContentHandler/mailcap lookup happens (the
 * fragile part of Jakarta Activation on Android). For an IMAP message the
 * DataSource is Angus' lazy multipart source: walking costs no downloads.
 */
internal object MimeWalker {
    data class Leaf(val path: String, val part: Part)

    /**
     * How deep the nesting may go before a multipart part is kept as one opaque leaf instead
     * of being descended into. The structure of a message is the sender's to choose and costs
     * almost nothing to nest: ~100 KB buys a thousand levels, and a thousand recursive frames
     * overflows the stack. `toSummary` happened to survive that -- its `runCatching` catches
     * `Throwable` -- but `fetchBody` and [find] run inside a `catch (e: Exception)`, so simply
     * *opening* such a mail took the process down, every time.
     *
     * Real mail nests three or four levels (mixed > related > alternative > text), so stopping
     * at twenty costs nothing anyone sends by accident. The subtree that is kept whole shows up
     * as an attachment rather than throwing, which is what an unreadable part should do anyway.
     */
    const val MAX_DEPTH = 20

    /** And a flat multipart with a hundred thousand children is the same attack without the depth. */
    const val MAX_LEAVES = 1_000

    fun leaves(root: Part): List<Leaf> = mutableListOf<Leaf>().also { walk(root, "", it, depth = 0) }

    fun find(root: Part, path: String): Part? = leaves(root).firstOrNull { it.path == path }?.part

    private fun walk(part: Part, path: String, out: MutableList<Leaf>, depth: Int) {
        if (out.size >= MAX_LEAVES) return
        if (depth < MAX_DEPTH && part.isMimeType("multipart/*")) {
            val multipart = MimeMultipart(part.dataHandler.dataSource)
            for (i in 0 until multipart.count) {
                if (out.size >= MAX_LEAVES) return
                val childPath = if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}"
                walk(multipart.getBodyPart(i), childPath, out, depth + 1)
            }
        } else {
            out += Leaf(path.ifEmpty { "1" }, part)
        }
    }
}
