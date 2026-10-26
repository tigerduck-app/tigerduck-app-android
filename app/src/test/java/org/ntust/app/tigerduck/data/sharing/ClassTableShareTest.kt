package org.ntust.app.tigerduck.data.sharing

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ClassTableShare.Selection
import org.ntust.app.tigerduck.shared.Course
import java.time.Instant

/** The share sheet's rules: the term picked first, the TAT switch, and the code each choice gives. */
class ClassTableShareTest {

    private val sharedAt = Instant.parse("2026-10-14T03:20:00Z")

    private fun course(courseNo: String, day: Int = 1, vararg periods: String) =
        Course.fromSchedule(courseNo = courseNo, courseName = courseNo, schedule = mapOf(day to periods.toList()))

    private val fall = ClassTableShare.Term("1151", listOf(course("CS1010001", 1, "3", "4"), course("MA1010001", 2, "6")))
    private val spring = ClassTableShare.Term("1142", listOf(course("EE1010002", 3, "2")))

    /** A course code of ten characters, which TAT's code cannot hold. */
    private val longCodes = ClassTableShare.Term("1141", listOf(course("3CS1010001", 4, "1")))

    private val share = ClassTableShare("b11300000", listOf(fall, spring, longCodes))

    @Test
    fun `the copied link is the code shown, with the scheme and host in lower case and TAT's padding encoded`() {
        val ours = share.code(share.initialState, sharedAt)!!
        assertEquals(
            "https://share.tigerduck.app/CLASSTABLE/" + ours.removePrefix(ClassTableShareCode.PREFIX),
            ClassTableShare.link(ours),
        )

        val tat = share.code(share.setTatCompatible(share.initialState, true), sharedAt)!!
        assertEquals("https://ntust-tat.web.app/S/TAT21151B11300000CS1010001.134-MA1010001.25", ClassTableShare.link(tat))

        // A student ID shorter than TAT's nine characters is padded with spaces, which a pasted
        // link would end at.
        val short = ClassTableShare("b000", listOf(fall))
        val padded = short.code(short.setTatCompatible(short.initialState, true), sharedAt)!!
        val link = ClassTableShare.link(padded)
        assertEquals("https://ntust-tat.web.app/S/TAT21151B000%20%20%20%20%20CS1010001.134-MA1010001.25", link)
        assertEquals(ClassTableShareCode.decode(padded), ClassTableShareCode.decode(link))
        assertEquals(ClassTableShareCode.decode(ours), ClassTableShareCode.decode(ClassTableShare.link(ours)))
    }

    @Test
    fun `the sheet opens on the latest term with the switch off`() {
        assertEquals(ClassTableShare.State(Selection.Semester("1151"), false), share.initialState)
        assertEquals("B11300000", share.studentId)
        assertEquals(
            listOf(Selection.AllSemesters, Selection.Semester("1151"), Selection.Semester("1142"), Selection.Semester("1141")),
            share.options,
        )
    }

    @Test
    fun `all semesters and a term TAT cannot carry turn the switch off and keep it off`() {
        val on = share.setTatCompatible(share.initialState, true)
        assertTrue(on.tatCompatible)

        assertFalse(share.select(on, Selection.AllSemesters).tatCompatible)
        assertFalse(share.select(on, Selection.Semester("1141")).tatCompatible)
        assertTrue(share.select(on, Selection.Semester("1142")).tatCompatible)

        val all = ClassTableShare.State(Selection.AllSemesters, false)
        assertFalse(share.offersTat(Selection.AllSemesters))
        assertFalse(share.setTatCompatible(all, true).tatCompatible)
        assertFalse(share.offersTat(Selection.Semester("1141")))
    }

