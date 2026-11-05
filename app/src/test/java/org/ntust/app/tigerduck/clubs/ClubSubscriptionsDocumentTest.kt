package org.ntust.app.tigerduck.clubs

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Club
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamp
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamped
import java.time.Instant

/** The `club_subscriptions` document's merge, order and wire form, as iOS's ClubSubscriptionsDocumentTests. */
class ClubSubscriptionsDocumentTest {

    companion object {
        fun at(minute: Int): Stamp = Stamp.of(Instant.ofEpochSecond(1_793_620_800L + minute * 60L))

        fun club(subscribed: Boolean? = null, subscribedAt: Int = 0, color: Int? = null, colorAt: Int = 0) = Club(
            subscribed = subscribed?.let { Stamped(it, at(subscribedAt)) },
            color = color?.let { Stamped(it, at(colorAt)) },
        )
    }

    @Test
    fun `each value takes the side that set it later, so two devices' edits of different fields both stay`() {
        val local = ClubSubscriptionsDocument(
            mapOf(93 to club(false, 5, 0x111111, 1), 33 to club(true, 2, 0x222222, 2)),
            Stamped(listOf(33), at(2)),
        )
        val server = ClubSubscriptionsDocument(
            mapOf(93 to club(true, 1, 0x333333, 4), 41 to club(true, 3, 0x444444, 3)),
            Stamped(listOf(93, 41), at(3)),
        )

        val merged = ClubSubscriptionsDocument.merged(local, server)

        assertEquals(club(false, 5, 0x333333, 4), merged.clubs[93])
        assertEquals(club(true, 2, 0x222222, 2), merged.clubs[33])
        assertEquals(club(true, 3, 0x444444, 3), merged.clubs[41])
        assertEquals(Stamped(listOf(93, 41), at(3)), merged.order)
        assertEquals(listOf(41, 33), merged.subscribedIds)
    }

    @Test
    fun `a tie keeps the server's value, and a value one side lacks comes from the other`() {
        val local = ClubSubscriptionsDocument(mapOf(93 to club(true, 1, 0x111111, 1)), Stamped(listOf(93), at(1)))
        val server = ClubSubscriptionsDocument(mapOf(93 to club(false, 1)), Stamped(emptyList(), at(1)))

        val merged = ClubSubscriptionsDocument.merged(local, server)

        assertEquals(club(false, 1, 0x111111, 1), merged.clubs[93])
        assertEquals(emptyList<Int>(), merged.order?.value)
        assertEquals(server, ClubSubscriptionsDocument.merged(ClubSubscriptionsDocument(), server))
        assertEquals(local, ClubSubscriptionsDocument.merged(local, ClubSubscriptionsDocument()))
    }

    @Test
    fun `a tie is by the time, whatever the offset or precision it was written with`() {
        val written = Stamp.read("2026-11-02T22:00:00.000+08:00")!!
        val local = ClubSubscriptionsDocument(mapOf(93 to Club(subscribed = Stamped(true, written))))
        val server = ClubSubscriptionsDocument(mapOf(93 to Club(subscribed = Stamped(false, Stamp.read("2026-11-02T14:00:00Z")!!))))

        assertEquals(false, ClubSubscriptionsDocument.merged(local, server).clubs[93]?.subscribed?.value)
    }

    @Test
    fun `the shown order is the user's order of subscribed clubs, then the rest by subscription time and id`() {
        val document = ClubSubscriptionsDocument(
            mapOf(
                1 to club(true, 9), 2 to club(true, 4), 3 to club(false, 5),
                4 to club(true, 4), 5 to club(true, 1), 6 to club(color = 0x123456),
            ),
            Stamped(listOf(3, 5, 1, 5, 99), at(6)),
        )

        assertEquals(listOf(5, 1, 2, 4), document.subscribedIds)
        assertEquals(listOf(5, 2, 4, 1), ClubSubscriptionsDocument(document.clubs).subscribedIds)
    }

    @Test
    fun `subscribing puts a club last with its default color, and unsubscribing keeps the color for a later subscription`() {
        var document = ClubSubscriptionsDocument()
            .subscribe(93, 0xD35400, at(1))
            .subscribe(33, 0xC0392B, at(2))
        assertEquals(listOf(93, 33), document.subscribedIds)
        assertEquals(club(true, 2, 0xC0392B, 2), document.clubs[33])
        assertEquals(Stamped(listOf(93, 33), at(2)), document.order)
        assertEquals(document, document.subscribe(33, 0, at(3)))

        document = document.setColor(0xFF0000, 93, at(4)).unsubscribe(93, at(5))
        assertEquals(listOf(33), document.subscribedIds)
        assertEquals(Stamped(0xFF0000, at(4)), document.clubs[93]?.color)

        document = document.subscribe(93, 0xD35400, at(6))
        assertEquals(listOf(33, 93), document.subscribedIds)
        assertEquals(0xFF0000, document.clubs[93]?.color?.value)
        assertEquals(document, document.unsubscribe(41, at(7)))
    }

