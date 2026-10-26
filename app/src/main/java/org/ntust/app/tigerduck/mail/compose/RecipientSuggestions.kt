package org.ntust.app.tigerduck.mail.compose

import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.Duration
import java.time.Instant
import kotlin.math.pow

/**
 * Who the compose screen's recipient fields suggest as the student types (tigerduck-app#552): the
 * people they have written to and heard from in the mail on the phone, ranked by how often and
 * how lately. Pure, so it can be tested without a mailbox.
 */
object RecipientSuggestions {
    /** How many a field suggests at once. */
    const val SHOWN = 5

    /** How long it takes a mail to count half as much toward the rank of the people in it. */
    private val HALF_LIFE: Duration = Duration.ofDays(30)

    /**
     * A mail written counts double one read: a teacher the student writes to outranks a list that
     * writes to everyone.
     */
    private const val WRITTEN_TO = 2.0
    private const val HEARD_FROM = 1.0

    /** What a mail with no date counts, as one long past. */
    private const val UNDATED = 0.001

    /** A sender no reply reaches: `noreply@`, `no-reply@`, `do-not-reply@` and the like. */
    private val NO_REPLY = Regex("^(no|do[-_.]?not)[-_.]?reply", RegexOption.IGNORE_CASE)

    /** One person, as they are suggested, with how much the mail on the phone ranks them. */
    data class Contact(val address: MailAddress, val score: Double)

    /**
     * Each person in [mail] once, best first: those [self] wrote to (a mail from [self], its To and
     * Cc) and those who wrote to [self] (any other mail, its sender). A draft counts for nothing,
     * and neither does [self], a mailbox with no address, nor one no reply reaches. Addresses are
     * one person whatever their case; the name is the latest one a mail gave them.
     */
    fun contacts(mail: List<MailSummary>, self: String, now: Instant): List<Contact> {
        class Tally(var address: MailAddress, var at: Instant?, var namedAt: Instant?, var score: Double)
        val tallies = mutableMapOf<String, Tally>()
        for (summary in mail) {
            if (summary.flags.draft) continue
            val written = summary.from?.address.equals(self, ignoreCase = true)
            val people = if (written) summary.to + summary.cc else listOfNotNull(summary.from)
            val at = summary.sentAt ?: summary.receivedAt
            val weight = (if (written) WRITTEN_TO else HEARD_FROM) * decay(at, now)
            for (person in people.distinctBy { it.address.lowercase() }) {
                val address = person.address.trim()
                if (address.isEmpty() || address.equals(self, ignoreCase = true)) continue
                if (NO_REPLY.containsMatchIn(address.substringBefore('@'))) continue
                val name = person.name?.trim()?.takeIf { it.isNotEmpty() }
                val tally = tallies.getOrPut(address.lowercase()) { Tally(MailAddress(name, address), at, if (name != null) at else null, 0.0) }
                tally.score += weight
                if (isLater(at, tally.at)) {
                    tally.at = at
                    tally.address = MailAddress(tally.address.name, address)
                }
                if (name != null && (tally.namedAt == null || isLater(at, tally.namedAt))) {
                    tally.namedAt = at
                    tally.address = MailAddress(name, tally.address.address)
                }
            }
        }
        return tallies.values
            .sortedWith(compareByDescending<Tally> { it.score }.thenBy { it.address.address.lowercase() })
            .map { Contact(it.address, it.score) }
    }

    /**
     * Those of [contacts] whose name or address holds [typed], ignoring case, at most [SHOWN]: the
     * ones a name, a word of one or an address starts with it first, then the rest, each in
     * [contacts]' order. None for nothing typed, nor any of [taken], the addresses the mail
     * already has.
     */
    fun matching(contacts: List<Contact>, typed: String, taken: Collection<String>): List<MailAddress> {
        val query = typed.trim()
        if (query.isEmpty()) return emptyList()
        val excluded = taken.mapTo(mutableSetOf()) { it.lowercase() }
        val found = contacts.filter { contact ->
            val a = contact.address
            a.address.lowercase() !in excluded &&
                (a.address.contains(query, ignoreCase = true) || a.name?.contains(query, ignoreCase = true) == true)
        }
        val (starting, within) = found.partition { contact ->
            val a = contact.address
            a.address.startsWith(query, ignoreCase = true) ||
                a.name?.split(' ')?.any { it.startsWith(query, ignoreCase = true) } == true
        }
        return (starting + within).take(SHOWN).map { it.address }
    }

    private fun decay(at: Instant?, now: Instant): Double {
        at ?: return UNDATED
        val days = Duration.between(at, now).toMillis().coerceAtLeast(0) / Duration.ofDays(1).toMillis().toDouble()
        return 0.5.pow(days / HALF_LIFE.toDays())
    }

    /** Whether [at] is later than [than], an undated mail being earlier than any dated one. */
    private fun isLater(at: Instant?, than: Instant?): Boolean = at != null && (than == null || at > than)
}
