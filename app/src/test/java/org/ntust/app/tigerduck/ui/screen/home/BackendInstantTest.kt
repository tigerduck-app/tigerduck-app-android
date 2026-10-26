package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The backend's timestamps ([backendInstant]): Python `isoformat()`, with
 * microseconds unless they are zero. Null, which never reads as a new course
 * reset, for every shape that does not parse.
 */
class BackendInstantTest {

    @Test
    fun `isoformat times parse with and without their fraction`() {
        assertEquals(
            Instant.parse("2026-10-10T16:42:49.666640Z"),
            backendInstant("2026-10-10T16:42:49.666640+00:00"),
        )
        assertEquals(Instant.parse("2026-10-10T16:42:49Z"), backendInstant("2026-10-10T16:42:49+00:00"))
        assertEquals(Instant.parse("2026-10-10T16:42:49Z"), backendInstant("2026-10-10T16:42:49Z"))
        assertEquals(Instant.parse("2026-10-10T08:42:49Z"), backendInstant("2026-10-10T16:42:49+08:00"))
    }

    @Test
    fun `a time with no offset reads as UTC`() {
        assertEquals(Instant.parse("2023-11-14T22:13:20Z"), backendInstant("2023-11-14T22:13:20"))
    }

    @Test
    fun `anything else is null`() {
        assertNull(backendInstant(null))
        assertNull(backendInstant(""))
        assertNull(backendInstant("not a date"))
    }
}
