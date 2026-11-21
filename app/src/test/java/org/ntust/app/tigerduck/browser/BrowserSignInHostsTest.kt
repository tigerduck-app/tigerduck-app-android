package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hosts the app signs in to, which a page opened at risk opens only the front page of (tigerduck-app#958). */
class BrowserSignInHostsTest {

    @Test
    fun `the hosts are the app's services, the password sites and the sign-in links, as the iPhone lists them`() {
        assertEquals(
            setOf(
                // The app's own services: Moodle, SSO, the portal, mail, the library and the school's.
                "moodle2.ntust.edu.tw", "ssoam2.ntust.edu.tw", "ssoam.ntust.edu.tw", "i.ntust.edu.tw", "mail.ntust.edu.tw",
                "api.lib.ntust.edu.tw", "cour01.ntust.edu.tw", "courseselection.ntust.edu.tw",
                // The sites whose forms get the stored password.
                "stuinfosys.ntust.edu.tw", "cardsystem.ntust.edu.tw", "studentpay.ntust.edu.tw",
                // The hosts of the sign-in links.
                "querycourse.ntust.edu.tw", "sa02.ntust.edu.tw", "sa.ntust.edu.tw",
            ),
            BrowserSignInHosts.hosts,
        )
    }

    @Test
    fun `a host matches lowercased and without its trailing dot, as the pin matcher's`() {
        assertTrue("moodle2.ntust.edu.tw" in BrowserSignInHosts)
        assertTrue("MOODLE2.NTUST.edu.TW" in BrowserSignInHosts)
        assertTrue("moodle2.ntust.edu.tw." in BrowserSignInHosts)
        assertTrue("SSOAM2.ntust.edu.tw." in BrowserSignInHosts)
    }

    @Test
    fun `any other host is not one, its subdomains and look-alikes included`() {
        listOf(
            "www.ntust.edu.tw",
            "suggestionbox.ntust.edu.tw",
            "www.lib.ntust.edu.tw",
            "ntust.edu.tw",
            "evil.moodle2.ntust.edu.tw",
            "moodle2.ntust.edu.tw.evil.example",
            "moodle2-ntust.edu.tw",
            "",
        ).forEach { assertFalse(it, it in BrowserSignInHosts) }
    }
}
