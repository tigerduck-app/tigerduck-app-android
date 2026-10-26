package org.ntust.app.tigerduck.network.tls

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.tls.PinAcceptance
import org.ntust.app.tigerduck.util.JvmContext

class PinAcceptanceStoreTest {

    private val context = JvmContext()
    private val prefs get() = context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE)

    private fun store(versionCode: Long = 30) = PinAcceptanceStore(prefs, versionCode)

    @Test
    fun `an acceptance holds under the build it was made in`() {
        store().accept("ntust", setOf("a", "b"), now = 5)

        assertEquals(listOf(PinAcceptance("ntust", setOf("a", "b"), 30, 5)), store().all)
    }

    @Test
    fun `a later acceptance for the same set adds to what it trusted`() {
        store().accept("ntust", setOf("a"), now = 5)
        store().accept("ntust", setOf("b"), now = 9)

        assertEquals(listOf(PinAcceptance("ntust", setOf("a", "b"), 30, 9)), store().all)
    }

    @Test
    fun `an update drops every acceptance made under another build`() {
        store(versionCode = 29).accept("ntust", setOf("a"), now = 5)

        assertTrue(store(versionCode = 30).all.isEmpty())
        // And for good, not just for the newer build's reading.
        assertTrue(store(versionCode = 29).all.isEmpty())
    }

    @Test
    fun `a full reset's wipe keeps the acceptances`() {
        store().accept("library", setOf("c"), now = 5)

        AppPreferences(context).clearAllPrefs()
        context.cacheDir.deleteRecursively()

        assertEquals(setOf("c"), store().all.single().spkis)
    }

    @Test
    fun `a Cancel keeps its set quiet for an hour`() {
        val at = 1_000_000L
        store().cancelled("ntust", at)

        assertTrue(store().isQuiet("ntust", at))
        assertTrue(store().isQuiet("ntust", at + PinAcceptanceStore.QUIET_MS - 1))
        assertFalse(store().isQuiet("ntust", at + PinAcceptanceStore.QUIET_MS))
        assertFalse(store().isQuiet("library", at))
        // A clock set back doesn't stretch it.
        assertFalse(store().isQuiet("ntust", at - 1))
    }
}
