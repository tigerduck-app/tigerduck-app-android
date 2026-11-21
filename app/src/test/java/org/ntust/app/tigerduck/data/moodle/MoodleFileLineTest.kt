package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A file row's second line, as iOS writes it (tigerduck-app#1005): its date, size and type, the
 * type the name's extension in capitals, tar's compound endings one short type.
 */
class MoodleFileLineTest {

    @Test
    fun `a file's type is its extension in capitals, with tar's compound endings shortened`() {
        val labels = mapOf(
            "slides.pdf" to "PDF",
            "SLIDES.PDF" to "PDF",
            "Week 3 lecture notes.pptx" to "PPTX",
            "data.7z" to "7Z",
            "Week 3 lecture.mp3" to "MP3",
            "lab4.tar" to "TAR",
            "lab4.tar.gz" to "TGZ",
            "lab4.TAR.GZ" to "TGZ",
            "lab4.TGZ" to "TGZ",
            "lab4.tar.bz2" to "TBZ2",
            "lab4.tbz2" to "TBZ2",
            "lab4.tar.xz" to "TXZ",
            "lab4.txz" to "TXZ",
        )
        for ((name, label) in labels) {
            assertEquals(name, label, MoodleFileLine.typeLabel(name))
        }
    }

    @Test
    fun `a name without an extension of one to five ASCII letters or digits, one of them a letter, has no type`() {
        for (name in listOf(
            "Makefile", "file.", "notes.markdown", ".bashrc", "slides.pdf?forcedownload=1", "report.ｐｄｆ", "",
            "Chapter 1.2", "lab4.001", "..pdf", "...pdf",
        )) {
            assertEquals(name, null, MoodleFileLine.typeLabel(name))
        }
    }

    @Test
    fun `the type follows whichever of the date and size the line has, joined the same way`() {
        fun line(name: String, size: String?, date: String?) = MoodleFileLine.of(date = date, size = size, fileName = name)

        assertEquals("10/04 · 646 kB · PDF", line("slides.pdf", "646 kB", "10/04"))
        assertEquals("646 kB · TGZ", line("lab4.tar.gz", "646 kB", null))
        assertEquals("10/04 · TGZ", line("lab4.tar.gz", null, "10/04"))
        assertEquals("TGZ", line("lab4.tar.gz", null, null))
        assertEquals("10/04 · 646 kB", line("Makefile", "646 kB", "10/04"))
        assertEquals("", line("Makefile", null, null))
    }
}
