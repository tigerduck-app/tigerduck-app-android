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
}
