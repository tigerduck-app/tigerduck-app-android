package org.ntust.app.tigerduck.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `/v3/sync/full`'s `roster_requests` (backend 3.10.1): the terms whose roster the backend asks
 * for, and when this phone asks course selection for one. As the iPhone's `RosterRequestLog`
 * tests (tigerduck-app#914).
 */
class RosterRequestsTest {

    @Test
    fun `a snapshot without roster_requests asks for nothing`() {
        assertEquals(emptyList<String>(), parseRosterRequests(JSONObject("""{"courses": []}""")))
        assertEquals(emptyList<String>(), parseRosterRequests(JSONObject("""{"roster_requests": null}""")))
        assertEquals(listOf("1151"), parseRosterRequests(JSONObject("""{"roster_requests": ["1151", "", "1151"]}""")))
    }

    @Test
    fun `a roster request goes to the school once, then again only after an hour or a new sign-in`() {
        val log = RosterRequestLog()
        val now = 1_800_000_000_000L
        val asked = listOf("1151", "1151")
        val hour = RosterRequestLog.RETRY_INTERVAL_MS

        assertEquals(emptyList<String>(), log.termsToAsk(emptyList(), generation = 1, nowMs = now))
        assertEquals(listOf("1151"), log.termsToAsk(asked, generation = 1, nowMs = now))
        assertEquals(emptyList<String>(), log.termsToAsk(asked, generation = 1, nowMs = now + hour - 1))
        assertEquals(listOf("1151"), log.termsToAsk(asked, generation = 2, nowMs = now + hour - 1))
        assertEquals(listOf("1151"), log.termsToAsk(asked, generation = 2, nowMs = now + 2 * hour - 1))
        // A clock set back past the last ask does not hold it off.
        assertEquals(listOf("1151"), log.termsToAsk(asked, generation = 2, nowMs = now))
    }
}
