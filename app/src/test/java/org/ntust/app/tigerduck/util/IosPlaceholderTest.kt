package org.ntust.app.tigerduck.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IosPlaceholderTest {

    @Test
    fun `a text placeholder takes its value`() {
        assertEquals("Hello, Ada", "Hello, %1\$@".replaceIosArg(1, "Ada"))
    }

    @Test
    fun `a count placeholder takes its number in every width iOS writes`() {
        // Java's formatter throws on "%2$lld", the form the shared strings carry.
        assertEquals("Oct 14, 3 events", "%1\$@, %2\$lld events".replaceIosArg(1, "Oct 14").replaceIosArg(2, 3L))
        assertEquals("3 and 4", "%1\$ld and %2\$d".replaceIosArg(1, 3L).replaceIosArg(2, 4L))
    }

    @Test
    fun `a count goes only where its own position is named`() {
        assertEquals("%2\$lld left", "%2\$lld left".replaceIosArg(1, 7L))
        assertEquals("%11\$lld", "%11\$lld".replaceIosArg(1, 7L))
    }
}
