package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.mail.store.HeldSendStore

class MailRoutesTest {
    @Test
    fun `folder names are percent-encoded in routes`() {
        assertEquals("schoolMail/message/INBOX/42", MailRoutes.message("INBOX", 42))
        assertEquals("schoolMail/message/Moodle%20%E8%AA%B2%E7%A8%8B/7", MailRoutes.message("Moodle 課程", 7))
        assertEquals("schoolMail/compose?mode=REPLY&folder=INBOX&uid=42", MailRoutes.compose(ComposeMode.REPLY, "INBOX", 42))
        assertEquals("schoolMail/compose?mode=NEW&folder=&uid=-1", MailRoutes.compose(ComposeMode.NEW))
    }

    @Test
    fun `a mailto link rides along encoded, so its own query survives the route's`() {
        assertEquals(
            "schoolMail/compose?mode=NEW&folder=&uid=-1&mailto=mailto%3Aa%40x.tw%3Fsubject%3DHi%26body%3Da%2520b",
            MailRoutes.compose(ComposeMode.NEW, mailto = "mailto:a@x.tw?subject=Hi&body=a%20b"),
        )
    }

    @Test
    fun `a mailto link too long to carry in the route rides along with its addresses alone`() {
        // The route's arguments are saved with the back stack; a sender-chosen link of hundreds of
        // kilobytes would overflow that when the app goes to the background.
        val route = MailRoutes.compose(ComposeMode.NEW, mailto = "mailto:a@x.tw?body=" + "x".repeat(300_000))
        assertEquals("schoolMail/compose?mode=NEW&folder=&uid=-1&mailto=mailto%3Aa%40x.tw", route)
    }

    @Test
    fun `a new message can name its recipient`() {
        assertEquals(
            "schoolMail/compose?mode=NEW&folder=&uid=-1&to=b11300001%40mail.ntust.edu.tw",
            MailRoutes.compose(ComposeMode.NEW, to = "b11300001@mail.ntust.edu.tw"),
        )
    }

    @Test
    fun `a mail kept for its send reopens as it was written, the reply it answers included`() {
        val held = HeldSendStore.Held(
            "m1", "b00015999@mail.ntust.edu.tw", 0, null,
            HeldSendStore.Fields("REPLY", "INBOX", 42, true, null, null, emptyList(), emptyList(), emptyList(), "", ""),
            emptyList(),
        )

        assertEquals("schoolMail/compose?mode=REPLY&folder=INBOX&uid=42&held=m1", MailRoutes.reopen(held))
        assertEquals(
            "a mode the app no longer knows opens a new mail",
            "schoolMail/compose?mode=NEW&folder=&uid=-1&held=m1",
            MailRoutes.reopen(held.copy(fields = held.fields.copy(mode = "OUTBOX", sourceFolder = "", sourceUid = -1))),
        )
    }
}
