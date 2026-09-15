package org.ntust.app.tigerduck.mail.sync

/** Arms / disarms every background new-mail check (exact alarm + WorkManager). */
interface MailBackgroundScheduler {
    fun schedule()
    fun cancel()
}
