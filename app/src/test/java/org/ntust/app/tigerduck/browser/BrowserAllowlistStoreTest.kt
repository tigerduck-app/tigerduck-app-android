package org.ntust.app.tigerduck.browser

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** The developer page's changes to the allowlist, and a release build ignoring them. */
class BrowserAllowlistStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = JvmContext()
        context.getSharedPreferences(BrowserAllowlistStore.PREFS_FILE, 0).edit().clear().commit()
    }

    private fun debugStore() = BrowserAllowlistStore(context, debug = true)

    @Test
    fun `entries added go after the defaults, in order, and survive a new store`() {
        val store = debugStore()
        assertEquals(BrowserAllowlistStore.AddResult.ADDED, store.add("example.com"))
        assertEquals(BrowserAllowlistStore.AddResult.ADDED, store.add("*.test.example"))

        val again = debugStore()
        assertEquals(
            listOf("*.ntust.edu.tw", "example.com", "*.test.example"),
            again.allowlist.value.entries.map { it.text },
        )
        assertTrue(again.allowlist.value.allows("https://a.test.example/"))
    }

    @Test
    fun `a line that is not a domain, a default or one already added is not added`() {
        val store = debugStore()
        assertEquals(BrowserAllowlistStore.AddResult.INVALID, store.add("https://example.com/x"))
        assertEquals(BrowserAllowlistStore.AddResult.ALREADY_LISTED, store.add("*.NTUST.edu.tw"))
        store.add("example.com")
        assertEquals(BrowserAllowlistStore.AddResult.ALREADY_LISTED, store.add("Example.com"))
        assertEquals(1, store.customEntries.size)
    }

    @Test
    fun `removing takes an added entry out and leaves a default`() {
        val store = debugStore()
        store.add("example.com")
        store.remove(AllowlistEntry.Exact("example.com"))
        store.remove(AllowlistEntry.Wildcard("ntust.edu.tw"))

        assertEquals(listOf("*.ntust.edu.tw"), store.allowlist.value.entries.map { it.text })
    }

    @Test
    fun `reset forgets the added entries and turns allow every site off`() {
        val store = debugStore()
        store.add("example.com")
        store.setAllowEverySite(true)
        assertTrue(store.allowlist.value.allowEverySite)

        store.reset()

        assertFalse(store.allowlist.value.allowEverySite)
        assertEquals(listOf("*.ntust.edu.tw"), store.allowlist.value.entries.map { it.text })
    }

    @Test
    fun `a release build goes by the defaults whatever a debug build left`() {
        debugStore().apply {
            add("example.com")
            setAllowEverySite(true)
        }

        val release = BrowserAllowlistStore(context, debug = false)

        assertFalse(release.allowlist.value.allowEverySite)
        assertEquals(listOf("*.ntust.edu.tw"), release.allowlist.value.entries.map { it.text })
        assertEquals(BrowserAllowlistStore.AddResult.INVALID, release.add("other.example"))
        assertTrue(release.customEntries.isEmpty())
    }
}
