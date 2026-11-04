package org.ntust.app.tigerduck.mail.imap

import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol

/**
 * [uids] as an IMAP sequence set, in ascending order, each run of consecutive UIDs as a range:
 * `3,5:7,10`. A range takes only the UIDs inside it, all of which are in [uids], so the set names
 * those and no other.
 */
internal fun uidSet(uids: Collection<Long>): String {
    val sorted = uids.toSortedSet().toList()
    val runs = mutableListOf<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        runs += if (j == i) "${sorted[i]}" else "${sorted[i]}:${sorted[j]}"
        i = j + 1
    }
    return runs.joinToString(",")
}

/** The command that marks [uids] read, or unread: `UID STORE 3,5:7 +FLAGS (\Seen)`. */
internal fun seenStore(uids: Collection<Long>, seen: Boolean): String =
    "UID STORE ${uidSet(uids)} ${if (seen) "+" else "-"}FLAGS (\\Seen)"

/**
 * Sends [command] on the folder's connection. The server's FETCH answers reach the folder, so the
 * flags it keeps of its mail follow, and a NO or BAD throws.
 */
internal fun IMAPFolder.send(command: String) {
    doCommand(IMAPFolder.ProtocolCommand { p: IMAPProtocol ->
        val responses = p.command(command, null)
        p.notifyResponseHandlers(responses)
        p.handleResult(responses[responses.size - 1])
        null
    })
}
