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

    fun leaves(root: Part): List<Leaf> = mutableListOf<Leaf>().also { walk(root, "", it) }

    fun find(root: Part, path: String): Part? = leaves(root).firstOrNull { it.path == path }?.part

    private fun walk(part: Part, path: String, out: MutableList<Leaf>) {
        if (part.isMimeType("multipart/*")) {
            val multipart = MimeMultipart(part.dataHandler.dataSource)
            for (i in 0 until multipart.count) {
                val childPath = if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}"
                walk(multipart.getBodyPart(i), childPath, out)
            }
        } else {
            out += Leaf(path.ifEmpty { "1" }, part)
        }
    }
}
