package org.ntust.app.tigerduck.util

/**
 * Han script for searches across Traditional and Simplified Chinese: NTUST names its courses and
 * services in Traditional characters only.
 */
object HanScript {
    private val simplifiedToTraditional by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Simplified-Traditional") }.getOrNull()
    }

    /** [text] with each Simplified character in its Traditional form; [text] as it is where ICU has no transliterator. */
    fun toTraditional(text: String): String = simplifiedToTraditional?.transliterate(text) ?: text

    /** Whether [text] holds a Han character. */
    fun containsHan(text: String): Boolean = text.any {
        val block = Character.UnicodeBlock.of(it) ?: return@any false
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
