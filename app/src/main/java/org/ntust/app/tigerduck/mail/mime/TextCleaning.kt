package org.ntust.app.tigerduck.mail.mime

/** Spec appendix A.3: text that came from a mail and is shown outside the web view. */
object TextCleaning {
    private val BIDI = Regex("[\u202a-\u202e\u2066-\u2069\u200e\u200f\u061c]")
    private val CONTROLS = Regex("[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]")
    private val WHITESPACE = Regex("\\s+")

    fun stripBidi(s: String): String = BIDI.replace(s, "")

    /** Names, subjects, file names, notification text: bidi and control characters out, whitespace collapsed. */
    fun clean(s: String?): String =
        WHITESPACE.replace(CONTROLS.replace(stripBidi(s.orEmpty()), ""), " ").trim()
}
