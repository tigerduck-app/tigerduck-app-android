package org.ntust.app.tigerduck.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageManagerTest {

    private fun uiTag(language: String, device: String = "en-US") =
        AppLanguageManager.uiLanguageTag(language, Locale.forLanguageTag(device))

    // --- "Follow system": the phone's own locale, whole ------------------

    @Test
    fun `follow system on a Traditional Chinese phone keeps the script`() {
        // The watch used to be sent "zh", which Android resolves to
        // values-zh-rCN — Simplified on a Taiwan phone.
        assertEquals("zh-Hant-TW", uiTag(AppLanguageManager.SYSTEM, device = "zh-Hant-TW"))
    }

    @Test
    fun `follow system on a legacy zh-TW locale keeps the region`() {
        assertEquals("zh-TW", uiTag(AppLanguageManager.SYSTEM, device = "zh-TW"))
    }

    @Test
    fun `follow system on a Hong Kong phone is not flattened to zh`() {
        assertEquals("zh-Hant-HK", uiTag(AppLanguageManager.SYSTEM, device = "zh-Hant-HK"))
    }

    @Test
    fun `follow system on a Japanese phone is Japanese, not English`() {
        // resolvedSystemLanguage() answers "en" here, which is right for the
        // course API and was wrong for the watch UI.
        assertEquals("ja-JP", uiTag(AppLanguageManager.SYSTEM, device = "ja-JP"))
    }

    // --- an explicit pick wins over the device ----------------------------

    @Test
    fun `an explicit pick is passed on over the device locale`() {
        assertEquals("zh-TW", uiTag("zh-TW", device = "en-US"))
        assertEquals("zh-CN", uiTag("zh-CN", device = "zh-Hant-TW"))
        assertEquals("yue-HK", uiTag("yue-HK", device = "en-US"))
    }

    @Test
    fun `script-only Chinese picks get the same region the phone applies`() {
        assertEquals("zh-Hant-TW", uiTag("zh-Hant"))
        assertEquals("zh-Hans-CN", uiTag("zh-Hans"))
    }
}
