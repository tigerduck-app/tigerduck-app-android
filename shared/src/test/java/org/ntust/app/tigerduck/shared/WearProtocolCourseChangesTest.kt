package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Ported from iOS `WatchPayloadCodecTests`' course changes case, with the malformed payloads besides. */
class WearProtocolCourseChangesTest {

    private val nov5 = LocalDate.of(2026, 11, 5)
    private val nov12 = LocalDate.of(2026, 11, 12)

    @Test
    fun `course changes survive the trip to the watch and an older payload reads as none`() {
        val changes = WatchCourseChanges(
            mapOf(
                "XX900" to mapOf(
                    nov5 to listOf(
                        WatchClassChange(listOf("2"), cancelled = true),
                        WatchClassChange(listOf("3")),
                    ),
                    nov12 to listOf(
                        WatchClassChange(listOf("2", "3"), room = "TR-411", start = "09:30"),
                    ),
                ),
            ),
        )
        val wire = WearProtocol.Schedule.encodeCourseChanges(changes)
        assertEquals(changes, WearProtocol.Schedule.decodeCourseChanges(wire))
        assertEquals(WatchCourseChanges.EMPTY, WearProtocol.Schedule.decodeCourseChanges(null))
    }

    @Test
    fun `the wire carries a changed value only and cancelled always`() {
        val changes = WatchCourseChanges(
            mapOf("XX900" to mapOf(nov5 to listOf(WatchClassChange(listOf("2", "3"), end = "11:00")))),
        )
        assertEquals(
            """{"XX900":{"2026-11-05":[{"periods":["2","3"],"cancelled":false,"end":"11:00"}]}}""",
            WearProtocol.Schedule.encodeCourseChanges(changes),
        )
    }

    @Test
    fun `no changes go as an empty object`() {
        assertEquals("{}", WearProtocol.Schedule.encodeCourseChanges(WatchCourseChanges.EMPTY))
        assertEquals(WatchCourseChanges.EMPTY, WearProtocol.Schedule.decodeCourseChanges("{}"))
    }

    @Test
    fun `a payload that is empty or not an object of courses reads as none`() {
        for (raw in listOf("", "   ", "{", "not json", "[]", "\"XX900\"", "42", "null", """{"XX900":[]}""", "{} trailing")) {
            assertEquals(raw, WatchCourseChanges.EMPTY, WearProtocol.Schedule.decodeCourseChanges(raw))
        }
    }

    @Test
    fun `a day or class of the wrong shape is dropped and the rest kept`() {
        val raw = """
            {"XX900":{
              "2026-11-05":[
                {"periods":["2"],"cancelled":true},
                {"periods":"2"},
                {"periods":[]},
                {"periods":[2]},
                {"cancelled":true},
                {"periods":["3"],"start":"later"},
                {"periods":["3"],"end":"25:00"},
                {"periods":["3"],"room":7},
                {"periods":["4"],"cancelled":"yes"},
                "a class",
                {"periods":["4"],"room":null,"start":null,"futureKey":{"a":1}}
              ],
              "11/12":[{"periods":["2"]}],
              "2026-02-30":[{"periods":["2"]}],
              "2026-11-19":"cancelled",
              "2026-11-26":[{"periods":"x"}]
            },
            "YY100":7,
            "ZZ200":{"2026-11-05":[{"periods":["7"],"room":"","start":"14:40"}]}}
        """.trimIndent()
        assertEquals(
            WatchCourseChanges(
                mapOf(
                    "XX900" to mapOf(
                        nov5 to listOf(
                            WatchClassChange(listOf("2"), cancelled = true),
                            WatchClassChange(listOf("4")),
                        ),
                    ),
                    // An empty room is no move.
                    "ZZ200" to mapOf(nov5 to listOf(WatchClassChange(listOf("7"), start = "14:40"))),
                ),
            ),
            WearProtocol.Schedule.decodeCourseChanges(raw),
        )
    }

    @Test
    fun `a course's classes on a day the school left alone are none`() {
        val changes = WatchCourseChanges(mapOf("XX900" to mapOf(nov5 to listOf(WatchClassChange(listOf("2"))))))
        assertEquals(listOf(WatchClassChange(listOf("2"))), changes.on("XX900", nov5))
        assertEquals(emptyList<WatchClassChange>(), changes.on("XX900", nov12))
        assertEquals(emptyList<WatchClassChange>(), changes.on("YY100", nov5))
    }
}
