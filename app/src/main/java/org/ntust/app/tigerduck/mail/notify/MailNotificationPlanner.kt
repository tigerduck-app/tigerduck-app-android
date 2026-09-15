package org.ntust.app.tigerduck.mail.notify

import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailSummary

/** What to post for a batch of new mail (spec §8.6). Pure, so the rules are unit-tested. */
object MailNotificationPlanner {
    const val COLLAPSE_ABOVE = 5
    const val AUTH_FAILED_ID = 40_999
    const val SUMMARY_ID = 41_000

    data class Item(val uid: Long, val title: String, val text: String)

    sealed interface Plan {
        data class Individual(val items: List<Item>) : Plan
        data class Summary(val count: Int) : Plan
    }

    fun notificationId(uid: Long): Int = SUMMARY_ID + 1 + (uid % 100_000).toInt()

    fun plan(messages: List<MailSummary>, noSender: String, noSubject: String): Plan? = when {
        messages.isEmpty() -> null
        messages.size > COLLAPSE_ABOVE -> Plan.Summary(messages.size)
        else -> Plan.Individual(
            messages.sortedBy { it.uid }.map { m ->
                Item(
                    uid = m.uid,
                    title = TextCleaning.clean(m.from?.display).ifBlank { noSender },
                    text = TextCleaning.clean(m.subject).ifBlank { noSubject },
                )
            },
        )
    }
}
