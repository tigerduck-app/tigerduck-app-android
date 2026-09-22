package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideUrlTest {

    @Test
    fun `builds the android help url with every parameter`() {
        val url = guideUrl("android", isDark = true, languageTag = "zh-Hant", background = "#121212", foreground = "#e6e6e6")
        assertEquals(
            "https://tigerduck.app/help/receive-mail/android" +
                "?embed=1&theme=dark&lang=zh-Hant&bg=%23121212&fg=%23e6e6e6",
            url,
        )
    }

    @Test
    fun `light theme says so`() {
        val url = guideUrl("android", isDark = false, languageTag = "en", background = "#ffffff", foreground = "#1b1b1b")
        assertTrue(url, url.contains("theme=light"))
    }

    @Test
    fun `the hash in a colour is encoded, not sent raw`() {
        val url = guideUrl("android", isDark = true, languageTag = "en", background = "#121212", foreground = "#e6e6e6")
        assertTrue(url, url.contains("bg=%23121212"))
        assertTrue(url, !url.contains("bg=#"))
    }
}
