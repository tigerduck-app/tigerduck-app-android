package org.ntust.app.tigerduck.mail.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary

class MailNotificationPlannerTest {
    private fun m(uid: Long, from: MailAddress? = MailAddress("教務處", "o@x.tw"), subject: String = "s$uid") =
        MailSummary(uid, from, emptyList(), emptyList(), emptyList(), subject, null, null, MailFlags.NONE, 1, false, null, null, null)

    @Test
    fun `up to five mails notify one by one, oldest first, title is subject and body is sender`() {
        val plan = MailNotificationPlanner.plan(
            listOf(m(3), m(2, from = null, subject = "  "), m(4, from = MailAddress(null, "a@x.tw"), subject = "x\u202ey")),
            noSender = "（沒有寄件者）", noSubject = "（沒有主旨）", titleFormat = "Email:%1$@",
        ) as MailNotificationPlanner.Plan.Individual
        assertEquals(listOf(2L, 3L, 4L), plan.items.map { it.uid })
        assertEquals("（沒有寄件者）", plan.items[0].text)
        assertEquals("Email:（沒有主旨）", plan.items[0].title)
        assertEquals("教務處", plan.items[1].text)
        assertEquals("Email:s3", plan.items[1].title)
        assertEquals("a@x.tw", plan.items[2].text)
        assertEquals("Email:xy", plan.items[2].title)
    }

    @Test
    fun `more than five collapse into one summary`() {
        assertEquals(
            MailNotificationPlanner.Plan.Summary(6),
            MailNotificationPlanner.plan((1L..6L).map { m(it) }, "", "", "Email:%1$@"),
        )
        assertNull(MailNotificationPlanner.plan(emptyList(), "", "", "Email:%1$@"))
    }

    @Test
    fun `ids stay inside their own range`() {
        val id = MailNotificationPlanner.notificationId(3_976)
        assertEquals(true, id > MailNotificationPlanner.SUMMARY_ID && id < MailNotificationPlanner.SUMMARY_ID + 100_002)
    }
}