    @Test
    fun `reordering stamps the order only when it changes, and setting the same color changes nothing`() {
        var document = listOf(93, 33, 41).foldIndexed(ClubSubscriptionsDocument()) { minute, doc, id -> doc.subscribe(id, 0, at(minute)) }
        val before = document

        assertEquals(before, document.reorder(listOf(93, 33, 41), at(9)).setColor(0, 93, at(9)))

        document = document.reorder(listOf(41, 7, 93, 41, 33), at(10))
        assertEquals(Stamped(listOf(41, 93, 33), at(10)), document.order)
        assertEquals(0xCDEF12, document.setColor(0xABCDEF12.toInt(), 41, at(11)).clubs[41]?.color?.value)
    }

    @Test
    fun `an edit's stamp, as written, is later than the latest stamp the document holds, at every millisecond`() {
        for (millis in listOf("000", "001", "123", "499", "500", "501", "998", "999")) {
            val latest = Stamp.read("2099-01-01T00:00:00.${millis}Z")!!
            val document = ClubSubscriptionsDocument(mapOf(93 to Club(color = Stamped(0x111111, latest))))

            val stamp = document.stamp(Instant.ofEpochSecond(1_793_620_800L))

            assertTrue(millis, Stamp.read(stamp.text)!!.instant.isAfter(latest.instant))
        }
        assertEquals("2026-11-02T12:00:00.000Z", at(0).text)
        assertEquals(at(3), ClubSubscriptionsDocument(mapOf(1 to club(true, 2))).stamp(at(3).instant))
    }

    @Test
    fun `values another writer left unreadable or without a time read as absent, and the merge keeps the device's`() {
        val read = ClubSubscriptionsWire.read(
            """{"clubs": {
                "93": {"subscribed": 1, "subscribed_at": "2026-11-02T14:00:00.000Z", "color": "red", "color_at": "2026-11-02T14:00:00Z"},
                "33": {"subscribed": "yes", "subscribed_at": "2026-11-02T14:00:00Z", "color": "#C0392B", "color_at": "later"},
                "41": {"subscribed": true, "color": "#1ABC9G", "color_at": "2026-11-02T14:00:00Z"},
                "128": {"subscribed": true, "subscribed_at": "2026-11-02T14:00:00.123456+00:00", "color": "#f1c40f", "color_at": "2026-11-02T14:00:00Z"},
                "039": {"subscribed": true, "subscribed_at": "2026-11-02T14:00:00Z"},
                "abc": {"subscribed": true, "subscribed_at": "2026-11-02T14:00:00Z"},
                "110": "subscribed",
                "1": {"subscribed": true, "subscribed_at": "2026-11-02T14:00:00Z", "color": "#DDA0DD0", "color_at": "2026-11-02T14:00:00Z"}
            }, "order": {"ids": [128, "93", null, 1, 128], "at": "2026-11-02T14:00:00Z"}}""",
        )

        assertEquals(setOf(128, 1), read.clubs.keys)
        assertEquals(0xF1C40F, read.clubs[128]?.color?.value)
        assertEquals("2026-11-02T14:00:00.123456+00:00", read.clubs[128]?.subscribed?.at?.text)
        assertNull(read.clubs[1]?.color)
        assertEquals(listOf(128, 1, 128), read.order?.value)
        assertEquals(listOf(128, 1), read.subscribedIds)

        val local = ClubSubscriptionsDocument(mapOf(93 to club(true, 0, 0xD35400, 0)))
        assertEquals(local.clubs[93], ClubSubscriptionsDocument.merged(local, read).clubs[93])
        for (broken in listOf("[]", "null", "not json", """{"clubs": [], "order": "93"}""", """{"order": {"ids": [93]}}""", null)) {
            assertEquals(broken, ClubSubscriptionsDocument(), ClubSubscriptionsWire.read(broken))
        }
    }

    @Test
    fun `a document reads back as written, its stamps' text unchanged`() {
        val server = Stamp.read("2026-11-02T14:00:00.123456+00:00")!!
        val document = ClubSubscriptionsDocument(
            mapOf(93 to Club(Stamped(true, server), Stamped(0x0000FF, at(1))), 33 to club(false, 2)),
            Stamped(listOf(93), at(3)),
        )

        val written = ClubSubscriptionsWire.json(document)
        val club = written.getAsJsonObject("clubs").getAsJsonObject("93")

        assertEquals(document, ClubSubscriptionsWire.read(written.toString()))
        assertEquals("#0000FF", club.get("color").asString)
        assertEquals("2026-11-02T14:00:00.123456+00:00", club.get("subscribed_at").asString)
        assertEquals(
            JsonParser.parseString(
                """{"clubs": {"33": {"subscribed": false, "subscribed_at": "2026-11-02T12:02:00.000Z"},
                    "93": {"color": "#0000FF", "color_at": "2026-11-02T12:01:00.000Z", "subscribed": true,
                           "subscribed_at": "2026-11-02T14:00:00.123456+00:00"}},
                    "order": {"at": "2026-11-02T12:03:00.000Z", "ids": [93]}}""",
            ),
            written,
        )
    }
}
