package org.ntust.app.tigerduck.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A round's Moodle status calls go at most four at a time ([mapAtMost]). */
class BoundedMapTest {

    @Test
    fun `no more than the limit run at once, and the results keep their order`() = runTest {
        var running = 0
        var most = 0

        val results = mapAtMost(MAX_CONCURRENT_SCHOOL_REQUESTS, (1..20).toList()) { n ->
            running++
            most = maxOf(most, running)
            delay(10)
            running--
            n * 2
        }

        assertEquals(MAX_CONCURRENT_SCHOOL_REQUESTS, most)
        assertEquals((1..20).map { it * 2 }, results)
    }
}
