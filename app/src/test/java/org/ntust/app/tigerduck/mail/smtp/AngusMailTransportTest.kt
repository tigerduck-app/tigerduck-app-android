package org.ntust.app.tigerduck.mail.smtp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailEndpoint
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.MailTransportSecurity
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.model.MailAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * How a send that breaks off is reported (tigerduck-app-android#377, as the iPhone's
 * tigerduck-app#662). Once the whole mail has gone to the server, a reply that never comes leaves
 * its delivery unknown, and sending again may deliver it twice; before that, nothing was sent.
 * Against a hand-made SMTP server that ends the session where each case needs it.
 */
class AngusMailTransportTest {

    private enum class Ending {
        /** Hangs up after RCPT TO, before the mail is sent. */
        BEFORE_DATA,
        /** Takes the whole mail and hangs up without the final reply. */
        AFTER_DATA,
        /** Takes the whole mail and refuses it. */
        REFUSED,
        /** Takes the whole mail and accepts it. */
        ACCEPTED,
    }

    private val server = ServerSocket(0)
    private var received = StringBuilder()

    @After
    fun tearDown() {
        server.close()
    }

    private fun serve(ending: Ending) = thread(isDaemon = true) {
        server.accept().use { socket -> converse(socket, ending) }
    }

    private fun converse(socket: Socket, ending: Ending) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val output = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
        fun say(line: String) {
            output.write("$line\r\n")
            output.flush()
        }
        say("220 test.example ESMTP")
        while (true) {
            val line = input.readLine() ?: return
            val verb = line.substringBefore(' ').uppercase()
            when {
                verb == "EHLO" -> {
                    say("250-test.example")
                    say("250 AUTH LOGIN PLAIN")
                }
                verb == "AUTH" && line.uppercase().startsWith("AUTH LOGIN") -> {
                    say("334 VXNlcm5hbWU6")
                    input.readLine()
                    say("334 UGFzc3dvcmQ6")
                    input.readLine()
                    say("235 2.7.0 accepted")
                }
                verb == "AUTH" -> say("235 2.7.0 accepted")
                verb == "MAIL" -> say("250 2.1.0 ok")
                verb == "RCPT" -> {
                    if (ending == Ending.BEFORE_DATA) return
                    say("250 2.1.5 ok")
                }
                verb == "DATA" -> {
                    say("354 go ahead")
                    while (true) {
                        val data = input.readLine() ?: return
                        if (data == ".") break
                        received.appendLine(data)
                    }
                    when (ending) {
                        Ending.AFTER_DATA -> return
                        Ending.REFUSED -> say("554 5.7.1 refused")
                        else -> say("250 2.0.0 queued")
                    }
                }
                verb == "QUIT" -> {
                    say("221 bye")
                    return
                }
                else -> say("250 ok")
            }
        }
    }

    private val transport = AngusMailTransport(
        MailServerConfig(
            domain = MailServerConfig.DOMAIN,
            imap = MailEndpoint("127.0.0.1", 1, MailTransportSecurity.NONE),
            smtp = MailEndpoint("127.0.0.1", server.localPort, MailTransportSecurity.NONE),
        ),
    )

    private val credentials = MailCredentials(studentId = "b00015999", password = "pw")

    private fun send(ending: Ending): MailError? {
        val serving = serve(ending)
        val mail = OutgoingMail(
            from = MailAddress(null, credentials.address),
            to = listOf(MailAddress(null, "someone@example.com")),
            cc = emptyList(), bcc = emptyList(), subject = "hi", body = "body",
        )
        return try {
            transport.send(credentials, MessageBuilder().build(mail))
            null
        } catch (e: MailError) {
            e
        } finally {
            serving.join(5_000)
        }
    }

    @Test
    fun `a reply that never comes after the whole mail went leaves the send unconfirmed`() {
        val error = send(Ending.AFTER_DATA)
        assertTrue("the server had the whole mail", "body" in received.toString())
        assertTrue("got $error", error is MailError.SendUnconfirmed)
    }

    @Test
    fun `a connection lost before the mail went is a failure, not unconfirmed`() {
        val error = send(Ending.BEFORE_DATA)
        assertTrue("got $error", error != null && error !is MailError.SendUnconfirmed)
    }

    @Test
    fun `a mail the server refuses is a failure, not unconfirmed`() {
        val error = send(Ending.REFUSED)
        assertTrue("got $error", error != null && error !is MailError.SendUnconfirmed)
    }

    @Test
    fun `a mail the server accepts is sent`() {
        val error = send(Ending.ACCEPTED)
        if (error != null) fail("got $error")
        assertEquals(true, "body" in received.toString())
    }
}
