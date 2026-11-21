package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** The WebView's profiles: those on disk, and those this process loaded, which it cannot delete. */
internal class FakeWebViewProfiles(var supported: Boolean = true) : WebViewProfiles {
    val onDisk = mutableListOf("Default")
    val loaded = mutableSetOf<String>()
    val cleared = mutableListOf<String>()

    /** Whether [names] lists what is on disk; a WebView might list only the profiles it loaded. */
    var listsOnDisk = true

    override fun supported() = supported
    override fun names(): List<String> = if (listsOnDisk) onDisk.toList() else onDisk.filter { it == "Default" || it in loaded }
    override fun delete(name: String) {
        check(name !in loaded) { "loaded in this process" }
        onDisk.remove(name)
    }
    override fun clear(name: String, done: () -> Unit) {
        cleared += name
        done()
    }
}

/** The profiles a page opened at risk loads in: one each, never used again, and cleared and deleted (tigerduck-app#958). */
class BrowserRiskProfilesTest {

    private val cleanup = BrowserCleanup(JvmContext())

    @Test
    fun `each browser opened at risk gets a new profile, kept by name before it is made`() {
        val profiles = BrowserRiskProfiles(FakeWebViewProfiles())

        val first = profiles.create(cleanup)!!
        val second = profiles.create(cleanup)!!

        assertNotEquals(first, second)
        assertTrue(first.startsWith(BrowserRiskProfiles.PREFIX))
        assertTrue(profiles.isMadeHere(first) && profiles.isMadeHere(second))
        assertEquals(setOf(first, second), cleanup.riskProfiles())
    }

    @Test
    fun `none where its name could not be kept first, so no later process would miss it`() {
        val failing = BrowserCleanup(CommitFailingContext())
        val profiles = BrowserRiskProfiles(FakeWebViewProfiles())

        assertNull(profiles.create(failing))
        assertTrue(failing.riskProfiles().isEmpty())
        assertTrue("none was made here", profiles.madeHere().isEmpty())
    }

    @Test
    fun `none where the WebView has no profiles, and nothing kept`() {
        val profiles = BrowserRiskProfiles(FakeWebViewProfiles(supported = false))

        assertFalse(profiles.supported())
        assertNull(profiles.create(cleanup))
        assertTrue(cleanup.riskProfiles().isEmpty())
    }

    @Test
    fun `one an earlier process made is not this one's`() {
        val earlier = BrowserRiskProfiles(FakeWebViewProfiles()).create(cleanup)!!

        assertFalse(BrowserRiskProfiles(FakeWebViewProfiles()).isMadeHere(earlier))
    }

    @Test
    fun `a sweep deletes each profile an earlier process left, never the app's own or one made here`() {
        val fake = FakeWebViewProfiles()
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        val profiles = BrowserRiskProfiles(fake)
        val made = profiles.create(cleanup)!!
        val unkept = BrowserRiskProfiles.PREFIX + "listed-only"
        fake.onDisk += listOf(left, made, unkept, "someone-else's")
        fake.loaded += made

        profiles.sweep(cleanup)

        assertEquals(listOf("Default", made, "someone-else's"), fake.onDisk)
        assertEquals("the one made here is left for a later process", setOf(made), cleanup.riskProfiles())
    }

    @Test
    fun `a kept name goes though the WebView does not list it, and then nothing is kept`() {
        val fake = FakeWebViewProfiles().apply { listsOnDisk = false }
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        fake.onDisk += left

        BrowserRiskProfiles(fake).sweep(cleanup)

        assertEquals(listOf("Default"), fake.onDisk)
        assertTrue(cleanup.riskProfiles().isEmpty())
    }

    @Test
    fun `one that would not go stays kept for the next try`() {
        val fake = FakeWebViewProfiles()
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        fake.onDisk += left
        fake.loaded += left

        BrowserRiskProfiles(fake).sweep(cleanup)

        assertTrue(left in fake.onDisk)
        assertEquals(setOf(left), cleanup.riskProfiles())
    }

    @Test
    fun `with nothing kept a sweep asks the WebView nothing`() {
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
        val fake = FakeWebViewProfiles()
        val profiles = BrowserRiskProfiles(fake)
        val made = profiles.create(cleanup)!!
        val done = mutableListOf<Boolean>()

        profiles.clear(made) { done += it }
        profiles.clear(BrowserRiskProfiles.PREFIX + "from-before") { done += it }
        profiles.clear("Default") { done += it }

        assertEquals(listOf(made), fake.cleared)
        assertEquals("each says it is done", listOf(true, true, true), done)
    }

    @Test
    fun `a clearing that fails with an error still says it is done`() {
        val fake = object : WebViewProfiles {
            override fun supported() = true
            override fun names() = emptyList<String>()
            override fun delete(name: String) = Unit
            override fun clear(name: String, done: () -> Unit): Unit = throw NoSuchMethodError("an older WebView")
        }
        val profiles = BrowserRiskProfiles(fake)
        var done: Boolean? = null

        profiles.clear(profiles.create(cleanup)!!) { done = it }

        assertEquals("done, and not cleared", false, done)
    }

    @Test
    fun `a clearing that fails still says it is done, and that it failed`() {
        val fake = object : WebViewProfiles {
            override fun supported() = true
            override fun names() = emptyList<String>()
            override fun delete(name: String) = Unit
            override fun clear(name: String, done: () -> Unit): Unit = throw IllegalStateException("gone")
        }
        val profiles = BrowserRiskProfiles(fake)
        var done: Boolean? = null

        profiles.clear(profiles.create(cleanup)!!) { done = it }

        assertEquals("done, and not cleared", false, done)
    }

    @Test
    fun `a browser clearing clears each profile made here and deletes those left, and says it all went`() = runTest {
        val fake = FakeWebViewProfiles()
        val left = BrowserRiskProfiles(fake).create(cleanup)!!
        fake.onDisk += left
        val profiles = BrowserRiskProfiles(fake)
        val made = profiles.create(cleanup)!!

        val clearing = profiles.clearAll(cleanup, timeoutMs = 1_000)

        assertTrue(clearing.inTime)
        assertTrue(clearing.settled())
        assertEquals(listOf(made), fake.cleared)
        assertFalse(left in fake.onDisk)
    }

    @Test
    fun `a browser clearing whose profile was not cleared stays owed`() = runTest {
        val fake = object : WebViewProfiles {
            override fun supported() = true
            override fun names() = emptyList<String>()
            override fun delete(name: String) = Unit
            override fun clear(name: String, done: () -> Unit): Unit = throw IllegalStateException("gone")
        }
        val profiles = BrowserRiskProfiles(fake)
        profiles.create(cleanup)

        val clearing = profiles.clearAll(cleanup, timeoutMs = 1_000)

        assertFalse("its cookies may still be there", clearing.inTime)
        assertFalse(clearing.settled())
        assertTrue("and the browser opens again once it settled", BrowserSession.usable)
    }
}

/** A context whose preference writes all fail to reach the disk: each `commit()` says false and keeps nothing. */
private class CommitFailingContext : ContextWrapper(null) {
    // Held here: android.jar's ContextWrapper hands nothing on in a plain JVM test.
    private val base = JvmContext()
    private val prefs = mutableMapOf<String, SharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FailingPreferences(base.getSharedPreferences(name, mode)) }

    private class FailingPreferences(private val base: SharedPreferences) : SharedPreferences by base {
        override fun edit(): SharedPreferences.Editor = FailingEditor()
    }

    private class FailingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = false
        override fun apply() = Unit
    }
}
