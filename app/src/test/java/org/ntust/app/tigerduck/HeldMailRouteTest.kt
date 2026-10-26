package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import java.io.File

/** Which composer a fresh start opens for a mail the system ended the app over mid-send. */
class HeldMailRouteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val me = "b00015999@mail.ntust.edu.tw"
    private val root by lazy { File(tmp.root, "held") }

    private val fields = HeldSendStore.Fields(
        "REPLY", "INBOX", 42, true, "<a@x>", null, listOf("amy@x.tw"), emptyList(), emptyList(), "Re: Lab", "Thanks",
    )

    @Test
    fun `a mail kept before the restart opens its composer again, for the student signed in`() {
        HeldSendStore(root).save("m1", me, fields, emptyList()) { true }

        assertEquals("schoolMail/compose?mode=REPLY&folder=INBOX&uid=42&held=m1", heldMailRoute(HeldSendStore(root), me))
    }

    @Test
    fun `nothing opens with no one signed in to the mail, or for a mail a composer has open`() {
        val store = HeldSendStore(root)
        store.save("m1", me, fields, emptyList()) { true }

        assertNull(heldMailRoute(HeldSendStore(root), null))
        assertNull("this process's composer holds it", heldMailRoute(store, me))
    }
}
