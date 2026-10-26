package org.ntust.app.tigerduck.files

import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.Date
import kotlin.random.Random

/** The archive reader behind the file viewer, on archives each test makes itself. */
class ArchivesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val modified = Date(1_700_000_000_000L)

    private fun zip(
        name: String = "fake.zip",
        encoding: String = "UTF-8",
        flagged: Boolean = true,
        build: ZipArchiveOutputStream.() -> Unit,
    ): File = temp.newFile(name).also { file ->
        ZipArchiveOutputStream(file).use { out ->
            out.setEncoding(encoding)
            out.setUseLanguageEncodingFlag(flagged)
            out.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
            out.build()
        }
    }

    private fun ZipArchiveOutputStream.file(path: String, bytes: ByteArray, edit: ZipArchiveEntry.() -> Unit = {}) {
        putArchiveEntry(ZipArchiveEntry(path).apply { time = modified.time; edit() })
        write(bytes)
        closeArchiveEntry()
    }

    private fun ZipArchiveOutputStream.folder(path: String) {
        putArchiveEntry(ZipArchiveEntry(path))
        closeArchiveEntry()
    }

    private fun tar(out: OutputStream, build: TarArchiveOutputStream.() -> Unit) {
        TarArchiveOutputStream(out, "UTF-8").use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.build()
        }
    }

    private fun TarArchiveOutputStream.file(path: String, bytes: ByteArray) {
        putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong(); modTime = modified })
        write(bytes)
        closeArchiveEntry()
    }

    private fun source(file: File) = ArchiveSource.of(file)

    private fun listing(file: File, limits: ArchiveLimits = ArchiveLimits()) = Archives.list(source(file), limits)

    private fun extract(file: File, item: ArchiveItem, limits: ArchiveLimits = ArchiveLimits()): File =
        Archives.extract(source(file), item, temp.newFolder(), limits)

    private fun problem(block: () -> Unit): ArchiveProblem? = try {
        block()
        null
    } catch (e: ArchiveProblemException) {
        e.problem
    }

    @Test
    fun `a zip lists its files and folders, with sizes and dates`() {
        val file = zip {
            folder("notes/")
            file("notes/week 1.txt", "hello".toByteArray())
            file("readme.md", "# fake".toByteArray())
        }

        val listing = listing(file)

        assertEquals(ArchiveFormat.ZIP, listing.format)
        assertFalse(listing.truncated)
        assertEquals(listOf("notes", "notes/week 1.txt", "readme.md"), listing.items.map { it.path })
        assertEquals(listOf(true, false, false), listing.items.map { it.isDirectory })
        assertEquals(5L, listing.items[1].size)
        assertEquals(modified.time / 2_000, listing.items[1].modified!! / 2_000)
        assertNull(listing.items[1].problem)
    }

    @Test
    fun `a zip's names are utf-8 when it says so or they read as utf-8, cp950 otherwise`() {
        val path = "課程/第一週 講義.pdf"
        val flagged = zip("flagged.zip") { file(path, byteArrayOf(1)) }
        val unflagged = zip("unflagged.zip", flagged = false) { file(path, byteArrayOf(1)) }
        // As Windows in Taiwan writes them: CP950, with no flag.
        val windows = zip("windows.zip", encoding = "MS950", flagged = false) { file(path, byteArrayOf(1)) }

        for (file in listOf(flagged, unflagged, windows)) {
            assertEquals(file.name, path, listing(file).items.single().path)
        }
    }

    @Test
    fun `a name that is neither utf-8 nor cp950 reads as the zip format's own code page`() {
        // 0x80 starts no UTF-8 character and no CP950 one; in CP437 it is Ç.
        assertEquals("Ça.txt", ArchiveNames.zipName(byteArrayOf(0x80.toByte()) + "a.txt".toByteArray(), utf8 = false))
        assertEquals("課", ArchiveNames.zipName("課".toByteArray(charset("MS950")), utf8 = false))
        assertEquals("課", ArchiveNames.zipName("課".toByteArray(), utf8 = true))
    }

    @Test
    fun `a file taken out is written under a name of the app's, whatever path it claims`() {
        val file = zip { file("../../outside/../../evil.TXT", "fake".toByteArray()) }
        val item = listing(file).items.single()

        val into = temp.newFolder("into")
        val taken = Archives.extract(source(file), item, into)

        assertEquals(into.canonicalFile, taken.canonicalFile.parentFile)
        assertTrue(taken.name, taken.name.endsWith(".txt"))
        assertFalse(taken.name, taken.name.contains("evil"))
        assertEquals("fake", taken.readText())
        assertEquals(listOf(taken.name), into.list()!!.toList())
    }

    @Test
    fun `a tar, and one compressed with gzip, bzip2 or xz, list and open the same`() {
        val compressions: List<Pair<String, (OutputStream) -> OutputStream>> = listOf(
            "fake.tar" to { it },
            "fake.tar.gz" to { GzipCompressorOutputStream(it) },
            "fake.tgz" to { GzipCompressorOutputStream(it) },
            "fake.tar.bz2" to { BZip2CompressorOutputStream(it) },
            "fake.tar.xz" to { XZCompressorOutputStream(it) },
        )
        for ((name, compress) in compressions) {
            val file = temp.newFile(name)
            compress(file.outputStream()).use { out ->
                tar(out) {
                    file("src/Main.kt", "fun main() {}".toByteArray())
                    file("src/課程.txt", "中文".toByteArray())
                }
            }

            val listing = listing(file)

            assertEquals(name, listOf("src/Main.kt", "src/課程.txt"), listing.items.map { it.path })
            assertEquals(name, 13L, listing.items[0].size)
            assertEquals(name, "中文", extract(file, listing.items[1]).readText())
        }
    }

    @Test
    fun `an old V7 tar, with no ustar mark, reads by its header's checksum, plain or gzipped`() {
        val bytes = v7Tar("notes.txt" to "fake".toByteArray(), "src/Main.kt" to "fun main() {}".toByteArray())
        val plain = temp.newFile("old.tar").apply { writeBytes(bytes) }
        val gzipped = temp.newFile("old.tar.gz").also { file -> GzipCompressorOutputStream(file.outputStream()).use { it.write(bytes) } }

        for (file in listOf(plain, gzipped)) {
            val listing = listing(file)
            assertEquals(file.name, listOf("notes.txt", "src/Main.kt"), listing.items.map { it.path })
            assertEquals(file.name, "fake", extract(file, listing.items[0]).readText())
        }
    }

    @Test
    fun `bytes that only look like a header aren't taken for a tar`() {
        assertNull(Archives.format(Random(5).nextBytes(512)))
        assertNull(Archives.format(ByteArray(512)))
        assertNull(Archives.format("plain text, not an archive".padEnd(512).toByteArray()))
    }

    @Test
    fun `a gz of one file lists that file, under the name it was packed with`() {
        val file = temp.newFile("fake.gz")
        val parameters = GzipParameters().apply { fileName = "notes.txt"; modificationTime = modified.time }
        GzipCompressorOutputStream(file.outputStream(), parameters).use { it.write("just one".toByteArray()) }

        val listing = listing(file)

        assertEquals(ArchiveFormat.GZIP, listing.format)
        assertEquals(listOf("notes.txt"), listing.items.map { it.path })
        assertEquals("just one", extract(file, listing.items.single()).readText())
    }

    @Test
    fun `a 7z packed with lzma2 lists and opens`() {
        val file = temp.newFile("fake.7z")
        SevenZOutputFile(file).use { out ->
            out.setContentCompression(SevenZMethod.LZMA2)
            for ((path, text) in listOf("a/one.txt" to "first", "a/two.txt" to "second")) {
                val entry = out.createArchiveEntry(File(path), path).apply { lastModifiedDate = modified }
                out.putArchiveEntry(entry)
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }

        val listing = listing(file)

        assertEquals(ArchiveFormat.SEVEN_Z, listing.format)
        assertEquals(listOf("a/one.txt", "a/two.txt"), listing.items.map { it.path })
        assertEquals(6L, listing.items[1].size)
        // The second of a solid block: the first is read past to reach it.
        assertEquals("second", extract(file, listing.items[1]).readText())
    }

    @Test
    fun `a bomb stops at the ratio limit, counted on what is read rather than what it claims`() {
        val file = zip { file("zeros.bin", ByteArray(8 shl 20)) }
        val limits = ArchiveLimits(ratioFreeBytes = 1L shl 20, maxRatio = 100)

        assertEquals(ArchiveProblem.TOO_LARGE, problem { extract(file, listing(file).items.single(), limits) })
    }

    @Test
    fun `a file larger than the limit isn't taken out, however well it packs`() {
        val file = zip { file("noise.bin", Random(1).nextBytes(2 shl 20)) }
        val limits = ArchiveLimits(maxEntryBytes = 1L shl 20)

        assertEquals(ArchiveProblem.TOO_LARGE, problem { extract(file, listing(file).items.single(), limits) })
    }

    @Test
    fun `a compressed tar read past its limit stops`() {
        val file = temp.newFile("fake.tar.gz")
        GzipCompressorOutputStream(file.outputStream()).use { out ->
            tar(out) {
                file("zeros.bin", ByteArray(4 shl 20))
                file("last.txt", "end".toByteArray())
            }
        }

        assertEquals(ArchiveProblem.TOO_LARGE, problem { listing(file, ArchiveLimits(maxScanBytes = 1L shl 20)) })
    }

    @Test
    fun `reading stops when the time is up`() {
        val file = zip { repeat(20) { file("file $it.txt", byteArrayOf(1)) } }
        var now = 0L
        val limits = ArchiveLimits(timeLimitMillis = 5_000, now = { now.also { now += 1_000 } })

        assertEquals(ArchiveProblem.TOO_LARGE, problem { listing(file, limits) })
    }

    @Test
    fun `an encrypted file is listed, marked as needing a password`() {
        val file = zip {
            file("secret.txt", "not really encrypted".toByteArray())
            file("open.txt", "fake".toByteArray())
        }.markedEncrypted("secret.txt")

        val items = listing(file).items

        assertEquals(ArchiveProblem.PASSWORD, items[0].problem)
        assertNull(items[1].problem)
        assertEquals(ArchiveProblem.PASSWORD, problem { extract(file, items[0]) })
    }

    @Test
    fun `a link is listed but never taken out`() {
        val zipped = zip { file("link", "/etc/hosts".toByteArray()) { unixMode = 0b1010_000_111_111_111 } }
        val tarred = temp.newFile("fake.tar").also { file ->
            tar(file.outputStream()) {
                putArchiveEntry(TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = "/etc/hosts" })
                closeArchiveEntry()
            }
        }

        for (file in listOf(zipped, tarred)) {
            val item = listing(file).items.single()
            assertEquals(file.name, ArchiveProblem.LINK, item.problem)
            assertEquals(file.name, ArchiveProblem.LINK, problem { extract(file, item) })
        }
    }

    @Test
    fun `the list stops at its limit and says so`() {
        val file = zip { repeat(12) { file("file $it.txt", byteArrayOf(1)) } }

        val listing = listing(file, ArchiveLimits(maxItems = 10))

        assertEquals(10, listing.items.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `a zip whose directory holds more entries than the limit isn't read`() {
        val file = zip { repeat(6) { file("file $it.txt", byteArrayOf(1)) } }

        assertEquals(ArchiveProblem.TOO_MANY, problem { listing(file, ArchiveLimits(maxZipEntries = 5)) })
    }

    @Test
    fun `a damaged archive, or one of a kind the app doesn't read, says it can't be shown`() {
        val whole = zip { file("a.txt", Random(2).nextBytes(64 shl 10)) }
        val cut = temp.newFile("cut.zip").apply { writeBytes(whole.readBytes().copyOf(1_000)) }
        val rar = temp.newFile("fake.rar").apply { writeBytes("Rar!".toByteArray() + ByteArray(64)) }

        assertEquals(ArchiveProblem.BROKEN, problem { listing(cut) })
        assertEquals(ArchiveProblem.UNSUPPORTED, problem { listing(rar) })
    }

    @Test
    fun `the kind of archive is told by its first bytes, not its name`() {
        fun format(vararg bytes: Int) = Archives.format(ByteArray(512).also { head -> bytes.forEachIndexed { i, b -> head[i] = b.toByte() } })
        assertEquals(ArchiveFormat.ZIP, format(0x50, 0x4B, 0x03, 0x04))
        assertEquals(ArchiveFormat.ZIP, format(0x50, 0x4B, 0x05, 0x06))
        assertEquals(ArchiveFormat.SEVEN_Z, format(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))
        assertEquals(ArchiveFormat.GZIP, format(0x1F, 0x8B))
        // "BZh", a block size from 1 to 9, then a block's magic, or the end's in an empty one.
        assertEquals(ArchiveFormat.BZIP2, format(0x42, 0x5A, 0x68, 0x39, 0x31, 0x41, 0x59, 0x26, 0x53, 0x59))
        assertEquals(ArchiveFormat.BZIP2, format(0x42, 0x5A, 0x68, 0x31, 0x17, 0x72, 0x45, 0x38, 0x50, 0x90))
        assertNull(format(0x42, 0x5A, 0x68))
        assertEquals(ArchiveFormat.XZ, format(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00))
        val tar = ByteArray(512).also { "ustar".toByteArray().copyInto(it, 257) }
        assertEquals(ArchiveFormat.TAR, Archives.format(tar))
        assertNull(format(0x52, 0x61, 0x72, 0x21))
    }

    @Test
    fun `a tar whose first file's name starts with BZh is a tar, not a bzip2`() {
        val file = temp.newFile("notes.tar").also { f -> f.outputStream().use { tar(it) { file("BZh notes.txt", "hi".toByteArray()) } } }

        val listing = listing(file)
        assertEquals(ArchiveFormat.TAR, listing.format)
        assertEquals(listOf("BZh notes.txt"), listing.items.map { it.path })
    }

    @Test
    fun `a zip named as something else still reads as a zip`() {
        val file = zip("misnamed.tar") { file("a.txt", "fake".toByteArray()) }

        assertEquals(ArchiveFormat.ZIP, listing(file).format)
    }

    @Test
    fun `an archive that changed since it was listed doesn't hand over another file`() {
        val file = zip("changing.zip") { file("week 1.pdf", "first".toByteArray()) }
        val item = listing(file).items.single()
        file.delete()
        zip("changing.zip") { file("other.pdf", "second".toByteArray()) }

        assertEquals(ArchiveProblem.BROKEN, problem { extract(file, item) })
    }

    @Test
    fun `two takings of one file at once each finish whole, neither spoiling the other's`() {
        val bytes = Random(4).nextBytes(1 shl 20)
        val file = zip { file("data.bin", bytes) }
        val item = listing(file).items.single()
        val into = temp.newFolder("shared")
        // The first stops partway, until the second has finished.
        val partway = java.util.concurrent.CountDownLatch(1)
        val secondDone = java.util.concurrent.CountDownLatch(1)
        var checks = 0
        val slow = ArchiveLimits(now = {
            if (++checks == 3) {
                partway.countDown()
                secondDone.await()
            }
            0L
        })
        var first: Result<File>? = null
        val thread = Thread { first = runCatching { Archives.extract(source(file), item, into, slow) } }
        thread.start()
        partway.await()

        val second = Archives.extract(source(file), item, into)
        assertArrayEquals(bytes, second.readBytes())
        secondDone.countDown()
        thread.join()

        assertArrayEquals(bytes, first!!.getOrThrow().readBytes())
        assertEquals(listOf(second.name), into.list()!!.toList())
    }

    @Test
    fun `a tar of endless extended headers says it's broken, without bringing the app down`() {
        val file = temp.newFile("headers.tar")
        file.outputStream().buffered().use { out ->
            repeat(5_000) {
                out.write(tarHeader("PaxHeader", 6, 'x'))
                out.write("6 a=b\n".toByteArray() + ByteArray(506))
            }
            out.write(tarHeader("last.txt", 0, '0'))
            out.write(ByteArray(1024))
        }
        var result: Any? = "not reached"
        // Headers allowed without end, so it's the depth that stops it, not their size, which a
        // JVM with smaller frames reaches first; on a small stack, as a worker thread's may be.
        val limits = ArchiveLimits(maxHeaderBytes = 1L shl 40)
        val thread = Thread(null, { result = runCatching { problem { listing(file, limits) } }.getOrElse { it::class.simpleName } }, "small", 256L * 1024)
        thread.start()
        thread.join()

        assertEquals(ArchiveProblem.BROKEN, result)
    }

    @Test
    fun `a zip whose directory holds more entries than its end record says isn't read past the limit`() {
        val file = zip { repeat(6) { file("file $it.txt", byteArrayOf(1)) } }.declaringEntries(0)

        assertEquals(ArchiveProblem.TOO_MANY, problem { listing(file, ArchiveLimits(maxZipEntries = 5)) })
    }

    @Test
    fun `a zip with data before it is counted where the reader finds its directory`() {
        val file = zip { repeat(6) { file("file $it.txt", byteArrayOf(1)) } }.declaringEntries(0).prefixedWith(1_000)

        assertEquals(6, listing(file).items.size)
        assertEquals(ArchiveProblem.TOO_MANY, problem { listing(file, ArchiveLimits(maxZipEntries = 5)) })
    }

    @Test
    fun `a zip with a Zip64 locator is counted where its Zip64 record says, as the reader goes`() {
        val file = zip { repeat(6) { file("file $it.txt", byteArrayOf(1)) } }.withZip64Record()

        assertEquals(6, listing(file).items.size)
        assertEquals(ArchiveProblem.TOO_MANY, problem { listing(file, ArchiveLimits(maxZipEntries = 5)) })
    }

    @Test
    fun `an xz file in a zip is one the app doesn't read, its decoder's memory being unbounded there`() {
        val file = zip { file("data.bin", "fake".toByteArray()) }.packedWith("data.bin", 95)

        assertEquals(ArchiveProblem.UNSUPPORTED, listing(file).items.single().problem)
    }

    @Test
    fun `a 7z whose packed header would grow past the memory limit isn't read`() {
        val file = temp.newFile("header.7z").apply { writeBytes(sevenZWithPackedHeader(unpackSize = Int.MAX_VALUE.toLong())) }

        assertEquals(Int.MAX_VALUE.toLong(), java.nio.channels.FileChannel.open(file.toPath()).use { Archives.sevenZHeaderSize(it) })
        assertEquals(ArchiveProblem.TOO_LARGE, problem { listing(file) })
    }

    @Test
    fun `a 7z header of more folders than real ones have is still held to the memory limit`() {
        val file = temp.newFile("folders.7z").apply { writeBytes(sevenZWithPackedHeader(unpackSize = 2L shl 20, folders = 65)) }

        // Its decoders fit this limit; the header it would hold doesn't.
        assertEquals(ArchiveProblem.TOO_LARGE, problem { listing(file, ArchiveLimits(maxMemoryKiB = 512)) })
    }

    @Test
    fun `a packed 7z header the check can't read is a damaged archive, not one left to the reader`() {
        val bytes = sevenZWithPackedHeader(unpackSize = 1L shl 20)
        // The header follows the 32-byte start and 10 bytes of packed data: its first record after
        // "encoded header" made one the check doesn't know. The checksums no longer match, as the
        // reader would find, but the check comes first.
        bytes[32 + 10 + 1] = 0x7F
        val file = temp.newFile("unknown.7z").apply { writeBytes(bytes) }

        assertEquals(ArchiveProblem.BROKEN, problem { listing(file) })
    }

    @Test
    fun `a tar entry's long name or extended header past the limit isn't held whole`() {
        val file = temp.newFile("long.tar.gz")
        GzipCompressorOutputStream(file.outputStream()).use { out ->
            val name = ByteArray(4 shl 20) { 'a'.code.toByte() }
            out.write(tarHeader("././@LongLink", name.size, 'L'))
            out.write(name)
            out.write(tarHeader("short.txt", 0, '0'))
            out.write(ByteArray(1024))
        }

        assertEquals(ArchiveProblem.TOO_LARGE, problem { listing(file) })
    }

    @Test
    fun `a tar's large files still list, their data passed over, not taken for headers`() {
        val file = temp.newFile("large.tar.gz")
        GzipCompressorOutputStream(file.outputStream()).use { out ->
            tar(out) {
                file("big.bin", ByteArray(8 shl 20))
                file("small.txt", "end".toByteArray())
            }
        }

        val listing = listing(file)
        assertEquals(listOf("big.bin", "small.txt"), listing.items.map { it.path })
        assertEquals("end", extract(file, listing.items[1]).readText())
    }

    @Test
    fun `a gz whose name runs on and on isn't read`() {
        val file = temp.newFile("long-name.gz")
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x08, 0, 0, 0, 0, 0, 0x03))
            out.write(ByteArray(100_000) { 'a'.code.toByte() })
            out.write(0)
            java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(9, true)).apply { write("x".toByteArray()); finish() }
            // A right trailer, so only the name is wrong with it.
            val crc = java.util.zip.CRC32().apply { update("x".toByteArray()) }.value
            out.write(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt()).putInt(1).array())
        }

        assertEquals(ArchiveProblem.BROKEN, problem { listing(file) })
    }

    @Test
    fun `a 7z file in a block of its own opens without decoding the files before it`() {
        val file = temp.newFile("blocks.7z")
        SevenZOutputFile(file).use { out ->
            for ((path, bytes) in listOf("zeros.bin" to ByteArray(4 shl 20), "last.txt" to "end".toByteArray())) {
                out.putArchiveEntry(out.createArchiveEntry(File(path), path))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        val last = listing(file).items.last()

        // Decoding the 4 MiB before it would pass this scan limit.
        assertEquals("end", extract(file, last, ArchiveLimits(maxScanBytes = 1L shl 20)).readText())
    }

    @Test
    fun `the reader's own reads of an archive stop when the time is up`() {
        val file = temp.newFile("any.bin").apply { writeBytes(ByteArray(4096)) }
        var now = 0L
        val limits = ArchiveLimits(timeLimitMillis = 5, now = { now })
        val channel = TimedChannel(java.nio.channels.FileChannel.open(file.toPath()), limits, deadline = 5)

        assertEquals(1024, channel.read(java.nio.ByteBuffer.allocate(1024)))
        now = 6
        assertEquals(ArchiveProblem.TOO_LARGE, problem { channel.read(java.nio.ByteBuffer.allocate(1024)) })
    }

    @Test
    fun `a gz of several members reads on through all of them, each header checked`() {
        val tarBytes = java.io.ByteArrayOutputStream().also { out ->
            tar(out) {
                file("first.txt", "one".toByteArray())
                file("second.txt", Random(6).nextBytes(20_000))
                file("third.txt", "three".toByteArray())
            }
        }.toByteArray()
        fun member(bytes: ByteArray, name: String? = null) = java.io.ByteArrayOutputStream().also { out ->
            val parameters = GzipParameters().apply { if (name != null) fileName = name }
            GzipCompressorOutputStream(out, parameters).use { it.write(bytes) }
        }.toByteArray()
        val half = tarBytes.size / 2
        val split = temp.newFile("split.tar.gz").apply { writeBytes(member(tarBytes.copyOfRange(0, half)) + member(tarBytes.copyOfRange(half, tarBytes.size))) }

        val listing = listing(split)
        assertEquals(listOf("first.txt", "second.txt", "third.txt"), listing.items.map { it.path })
        assertEquals("three", extract(split, listing.items[2]).readText())

        // A later member's name running on and on is caught too.
        val longName = temp.newFile("long.tar.gz").apply {
            writeBytes(member(tarBytes.copyOfRange(0, half)) + member(tarBytes.copyOfRange(half, tarBytes.size), name = "n".repeat(100_000)))
        }
        assertEquals(ArchiveProblem.BROKEN, problem { listing(longName) })
    }

    @Test
    fun `a 7z header's digest count is held to the bytes it has before anything is made for it`() {
        assertEquals(2, Archives.definedBitsLength(count = 9, remaining = 10))
        assertEquals(ArchiveProblem.BROKEN, runCatching { Archives.definedBitsLength(count = 8_589_934_592, remaining = 10) }
            .exceptionOrNull()?.let { ArchiveProblem.BROKEN })
    }

    @Test
    fun `passing over a compressed member stops at the scan limit as it reads, not after`() {
        var produced = 0L
        val endless = object : java.io.InputStream() {
            override fun read() = 0.also { produced++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 0)
                produced += len
                return len
            }
            override fun skip(n: Long): Long = n.also { produced += it }
        }
        val counted = Counted(endless, ArchiveLimits(), deadline = Long.MAX_VALUE, limit = 1L shl 20)

        assertEquals(ArchiveProblem.TOO_LARGE, problem { counted.skip(100L shl 20) })
        assertTrue("$produced", produced <= (1L shl 20) + (64 shl 10))
    }

    @Test
    fun `the same file taken out twice is the same bytes`() {
        val bytes = Random(3).nextBytes(100_000)
        val file = zip { file("data.bin", bytes) }
        val item = listing(file).items.single()

        assertArrayEquals(bytes, extract(file, item).readBytes())
        assertArrayEquals(bytes, extract(file, item).readBytes())
    }

    @Test
    fun `a folder lists its folders first, then its files, each by name`() {
        fun item(index: Int, path: String, directory: Boolean = false) = ArchiveItem(index, path, null, null, directory, null)
        val items = listOf(
            item(0, "b.txt"),
            item(1, "Lectures/week 2/slides.pdf"),
            item(2, "lectures/notes.md"),
            item(3, "a.txt"),
            item(4, "empty", directory = true),
        )

        assertEquals(
            listOf("empty/", "Lectures/", "lectures/", "a.txt", "b.txt"),
            ArchiveTree.children(items, folder = "").map { it.label() },
        )
        assertEquals(listOf("week 2/"), ArchiveTree.children(items, folder = "Lectures").map { it.label() })
        assertEquals(listOf("slides.pdf"), ArchiveTree.children(items, folder = "Lectures/week 2").map { it.label() })
        assertEquals("Lectures", ArchiveTree.parent("Lectures/week 2"))
        assertEquals("", ArchiveTree.parent("Lectures"))
    }

    private fun ArchiveNode.label() = when (this) {
        is ArchiveNode.Folder -> "$name/"
        is ArchiveNode.File -> name
    }

    @Test
    fun `paths are shown with forward slashes and without empty or dot parts`() {
        assertEquals("a/b/c.txt", ArchiveNames.normalize("/a\\b/./c.txt"))
        assertEquals("a/b", ArchiveNames.normalize("a//b/"))
    }

    @Test
    fun `a file's extension is kept for the viewer, nothing else of its name`() {
        assertEquals("pdf", ArchiveNames.extension("Week 1/講義.PDF"))
        assertEquals("gz", ArchiveNames.extension("code.tar.gz"))
        assertEquals("", ArchiveNames.extension("Makefile"))
        assertEquals("", ArchiveNames.extension("weird.<script>"))
        assertEquals("", ArchiveNames.extension("long.abcdefghijklmnop"))
    }

    @Test
    fun `nothing is left behind when taking out fails`() {
        val file = zip { file("zeros.bin", ByteArray(8 shl 20)) }
        val into = temp.newFolder("left")

        try {
            Archives.extract(source(file), listing(file).items.single(), into, ArchiveLimits(ratioFreeBytes = 1L shl 20))
            fail("taken out past the limit")
        } catch (_: ArchiveProblemException) {
        }

        assertEquals(emptyList<String>(), into.list()!!.toList())
    }
}
