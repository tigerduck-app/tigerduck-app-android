package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTextTest {

    private val text = WhatsNewText(en = "Reset Defaults", zhHant = "恢復預設")

    @Test
    fun `every Chinese-family language reads the Traditional copy`() {
        for (tag in listOf("zh-Hant-TW", "zh-TW", "zh-Hans-CN", "zh-CN", "yue-HK", "nan-TW", "hak")) {
            assertEquals(tag, WhatsNewLanguage.ZhHant, WhatsNewLanguage.of(tag))
            assertEquals(tag, "恢復預設", text.resolve(WhatsNewLanguage.of(tag)))
        }
    }

    @Test
    fun `every other language reads the English copy`() {
        for (tag in listOf("en-US", "en-GB", "en", "ja-JP", "ko-KR", "fr-FR", "und", "")) {
            assertEquals(tag, WhatsNewLanguage.En, WhatsNewLanguage.of(tag))
            assertEquals(tag, "Reset Defaults", text.resolve(WhatsNewLanguage.of(tag)))
        }
    }

    @Test
    fun `the page position reads in the sheet's language, with the heading when there is one`() {
        assertEquals(
            "Page 2 of 4: Reset your bottom bar?",
            pagePositionDescription(1, 4, "Reset your bottom bar?", WhatsNewLanguage.En),
        )
        assertEquals(
            "第 2 頁，共 4 頁：要恢復預設的底部功能列嗎？",
            pagePositionDescription(1, 4, "要恢復預設的底部功能列嗎？", WhatsNewLanguage.ZhHant),
        )
        assertEquals("Page 3 of 3", pagePositionDescription(2, 3, null, WhatsNewLanguage.En))
    }
}
