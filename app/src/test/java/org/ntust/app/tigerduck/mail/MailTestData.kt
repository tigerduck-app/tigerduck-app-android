package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.Instant

fun mailSummary(
    uid: Long,
    subject: String = "subject $uid",
    from: MailAddress? = MailAddress("教務處", "office@mail.ntust.edu.tw"),
    seen: Boolean = false,
    to: List<MailAddress> = listOf(MailAddress(null, "b10000001@mail.ntust.edu.tw")),
    cc: List<MailAddress> = emptyList(),
    messageId: String? = "<m$uid@x>",
    sentAt: Instant? = Instant.ofEpochMilli(1_789_466_442_000),
    hasAttachments: Boolean = false,
) = MailSummary(uid, from, emptyList(), to, cc, subject, sentAt, sentAt, MailFlags.NONE.copy(seen = seen),
    1_000, hasAttachments, messageId, null, null)
