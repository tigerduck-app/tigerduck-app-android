package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Two Gradle test runs on one machine (two worktrees, or the play and fdroid unit tests at
 * once) each start their own [MailTestServer]. On fixed ports the second one fails with
 * "Could not start mail server"; this starts two side by side the way those runs do.
 */
class MailTestServerTest {
    @Test
    fun `two servers run side by side, each on its own ports`() {
        val first = MailTestServer()
        val second = MailTestServer()
        first.around {
            second.around {
                assertNotEquals(first.config.imap.port, second.config.imap.port)
                assertNotEquals(first.config.smtp.port, second.config.smtp.port)
                // Each config points at a server that answers, not just at a number.
                listOf(first, second).forEach { server -> server.rawStore().use { it.isConnected } }
            }
        }
    }

    private fun MailTestServer.around(body: () -> Unit) =
        apply(object : Statement() {
            override fun evaluate() = body()
        }, Description.EMPTY).evaluate()
}
