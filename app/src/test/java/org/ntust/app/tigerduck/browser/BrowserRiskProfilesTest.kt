package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** The profiles a page opened at risk loads in: one each, never used again, and cleared and deleted (tigerduck-app#958). */
class BrowserRiskProfilesTest {

    /** The WebView's profiles: those on disk, and those this process loaded, which it cannot delete. */
    private class FakeProfiles(var supported: Boolean = true) : WebViewProfiles {
        val onDisk = mutableListOf("Default")
        val loaded = mutableSetOf<String>()
        val cleared = mutableListOf<String>()

        override fun supported() = supported
        override fun names(): List<String> = onDisk.toList()
        override fun delete(name: String) {
            check(name !in loaded) { "loaded in this process" }
            onDisk.remove(name)
        }
        override fun clear(name: String, done: () -> Unit) {
            cleared += name
            done()
        }
    }

    private val cleanup = BrowserCleanup(JvmContext())

    @Test
    fun `each browser opened at risk gets a new profile, marked to delete before it is made`() {
        val profiles = BrowserRiskProfiles(FakeProfiles())

        val first = profiles.create(cleanup)!!
        val second = profiles.create(cleanup)!!

        assertNotEquals(first, second)
        assertTrue(first.startsWith(BrowserRiskProfiles.PREFIX))
        assertTrue(profiles.isMadeHere(first) && profiles.isMadeHere(second))
        assertTrue(cleanup.riskProfilesLeft())
    }

    @Test
    fun `none where the WebView has no profiles, and nothing marked`() {
        val profiles = BrowserRiskProfiles(FakeProfiles(supported = false))

        assertFalse(profiles.supported())
        assertNull(profiles.create(cleanup))
        assertFalse(cleanup.riskProfilesLeft())
    }

    @Test
    fun `one saved by an earlier process is not this one's`() {
        assertFalse(BrowserRiskProfiles(FakeProfiles()).isMadeHere(BrowserRiskProfiles.PREFIX + "from-before"))
    }

    @Test
    fun `a sweep deletes each profile an earlier process left, never the app's own or one made here`() {
        val fake = FakeProfiles()
        val earlier = BrowserRiskProfiles(fake)
        val left = earlier.create(cleanup)!!
        fake.onDisk += left
        val profiles = BrowserRiskProfiles(fake)
        val made = profiles.create(cleanup)!!
        fake.onDisk += listOf(made, "someone-else's")
        fake.loaded += made

        profiles.sweep(cleanup)

        assertEquals(listOf("Default", made, "someone-else's"), fake.onDisk)
        assertTrue("the one made here is left for a later process", cleanup.riskProfilesLeft())
    }

    @Test
    fun `the mark comes off once none is left, and none was made here`() {
        val fake = FakeProfiles()
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        fake.onDisk += left

        BrowserRiskProfiles(fake).sweep(cleanup)

        assertEquals(listOf("Default"), fake.onDisk)
        assertFalse(cleanup.riskProfilesLeft())
    }

    @Test
    fun `one that would not go stays marked for the next try`() {
        val fake = FakeProfiles()
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        fake.onDisk += left
        fake.loaded += left

        BrowserRiskProfiles(fake).sweep(cleanup)

        assertTrue(left in fake.onDisk)
        assertTrue(cleanup.riskProfilesLeft())
    }

    @Test
    fun `with nothing marked a sweep asks the WebView nothing`() {
        val fake = object : WebViewProfiles {
            override fun supported(): Boolean = error("asked")
            override fun names(): List<String> = error("asked")
            override fun delete(name: String): Unit = error("asked")
            override fun clear(name: String, done: () -> Unit): Unit = error("asked")
        }

        BrowserRiskProfiles(fake).sweep(cleanup)
    }

    @Test
    fun `clearing empties a profile made here, and none other`() {
        val fake = FakeProfiles()
        val profiles = BrowserRiskProfiles(fake)
        val made = profiles.create(cleanup)!!
        var done = 0

        profiles.clear(made) { done++ }
        profiles.clear(BrowserRiskProfiles.PREFIX + "from-before") { done++ }
        profiles.clear("Default") { done++ }

        assertEquals(listOf(made), fake.cleared)
        assertEquals("each says it is done", 3, done)
    }

    @Test
    fun `a clearing that fails still says it is done`() {
        val fake = object : WebViewProfiles {
            override fun supported() = true
            override fun names() = emptyList<String>()
            override fun delete(name: String) = Unit
            override fun clear(name: String, done: () -> Unit): Unit = throw IllegalStateException("gone")
        }
        val profiles = BrowserRiskProfiles(fake)
        var done = false

        profiles.clear(profiles.create(cleanup)!!) { done = true }

        assertTrue(done)
    }
}
