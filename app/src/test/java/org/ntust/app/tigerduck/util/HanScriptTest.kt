package org.ntust.app.tigerduck.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HanScriptTest {

    @Test
    fun `Han from every block counts, a supplementary one too`() {
        assertTrue(HanScript.containsHan("資料結構"))
        // U+20000, CJK Unified Ideographs Extension B: a surrogate pair in a String.
        assertTrue(HanScript.containsHan("𠀀"))
        assertFalse(HanScript.containsHan("Data Structures 101"))
    }
}
