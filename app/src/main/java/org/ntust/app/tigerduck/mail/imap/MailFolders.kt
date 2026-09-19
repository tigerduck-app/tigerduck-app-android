package org.ntust.app.tigerduck.mail.imap

/**
 * Mail2000's own folders (spec appendix A.1). The server has no SPECIAL-USE,
 * so they are recognised by name — raw modified UTF-7 or decoded, since Angus
 * hands folder names back decoded.
 *
 * None of these is ever created speculatively: not at sign-in, not when the
 * folder list refreshes, and never in [MailFolders.resolve], which only ever
 * reports what the server already has. An account that lacks one keeps lacking
 * it until an operation genuinely needs it — at which point the repository
 * creates [SENT], [DRAFTS] or [TRASH] on demand, by [decodedName], because that
 * is the form the IMAP layer encodes for the wire.
 *
 * [INBOX] and [JUNK] are never created at all: INBOX always exists by RFC, and
 * the junk folder is where the *server's* spam classifier files mail, so one
 * the server does not know about would collect nothing.
 */
enum class SpecialFolder(val imapName: String, val decodedName: String) {
    INBOX("INBOX", "INBOX"),
    SENT("&W8RO9lCZTv1TIw-", "寄件備份匣"),
    DRAFTS("&g0l6P1Mj-", "草稿匣"),
    JUNK("&XuNUSk,hUyM-", "廣告信匣"),
    TRASH("&Vt5lNntS-", "回收筒"),
}

/**
 * What the mail list is pointed at.
 *
 * Mail2000 has no server-side "all mail" folder -- there is no SPECIAL-USE and IMAP has no
 * cross-folder view at all -- so All mail is a client-side merge with no name any server
 * command would accept. Modelling it as its own case instead of a magic folder string is the
 * whole point: [Real.name] is the only thing in this type that may ever reach a `SELECT`, and
 * every `when` over a selection has to state out loud what it does with [AllMail].
 */
sealed interface FolderSelection {
    /** A folder the server actually has, named exactly as `LIST` reported it. */
    data class Real(val name: String) : FolderSelection

    /** All mail: [MERGED] merged client-side, newest first. Never a folder name. */
    data object AllMail : FolderSelection {
        /**
         * The only folders All mail merges -- the two that hold real correspondence. Drafts,
         * junk, trash and user folders stay out on purpose: a merged view is a read view over
         * mail the student actually exchanged, and sweeping trash or drafts into it would put
         * those messages one tap from actions whose safety rules are written per folder.
         */
        val MERGED = listOf(SpecialFolder.INBOX, SpecialFolder.SENT)
    }
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
