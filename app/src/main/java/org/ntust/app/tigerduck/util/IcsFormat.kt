package org.ntust.app.tigerduck.util

/** RFC 5545 text for an ICS file the app writes. As iOS `ICSFormat`. */
object IcsFormat {
    /**
     * RFC 5545 §3.3.11: TEXT escapes a backslash, a semicolon and a comma, writes a line break as
     * `\n`, and cannot hold the other control characters.
     */
    fun escaped(text: String): String = buildString {
        for (char in text.replace("\r\n", "\n")) {
            when {
                char == '\\' || char == ';' || char == ',' -> append('\\').append(char)
                char == '\n' || char == '\r' -> append("\\n")
                char == '\t' -> append(char)
                char < ' ' || char == '\u007F' -> Unit
                else -> append(char)
            }
        }
    }

    /**
     * RFC 5545 §3.1: a content line past 75 octets goes on after a CRLF and a space, which counts
     * toward the next line's 75, and a fold must not split a UTF-8 sequence.
     */
    fun folded(line: String): String = buildString {
        var octets = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val width = utf8Width(codePoint)
            if (octets + width > LINE_OCTETS) {
                append("\r\n ")
                octets = 1
            }
            appendCodePoint(codePoint)
            octets += width
            index += Character.charCount(codePoint)
        }
    }

    private fun utf8Width(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private const val LINE_OCTETS = 75
}
