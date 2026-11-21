package org.ntust.app.tigerduck.data.moodle

import java.util.Locale

/**
 * The second line of a file's row: its date, size and type, the parts it has joined by " · ", for a
 * Moodle file and an archive's entry alike. As iOS's `MoodleFileRow.subline` (tigerduck-app#1005).
 */
object MoodleFileLine {

    fun of(date: String?, size: String?, fileName: String): String =
        listOfNotNull(date, size, typeLabel(fileName)).joinToString(" · ")

    private val TAR_LABELS = listOf(".tar.gz" to "TGZ", ".tar.bz2" to "TBZ2", ".tar.xz" to "TXZ")
    private val TYPE = Regex("[A-Za-z0-9]{1,5}")

    /**
     * The file's type: its name's extension in capitals, tar's compound endings as one short type
     * (`.tar.gz` is TGZ, as `.tgz` is). None for an extension that is not 1 to 5 ASCII letters or
     * digits with a letter among them, and none without one: a dotfile, a trailing dot, as iOS reads
     * one with Foundation's `pathExtension`.
     */
    fun typeLabel(fileName: String): String? {
        val lowered = fileName.lowercase(Locale.ROOT)
        TAR_LABELS.firstOrNull { (ending, _) -> lowered.endsWith(ending) }?.let { return it.second }
        val name = fileName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        // One to three leading dots start a hidden file's name, not an extension, as `pathExtension` reads them.
        val stem = name.take(dot.coerceAtLeast(0))
        if (dot < 0 || (stem.length < 3 && stem.all { it == '.' })) return null
        val extension = name.substring(dot + 1)
        if (!TYPE.matches(extension) || extension.none { it.isLetter() }) return null
        return extension.uppercase(Locale.ROOT)
    }
}
