package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryQRCacheTest {

    private val cache = LibraryQRCache<String>()

    @Test
    fun `a code is reused while it has at least 15 seconds left`() {
        cache.store("code", "image", account = 1, issuedAt = 1_000)

        assertEquals(30, cache.reusable(1, now = 1_000)?.second)
        assertEquals(15, cache.reusable(1, now = 16_999)?.second)
        assertNull(cache.reusable(1, now = 17_000))
    }

    @Test
    fun `a code belongs to the account it was fetched for`() {
        cache.store("code", "image", account = 1, issuedAt = 0)

        assertNull(cache.reusable(2, now = 0))
    }

    @Test
    fun `clearing drops the code`() {
        cache.store("code", "image", account = 1, issuedAt = 0)
        cache.clear()

        assertNull(cache.reusable(1, now = 0))
    }

    @Test
    fun `memory pressure drops the image and keeps the code`() {
        cache.store("code", "image", account = 1, issuedAt = 0)
        cache.dropImage()

        val entry = cache.reusable(1, now = 0)?.first
        assertEquals("code", entry?.payload)
        assertNull(entry?.image)
    }

    @Test
    fun `a clock behind the code leaves it no time`() {
        assertEquals(0, LibraryQRCache.secondsLeft(issuedAt = 10_000, now = 5_000))
    }
}