    @Test
    fun `our code carries the picked term, or every term for all semesters`() {
        val one = ClassTableShareCode.decode(share.code(share.initialState, sharedAt)!!)
        assertEquals(ClassTableShareCode.Format.TIGER_DUCK, one.format)
        assertEquals("B11300000", one.studentId)
        assertEquals(sharedAt, one.sharedAt)
        assertEquals(listOf(ClassTableShareCode.Term("1151", listOf("CS1010001", "MA1010001"))), one.terms)

        val all = ClassTableShareCode.decode(share.code(ClassTableShare.State(Selection.AllSemesters, false), sharedAt)!!)
        assertEquals(listOf("1151", "1142", "1141"), all.terms.map { it.semester })
    }

    @Test
    fun `the switch writes TAT's code for the term, which reads back`() {
        val state = share.setTatCompatible(ClassTableShare.State(Selection.Semester("1142"), false), true)

        val text = share.code(state, sharedAt)!!

        assertTrue(text.startsWith("HTTPS://NTUST-TAT.WEB.APP/S/TAT2"))
        val code = ClassTableShareCode.decode(text)
        assertEquals(ClassTableShareCode.Format.TAT2, code.format)
        assertEquals(listOf(ClassTableShareCode.Term("1142", listOf("EE1010002"))), code.terms)
    }

    @Test
    fun `a share code names only an ID of letters and digits`() {
        assertTrue(ClassTableShare.canName("b11300000"))
        assertFalse(ClassTableShare.canName(null))
        assertFalse(ClassTableShare.canName(""))
        assertFalse(ClassTableShare.canName("B113-0000"))
    }

    @Test
    fun `a term shares its courses that are not hidden and have a code NTUST can look up`() {
        val courses = listOf(course("CS1010001"), course("MA1010001"), course("MANUAL-1"))

        val shared = ClassTableShare.shareable("1151", courses) { it == "MA1010001" }

        assertEquals(listOf("CS1010001"), shared.map { it.courseNo })
        assertTrue(ClassTableShare.shareable("115", courses) { false }.isEmpty())
    }

    @Test
    fun `no term means no code`() {
        val empty = ClassTableShare("B11300000", emptyList())
        assertEquals(Selection.AllSemesters, empty.initialState.selection)
        assertNull(empty.code(empty.initialState, sharedAt))
    }

    @Test
    fun `the QR code reads back as the share code, at error correction M with a four-module margin`() {
        val text = share.code(ClassTableShare.State(Selection.AllSemesters, false), sharedAt)!!
        val matrix = ClassTableShareQrCode.matrix(text)!!
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        // The finder pattern starts four light modules in from the corner.
        assertFalse(matrix[3, 3])
        assertTrue(matrix[4, 4])
        val side = matrix.width * 4
        val picture = scaled(pixels, matrix.width, 4)
        assertEquals(listOf(text), QrCodeImageReader.read(picture, side, side))
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, picture))))
        assertEquals("M", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun `a picture finds a light code on a dark background too, and nothing in a blank one`() {
        val text = share.code(share.initialState, sharedAt)!!
        val matrix = ClassTableShareQrCode.matrix(text)!!
        val side = matrix.width
        val inverted = IntArray(side * side) { i ->
            if (matrix[i % side, i / side]) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        }

        assertEquals(listOf(text), QrCodeImageReader.read(scaled(inverted, side, 4), side * 4, side * 4))
        assertTrue(QrCodeImageReader.read(IntArray(100 * 100) { 0xFFFFFFFF.toInt() }, 100, 100).isEmpty())
    }

    /** [pixels] of a [side]-wide square, each pixel made a [factor]-wide block, as a screenshot draws a module. */
    private fun scaled(pixels: IntArray, side: Int, factor: Int): IntArray {
        val out = side * factor
        return IntArray(out * out) { i -> pixels[(i / out / factor) * side + (i % out) / factor] }
    }

    @Test
    fun `a picture ZXing cannot read finds no code rather than throwing`() {
        // Fewer pixels than the size claims, as a broken decode might hand over.
        assertTrue(QrCodeImageReader.read(IntArray(10), 100, 100).isEmpty())
    }
}

