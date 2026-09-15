package org.ntust.app.tigerduck.mail

import jakarta.mail.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AngusGreenMailSmokeTest {
    @get:Rule val server = MailTestServer()

    @Test
    fun `angus signs in to the test server and sees a delivered mail`() {
        server.deliver(subject = "hello")
        server.rawStore().use { store ->
            val inbox = store.getFolder("INBOX")
            assertTrue(inbox.exists())
            inbox.open(Folder.READ_ONLY)
            assertEquals(1, inbox.messageCount)
            assertEquals("hello", inbox.getMessage(1).subject)
            inbox.close(false)
        }
    }

    @Test
    fun `credentials map to the login name and the lowercase address`() {
        val c = MailCredentials(studentId = " B11234567 ", password = "x")
        assertEquals("B11234567", c.loginName)
        assertEquals("b11234567@mail.ntust.edu.tw", c.address)
        assertTrue("password never printed", "x" !in c.toString())
    }
}
