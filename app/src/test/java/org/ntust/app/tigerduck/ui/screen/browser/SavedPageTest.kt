package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page a recreated browser puts back, kept whole until the browser has started it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SavedPageTest {

    private fun webState() = Bundle().apply { putString("url", "https://moodle2.ntust.edu.tw/my/") }

    @Test
    fun `a saved page reads back as it was written`() {
        val out = Bundle()
        SavedPage(webState(), session = 3, signInTarget = "https://moodle2.ntust.edu.tw/user/edit.php").writeTo(out)

        val read = SavedPage.readFrom(out)!!
        assertEquals(3, read.session)
        assertEquals("https://moodle2.ntust.edu.tw/my/", read.webState.getString("url"))
        assertEquals("https://moodle2.ntust.edu.tw/user/edit.php", read.signInTarget)
    }

    @Test
    fun `a page saved again before it was started is written again whole`() {
        // Recreated, then stopped before its first frame: the page being put back is saved once more.
        val pending = SavedPage.readFrom(Bundle().also { SavedPage(webState(), session = 5, signInTarget = null).writeTo(it) })!!
        val out = Bundle()
        pending.writeTo(out)

        assertEquals(5, SavedPage.readFrom(out)!!.session)
    }

    @Test
    fun `nothing is read from a state without a page`() {
        assertNull(SavedPage.readFrom(null))
        assertNull(SavedPage.readFrom(Bundle()))
        assertNull(SavedPage.readFrom(Bundle().apply { putInt("session", 2) }))
    }
}
