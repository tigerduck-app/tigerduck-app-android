package org.ntust.app.tigerduck.clubs

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The `club_subscriptions` settings document, which the Apple and Android apps write: for each
 * club whether it is subscribed and its color, each with the time it was set, and the order of the
 * subscribed clubs with its time (the Apple repo's docs/decisions/0030). A value another writer
 * left unreadable, or without its time, reads as absent ([ClubSubscriptionsWire]).
 */
data class ClubSubscriptionsDocument(
    val clubs: Map<Int, Club> = emptyMap(),
    val order: Stamped<List<Int>>? = null,
) {
    /** When a value was set: the text as written, which a write sends back unchanged, and its time. */
    class Stamp private constructor(val text: String, val instant: Instant) {
        override fun equals(other: Any?): Boolean = other is Stamp && other.text == text && other.instant == instant
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = text

        companion object {
            /** In UTC to the millisecond with a `Z`, as the app's other sync stamps are written. */
            private val written = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

            /** [text] as an ISO 8601 time with any offset and precision; null for one that is not. */
            fun read(text: String): Stamp? = try {
                Stamp(text, OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant())
            } catch (e: DateTimeParseException) {
                null
            }

            /** [instant] to the millisecond, as written. */
            fun of(instant: Instant): Stamp {
                val millis = instant.truncatedTo(ChronoUnit.MILLIS)
                return Stamp(written.format(millis), millis)
            }
        }
    }

    data class Stamped<T>(val value: T, val at: Stamp)

    data class Club(val subscribed: Stamped<Boolean>? = null, val color: Stamped<Int>? = null)

    /**
     * The subscribed clubs, highest priority first: the user's order, then the clubs it lacks by
     * the time they were subscribed, then by id.
     */
    val subscribedIds: List<Int>
        get() {
            val since = clubs.mapNotNull { (id, club) -> club.subscribed?.takeIf { it.value }?.let { id to it.at.instant } }.toMap()
            val listed = LinkedHashSet<Int>()
            order?.value.orEmpty().forEach { if (it in since) listed += it }
            val rest = since.filterKeys { it !in listed }.entries
                .sortedWith(compareBy<Map.Entry<Int, Instant>>({ it.value }, { it.key })).map { it.key }
            return listed.toList() + rest
        }

    fun isSubscribed(id: Int): Boolean = clubs[id]?.subscribed?.value == true

    val hasSubscriptions: Boolean get() = clubs.values.any { it.subscribed?.value == true }

    /**
     * The stamp of an edit made at [now]: [now], or 1 ms after the latest stamp the document holds
     * when that is later, so a value another device stamped ahead by a wrong clock cannot outlast
     * the edits made after it.
     */
    fun stamp(now: Instant): Stamp {
        val latest = (clubs.values.flatMap { listOfNotNull(it.subscribed?.at, it.color?.at) } + listOfNotNull(order?.at))
            .maxOfOrNull { it.instant } ?: return Stamp.of(now)
        return Stamp.of(maxOf(now, latest.plusMillis(1)))
    }

    /** Puts the club last; it keeps the color it had, and one never given a color gets [defaultColor], stamped. */
    fun subscribe(id: Int, defaultColor: Int, at: Stamp): ClubSubscriptionsDocument {
        if (isSubscribed(id)) return this
        val club = clubs[id] ?: Club()
        val next = club.copy(subscribed = Stamped(true, at), color = club.color ?: Stamped(defaultColor, at))
        val withClub = copy(clubs = clubs + (id to next))
        return withClub.copy(order = Stamped(withClub.subscribedIds.filter { it != id } + id, at))
    }

    /** Keeps the club's color for a later subscription. */
    fun unsubscribe(id: Int, at: Stamp): ClubSubscriptionsDocument {
        val club = clubs[id]?.takeIf { isSubscribed(id) } ?: return this
        return copy(clubs = clubs + (id to club.copy(subscribed = Stamped(false, at))))
    }

    /** [ids] is the subscribed clubs in their new order; ids of other clubs are left out. */
    fun reorder(ids: List<Int>, at: Stamp): ClubSubscriptionsDocument {
        val kept = LinkedHashSet<Int>().apply { ids.forEach { if (isSubscribed(it)) add(it) } }.toList()
        if (kept == subscribedIds) return this
        return copy(order = Stamped(kept, at))
    }

    /** [color] is `0xRRGGBB`; higher bits are dropped, as `#RRGGBB` cannot carry them. */
    fun setColor(color: Int, id: Int, at: Stamp): ClubSubscriptionsDocument {
        val rgb = color and 0xFFFFFF
        val club = clubs[id] ?: Club()
        if (club.color?.value == rgb) return this
        return copy(clubs = clubs + (id to club.copy(color = Stamped(rgb, at))))
    }

    companion object {
        /** Every value takes the side that set it later; a tie keeps the server's. */
        fun merged(local: ClubSubscriptionsDocument, server: ClubSubscriptionsDocument): ClubSubscriptionsDocument {
            val clubs = server.clubs.toMutableMap()
            for ((id, mine) in local.clubs) {
                val theirs = server.clubs[id]
                clubs[id] = Club(later(mine.subscribed, theirs?.subscribed), later(mine.color, theirs?.color))
            }
            return ClubSubscriptionsDocument(clubs, later(local.order, server.order))
        }

        private fun <T> later(local: Stamped<T>?, server: Stamped<T>?): Stamped<T>? {
            if (local == null || server == null) return local ?: server
            return if (local.at.instant.isAfter(server.at.instant)) local else server
        }
    }
}
