package org.ntust.app.tigerduck.mail.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnedDeletedCodecTest {
    @Test
    fun `round trips folder names with spaces and CJK`() {
        val entry = OwnedDeletedCodec.encode("Moodle 課程討論區", 1722230694, 3976)
        assertEquals(Triple("Moodle 課程討論區", 1722230694L, 3976L), OwnedDeletedCodec.decode(entry))
        assertNull(OwnedDeletedCodec.decode("garbage"))
    }
}
