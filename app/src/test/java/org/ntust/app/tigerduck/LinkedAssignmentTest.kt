package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When MainActivity opens the page of the assignment a `tigerduck://assignment/<id>` link names. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkedAssignmentTest {

    private fun linkIntent(link: String = "tigerduck://assignment/900002") =
        Intent(Intent.ACTION_VIEW, Uri.parse(link))

    @Test
    fun `a fresh delivery opens the link's assignment, once`() {
        val intent = linkIntent()

        assertEquals("900002", takeLinkedAssignmentId(intent, restored = false))
        assertNull("the link is taken off the intent", intent.data)
        assertNull("so the same intent opens nothing again", takeLinkedAssignmentId(intent, restored = false))
    }

    @Test
    fun `an Activity rebuilt from saved state opens nothing, and drops the link`() {
        val intent = linkIntent()

        assertNull(takeLinkedAssignmentId(intent, restored = true))
        assertNull(intent.data)
    }

    @Test
    fun `a relaunch from Recents opens nothing`() {
        val intent = linkIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertNull(takeLinkedAssignmentId(intent, restored = false))
        assertNull(intent.data)
    }

    @Test
    fun `another link is left for the route to open`() {
        val intent = linkIntent("tigerduck://classtable")

        assertNull(takeLinkedAssignmentId(intent, restored = false))
        assertEquals("tigerduck://classtable", intent.dataString)
    }

    @Test
    fun `a malformed assignment link opens nothing`() {
        assertNull(takeLinkedAssignmentId(linkIntent("tigerduck://assignment/abc"), restored = false))
    }

    @Test
    fun `the link opens the assignment's page, once per delivery`() {
        val intent = linkIntent()

        assertEquals(Screen.Assignment.route("900002"), linkedStartRoute(intent, restored = false))
        assertNull("the link is taken off the intent", intent.data)
        assertNull("so the same intent opens nothing again", linkedStartRoute(intent, restored = false))
        assertNull(linkedStartRoute(linkIntent(), restored = true))
    }

    @Test
    fun `another link still opens its own screen`() {
        assertEquals(Screen.ClassTable.route, linkedStartRoute(linkIntent("tigerduck://classtable"), restored = false))
        assertEquals(Screen.AnnouncementDetail.route(42), linkedStartRoute(linkIntent("tigerduck://announcement/42"), restored = false))
    }
}
