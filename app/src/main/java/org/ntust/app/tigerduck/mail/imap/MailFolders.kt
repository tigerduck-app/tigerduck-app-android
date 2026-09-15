package org.ntust.app.tigerduck.mail.imap

/**
 * Mail2000's own folders (spec appendix A.1). The server has no SPECIAL-USE,
 * so they are recognised by name — raw modified UTF-7 or decoded, since Angus
 * hands folder names back decoded. Never create any of these.
 */
enum class SpecialFolder(val imapName: String, val decodedName: String) {
    INBOX("INBOX", "INBOX"),
    SENT("&W8RO9lCZTv1TIw-", "寄件備份匣"),
    DRAFTS("&g0l6P1Mj-", "草稿匣"),
    JUNK("&XuNUSk,hUyM-", "廣告信匣"),
    TRASH("&Vt5lNntS-", "回收筒"),
}

data class ResolvedFolders(val special: Map<SpecialFolder, String>, val others: List<String>) {
    fun nameOf(folder: SpecialFolder): String? = special[folder]

    fun kindOf(name: String): SpecialFolder? = special.entries.firstOrNull { it.value == name }?.key
}

object MailFolders {
    fun resolve(serverNames: List<String>): ResolvedFolders {
        val special = SpecialFolder.entries.mapNotNull { folder ->
            val match = serverNames.firstOrNull { it == folder.imapName }
                ?: serverNames.firstOrNull { it == folder.decodedName }
                ?: if (folder == SpecialFolder.INBOX) serverNames.firstOrNull { it.equals("INBOX", ignoreCase = true) } else null
            match?.let { folder to it }
        }.toMap()
        val others = serverNames.filter { it !in special.values }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ResolvedFolders(special, others)
    }
}
