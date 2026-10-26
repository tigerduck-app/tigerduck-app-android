package org.ntust.app.tigerduck.files

import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarUtils
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption

/** The kinds of archive the viewer reads, told by their first bytes ([Archives.format]). */
enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, GZIP, BZIP2, XZ }

/** Why an archive, or a file in one, isn't shown. */
enum class ArchiveProblem {
    /** Encrypted. */
    PASSWORD,

    /** Packed with a method the app doesn't read, or an archive of a kind it doesn't. */
    UNSUPPORTED,

    /** Larger than the limits, packed far tighter than real files are (a bomb, likely), or too slow to read. */
    TOO_LARGE,

    /** More entries than the app reads at all. */
    TOO_MANY,

    /** A link, with nothing of its own to show. */
    LINK,

    /** Damaged, or not what its first bytes say. */
    BROKEN,
}

class ArchiveProblemException(val problem: ArchiveProblem, cause: Throwable? = null) : IOException(problem.name, cause)

/**
 * One entry of an archive, as the viewer lists it: [index] finds it again, [path] is shown
 * (with `/` between folders), and [problem] says why it can't be opened, if it can't.
 */
data class ArchiveItem(
    val index: Int,
    val path: String,
    val size: Long?,
    val modified: Long?,
    val isDirectory: Boolean,
    val problem: ArchiveProblem?,
)

/** An archive's entries, the first [ArchiveLimits.maxItems] of them: [truncated] when there were more. */
data class ArchiveListing(val format: ArchiveFormat, val items: List<ArchiveItem>, val truncated: Boolean)

/**
 * An archive to read, [size] bytes, opened afresh by [open] for each pass. [name] names a file
 * packed without one: a 7z's unnamed entry, or the one file in a `.gz`.
 */
class ArchiveSource(val name: String, val size: Long, val open: () -> SeekableByteChannel) {
    companion object {
        fun of(file: File) = ArchiveSource(file.name, file.length()) { FileChannel.open(file.toPath(), StandardOpenOption.READ) }
    }
}

/**
 * What one listing or one file taken out may cost. Every byte count is of what's actually read,
 * never of the sizes an archive claims.
 */
data class ArchiveLimits(
    /** The most entries listed. */
    val maxItems: Int = 5_000,
    /** The most a zip's directory may declare; more, and it isn't read at all. */
    val maxZipEntries: Long = 100_000,
    /** The largest file taken out. */
    val maxEntryBytes: Long = 256L shl 20,
    /** How many times its packed size a file taken out may grow to, past [ratioFreeBytes]. */
    val maxRatio: Long = 100,
    val ratioFreeBytes: Long = 16L shl 20,
    /** The most decompressed bytes one pass over a compressed tar reads, to list it or reach a file. */
    val maxScanBytes: Long = 2L shl 30,
    /**
     * The most a tar's headers before one entry may take: the reader holds a long name or an
     * extended header whole, however large it says it is.
     */
    val maxHeaderBytes: Long = 1L shl 20,
    /** The most memory, in KiB, a 7z or an xz may ask its decoder for: 7-Zip's Ultra, a 64 MiB dictionary, fits. */
    val maxMemoryKiB: Int = 96 shl 10,
    val timeLimitMillis: Long = 60_000,
    val now: () -> Long = System::currentTimeMillis,
)

/**
 * Reads the archives the file viewer shows: ZIP, 7z, and tar, plain or in gzip, bzip2 or xz, or
 * one file compressed on its own. Lists what's inside, and takes out one file at a time into a
 * folder of the caller's, under a name of the app's that keeps only the extension: no path
 * an archive claims is ever written.
 */
object Archives {

    fun format(head: ByteArray): ArchiveFormat? = when {
        head.startsWith(0x50, 0x4B, 0x03, 0x04) || head.startsWith(0x50, 0x4B, 0x05, 0x06) ||
            head.startsWith(0x50, 0x4B, 0x07, 0x08) -> ArchiveFormat.ZIP
        head.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SEVEN_Z
        head.startsWith(0x1F, 0x8B) -> ArchiveFormat.GZIP
        isBzip2(head) -> ArchiveFormat.BZIP2
        head.startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> ArchiveFormat.XZ
        isTar(head, head.size) -> ArchiveFormat.TAR
        else -> null
    }

    /**
     * "BZh" and a block size from 1 to 9, then the first block's magic, or the end-of-stream one
     * of an empty stream: "BZh" alone also starts a tar whose first file's name does.
     */
    private fun isBzip2(head: ByteArray): Boolean {
        if (!head.startsWith(0x42, 0x5A, 0x68) || head.size < 10 || head[3].toInt() !in '1'.code..'9'.code) return false
        val magic = head.copyOfRange(4, 10)
        return magic.startsWith(0x31, 0x41, 0x59, 0x26, 0x53, 0x59) || magic.startsWith(0x17, 0x72, 0x45, 0x38, 0x50, 0x90)
    }

    fun list(source: ArchiveSource, limits: ArchiveLimits = ArchiveLimits()): ArchiveListing {
        val deadline = limits.now() + limits.timeLimitMillis
        return guarded {
            when (val format = formatOf(source)) {
                ArchiveFormat.ZIP -> listZip(source, limits, deadline)
                ArchiveFormat.SEVEN_Z -> list7z(source, limits, deadline)
                else -> listStreamed(source, format, limits, deadline)
            }
        }
    }

    /** Takes [item] out of [source] into [into] and returns the file; nothing is left there if it fails. */
    fun extract(source: ArchiveSource, item: ArchiveItem, into: File, limits: ArchiveLimits = ArchiveLimits()): File {
        item.problem?.let { throw ArchiveProblemException(it) }
        if (item.isDirectory) throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED)
        val deadline = limits.now() + limits.timeLimitMillis
        val extension = ArchiveNames.extension(item.path)
        val target = File(into, "item-${item.index}" + if (extension.isEmpty()) "" else ".$extension")
        // A name of its own: another taking of the same file may be writing beside it.
        val partial = File.createTempFile("${target.name}.", ".part", into)
        try {
            guarded {
                partial.outputStream().use { out ->
                    when (val format = formatOf(source)) {
                        ArchiveFormat.ZIP -> extractZip(source, item, out, limits, deadline)
                        ArchiveFormat.SEVEN_Z -> extract7z(source, item, out, limits, deadline)
                        else -> extractStreamed(source, format, item, out, limits, deadline)
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Couldn't keep the file taken out")
            return target
        } finally {
            partial.delete()
        }
    }

    // ZIP

    private fun listZip(source: ArchiveSource, limits: ArchiveLimits, deadline: Long): ArchiveListing =
        openZip(source, limits).use { zip ->
            val items = ArrayList<ArchiveItem>()
            var truncated = false
            for ((index, entry) in zip.entries.asSequence().withIndex()) {
                limits.check(deadline)
                val path = ArchiveNames.normalize(zipName(entry))
                if (path.isEmpty()) continue
                if (items.size == limits.maxItems) {
                    truncated = true
                    break
                }
                items += ArchiveItem(
                    index = index,
                    path = path,
                    size = entry.size.takeIf { it >= 0 && !entry.isDirectory },
                    modified = entry.time.takeIf { it > 0 },
                    isDirectory = entry.isDirectory,
                    problem = zipProblem(zip, entry),
                )
            }
            ArchiveListing(ArchiveFormat.ZIP, items, truncated)
        }

    private fun extractZip(source: ArchiveSource, item: ArchiveItem, out: OutputStream, limits: ArchiveLimits, deadline: Long) {
        openZip(source, limits).use { zip ->
            val entry = zip.entries.asSequence().elementAtOrNull(item.index)
                ?.takeIf { ArchiveNames.normalize(zipName(it)) == item.path }
                ?: throw ArchiveProblemException(ArchiveProblem.BROKEN)
            zipProblem(zip, entry)?.let { throw ArchiveProblemException(it) }
            val packed = entry.compressedSize.takeIf { it > 0 } ?: source.size
            zip.getInputStream(entry).use { copyLimited(it, out, packed, limits, deadline) }
        }
    }

    private fun openZip(source: ArchiveSource, limits: ArchiveLimits): ZipFile {
        val channel = source.open()
        try {
            // The count its end record declares, then the records themselves: the reader goes by the
            // records, however many the record says.
            val directory = zipDirectory(channel)
            if (directory != null) {
                if (directory.entries > limits.maxZipEntries) throw ArchiveProblemException(ArchiveProblem.TOO_MANY)
                if (countDirectoryRecords(channel, directory.offset, limits.maxZipEntries + 1) > limits.maxZipEntries) {
                    throw ArchiveProblemException(ArchiveProblem.TOO_MANY)
                }
            }
            channel.position(0)
            return ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setUseUnicodeExtraFields(true)
                .setIgnoreLocalFileHeader(true)
                .get()
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    private fun zipName(entry: ZipArchiveEntry): String {
        // A name the archive also wrote as Unicode, which the reader has checked against the original.
        if (entry.nameSource == ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) return entry.name
        val raw = entry.rawName ?: return entry.name
        return ArchiveNames.zipName(raw, utf8 = entry.generalPurposeBit.usesUTF8ForNames())
    }

    private fun zipProblem(zip: ZipFile, entry: ZipArchiveEntry): ArchiveProblem? = when {
        entry.isDirectory -> null
        entry.generalPurposeBit.usesEncryption() || entry.method == ZipMethod.AES_ENCRYPTED.code -> ArchiveProblem.PASSWORD
        entry.isUnixSymlink -> ArchiveProblem.LINK
        entry.method in UNREAD_METHODS || !zip.canReadEntryData(entry) -> ArchiveProblem.UNSUPPORTED
        else -> null
    }

    /** What a zip's end of central directory says of its directory: how many entries, and where it starts. */
    internal class ZipDirectory(val entries: Long, val offset: Long)

    /**
     * What a zip's end of central directory declares, read before its directory is, so a zip of a
     * million entries is turned away before they're all in memory: found as the zip reader finds
     * it, so the records counted are the ones it reads. Null when there's no such record, which the
     * zip reader then reports.
     */
    internal fun zipDirectory(channel: SeekableByteChannel): ZipDirectory? {
        val size = channel.size()
        val tailSize = minOf(size, END_RECORD_SIZE + 0xFFFFL).toInt()
        val tailStart = size - tailSize
        val tail = read(channel, tailStart, tailSize) ?: return null
        for (at in tailSize - END_RECORD_SIZE.toInt() downTo 0) {
            if (tail.getInt(at) != END_SIGNATURE) continue
            val endAt = tailStart + at
            // A Zip64 locator just before it: the Zip64 record says where the directory is, whatever
            // this record says, and the reader refuses the zip if that record isn't there.
            val locator = read(channel, endAt - 20, 20)
            if (locator != null && locator.getInt(0) == ZIP64_LOCATOR_SIGNATURE) {
                val recordAt = locator.getLong(8)
                val record = if (recordAt in 0..(size - 56)) read(channel, recordAt, 56) else null
                if (record == null || record.getInt(0) != ZIP64_END_SIGNATURE) return null
                return ZipDirectory(record.getLong(32), record.getLong(48))
            }
            val entries = (tail.getShort(at + 10).toInt() and 0xFFFF).toLong()
            val length = tail.getInt(at + 12).toLong() and 0xFFFFFFFFL
            val offset = tail.getInt(at + 16).toLong() and 0xFFFFFFFFL
            // Data before the archive, as a self-extracting one has, moves its directory along by as
            // much, which the reader allows for.
            return ZipDirectory(entries, offset + maxOf(endAt - length - offset, 0L))
        }
        return null
    }

    /** How many directory records follow one another from [offset], counting no further than [atMost]. */
    internal fun countDirectoryRecords(channel: SeekableByteChannel, offset: Long, atMost: Long): Long {
        if (offset !in 0 until channel.size()) return 0
        channel.position(offset)
        val input = BufferedInputStream(Channels.newInputStream(channel), BUFFER)
        val header = ByteArray(DIRECTORY_RECORD_SIZE)
        var count = 0L
        while (count < atMost) {
            if (!readFully(input, header)) break
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (fields.getInt(0) != DIRECTORY_SIGNATURE) break
            count++
            val rest = (fields.getShort(28).toLong() and 0xFFFF) + (fields.getShort(30).toLong() and 0xFFFF) +
                (fields.getShort(32).toLong() and 0xFFFF)
            if (!skipFully(input, rest)) break
        }
        return count
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }

    private fun read(channel: SeekableByteChannel, at: Long, count: Int): ByteBuffer? {
        if (at < 0 || count <= 0) return null
        val buffer = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(at)
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null
        return buffer.flip() as ByteBuffer
    }

    /**
     * Methods the zip reader takes but the app doesn't: Zstandard needs a native library it
     * doesn't ship, and the reader decodes XZ with no limit on the memory its header asks for.
     */
    private val UNREAD_METHODS = setOf(ZipMethod.ZSTD.code, ZipMethod.ZSTD_DEPRECATED.code, ZipMethod.XZ.code)

    private const val END_RECORD_SIZE = 22L
    private const val END_SIGNATURE = 0x06054B50
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50
    private const val ZIP64_END_SIGNATURE = 0x06064B50
    private const val DIRECTORY_SIGNATURE = 0x02014B50
    private const val DIRECTORY_RECORD_SIZE = 46

    // 7z

    private fun list7z(source: ArchiveSource, limits: ArchiveLimits, deadline: Long): ArchiveListing =
        open7z(source, limits, deadline).use { sevenZ ->
            val items = ArrayList<ArchiveItem>()
            var truncated = false
            for ((index, entry) in sevenZ.entries.withIndex()) {
                limits.check(deadline)
                // An anti-item deletes a file from an earlier archive: nothing to show.
                if (entry.isAntiItem) continue
                val path = ArchiveNames.normalize(entry.name ?: defaultName(source))
                if (path.isEmpty()) continue
                if (items.size == limits.maxItems) {
                    truncated = true
                    break
                }
                items += ArchiveItem(
                    index = index,
                    path = path,
                    size = entry.size.takeIf { !entry.isDirectory },
                    modified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else null,
                    isDirectory = entry.isDirectory,
                    // Whether a file needs a password, or a method the app lacks, shows when it's read.
                    problem = if (!entry.isDirectory && isWindowsOrUnixLink(entry.hasWindowsAttributes, entry.windowsAttributes)) ArchiveProblem.LINK else null,
                )
            }
            ArchiveListing(ArchiveFormat.SEVEN_Z, items, truncated)
        }

    private fun extract7z(source: ArchiveSource, item: ArchiveItem, out: OutputStream, limits: ArchiveLimits, deadline: Long) {
        open7z(source, limits, deadline).use { sevenZ ->
            val entry = sevenZ.entries.elementAtOrNull(item.index)
                ?.takeIf { ArchiveNames.normalize(it.name ?: defaultName(source)) == item.path }
                ?: throw ArchiveProblemException(ArchiveProblem.BROKEN)
            if (entry.isDirectory || entry.isAntiItem) throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED)
            if (!entry.hasStream()) return
            // The reader skips the blocks the file isn't in, and decodes the files before it in its
            // own: the time limit stops that too, through the channel it reads.
            sevenZ.getInputStream(entry).use { copyLimited(it, out, source.size, limits, deadline) }
        }
    }

    private fun open7z(source: ArchiveSource, limits: ArchiveLimits, deadline: Long): SevenZFile {
        val channel = TimedChannel(source.open(), limits, deadline)
        try {
            // The reader holds a 7z's header whole, however large its packed form says it grows.
            val header = sevenZHeaderSize(channel)
            if (header != null && header > limits.maxMemoryKiB * 1024L) throw ArchiveProblemException(ArchiveProblem.TOO_LARGE)
            channel.position(0)
            return SevenZFile.builder()
                .setSeekableByteChannel(channel)
                .setDefaultName(defaultName(source))
                .setUseDefaultNameForUnnamedEntries(true)
                .setMaxMemoryLimitKiB(limits.maxMemoryKiB)
                .get()
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    /**
     * How large a 7z's header is once read: its size, or for a packed header, the largest size its
     * packed form's folder says it unpacks to. Null when the start of the file doesn't say, which
     * the 7z reader then reports.
     */
    internal fun sevenZHeaderSize(channel: SeekableByteChannel): Long? {
        val start = read(channel, 0, SEVEN_Z_START_SIZE) ?: return null
        val offset = start.getLong(12)
        val size = start.getLong(20)
        if (offset < 0 || size <= 0 || SEVEN_Z_START_SIZE + offset + size > channel.size()) return null
        if (size > SEVEN_Z_PACKED_HEADER_PEEK) return size
        val header = read(channel, SEVEN_Z_START_SIZE + offset, size.toInt()) ?: return null
        if (header.get(0) != ENCODED_HEADER) return size
        // One it can't read is a damaged archive, never one left to the reader unchecked.
        return runCatching { packedHeaderUnpackSize(header) }
            .getOrElse { throw ArchiveProblemException(ArchiveProblem.BROKEN, it) }
    }

    /** The largest unpack size a packed 7z header's folders declare: what reading it would hold. */
    private fun packedHeaderUnpackSize(header: ByteBuffer): Long {
        header.position(1)
        var id = header.get().toInt()
        if (id == PACK_INFO) {
            sevenZNumber(header)
            val streams = sevenZNumber(header)
            // Each stream takes a byte of the header at least.
            require(streams in 1..header.remaining().toLong())
            id = header.get().toInt()
            if (id == SIZE) {
                repeat(streams.toInt()) { sevenZNumber(header) }
                id = header.get().toInt()
            }
            if (id == CRC) {
                skipDigests(header, streams)
                id = header.get().toInt()
            }
            require(id == END)
            id = header.get().toInt()
        }
        require(id == UNPACK_INFO && header.get().toInt() == FOLDER)
        // Every count held to the bytes the header has left, each thing it counts taking one at least.
        val folders = sevenZNumber(header)
        require(folders in 1..header.remaining().toLong() && header.get().toInt() == 0)
        val outputs = LongArray(folders.toInt()) {
            val coders = sevenZNumber(header)
            require(coders in 1..header.remaining().toLong())
            var inputs = 0L
            var outputs = 0L
            repeat(coders.toInt()) {
                val flags = header.get().toInt() and 0xFF
                header.position(header.position() + (flags and 0x0F))
                if (flags and 0x10 != 0) {
                    inputs += sevenZNumber(header)
                    outputs += sevenZNumber(header)
                } else {
                    inputs++
                    outputs++
                }
                if (flags and 0x20 != 0) {
                    val properties = sevenZNumber(header)
                    header.position(header.position() + properties.toInt())
                }
            }
            require(outputs in 1..header.remaining().toLong() + 1 && inputs in 1..header.remaining().toLong() + coders)
            repeat((outputs - 1).toInt()) {
                sevenZNumber(header)
                sevenZNumber(header)
            }
            val packed = inputs - (outputs - 1)
            if (packed > 1) repeat(packed.toInt()) { sevenZNumber(header) }
            outputs
        }
        require(header.get().toInt() == UNPACK_SIZES)
        var largest = 0L
        for (count in outputs) repeat(count.toInt()) { largest = maxOf(largest, sevenZNumber(header)) }
        return largest
    }

    /** 7z's own number: a first byte whose leading ones count the bytes after it. */
    private fun sevenZNumber(header: ByteBuffer): Long {
        val first = header.get().toLong() and 0xFF
        var mask = 0x80
        var value = 0L
        for (i in 0 until 8) {
            if (first and mask.toLong() == 0L) return value or ((first and (mask - 1).toLong()) shl (8 * i))
            value = value or ((header.get().toLong() and 0xFF) shl (8 * i))
            mask = mask ushr 1
        }
        return value
    }

    /**
     * How many bytes the bits marking which of [count] digests are there take, held to the
     * [remaining] bytes of the header: a count it can't hold is a damaged header, not a request
     * for memory.
     */
    internal fun definedBitsLength(count: Long, remaining: Int): Int {
        require(count in 0..remaining * 8L)
        return ((count + 7) / 8).toInt()
    }

    private fun skipDigests(header: ByteBuffer, count: Long) {
        val all = header.get().toInt() != 0
        var defined = count
        if (!all) {
            val bits = ByteArray(definedBitsLength(count, header.remaining())).also { header.get(it) }
            defined = (0 until count).count { bits[(it / 8).toInt()].toInt() and (0x80 ushr (it % 8).toInt()) != 0 }.toLong()
        }
        header.position(header.position() + (4 * defined).toInt())
    }

    private const val SEVEN_Z_START_SIZE = 32

    /** A packed header is small; past this its size alone is too much to read. */
    private const val SEVEN_Z_PACKED_HEADER_PEEK = 1L shl 20
    private const val ENCODED_HEADER: Byte = 0x17
    private const val END = 0x00
    private const val PACK_INFO = 0x06
    private const val UNPACK_INFO = 0x07
    private const val SIZE = 0x09
    private const val CRC = 0x0A
    private const val FOLDER = 0x0B
    private const val UNPACK_SIZES = 0x0C

    /** A 7z entry stored as a link: by its Unix mode, kept in the high half, or as a Windows reparse point. */
    private fun isWindowsOrUnixLink(has: Boolean, attributes: Int): Boolean {
        if (!has) return false
        val unix = attributes and UNIX_EXTENSION != 0 && (attributes ushr 16) and 0xF000 == 0xA000
        return unix || attributes and REPARSE_POINT != 0
    }

    private const val UNIX_EXTENSION = 0x8000
    private const val REPARSE_POINT = 0x400

    // tar, and gzip, bzip2 or xz around a tar or a file of its own

    private fun listStreamed(source: ArchiveSource, format: ArchiveFormat, limits: ArchiveLimits, deadline: Long): ArchiveListing =
        streamed(source, format, limits, deadline) { pass ->
            val tar = pass.tar ?: return@streamed ArchiveListing(
                format,
                listOf(ArchiveItem(0, pass.singleName, null, pass.singleModified, isDirectory = false, problem = null)),
                truncated = false,
            )
            val items = ArrayList<ArchiveItem>()
            var truncated = false
            var index = 0
            var unread = 0L
            while (true) {
                limits.check(deadline)
                val entry = pass.nextEntry(tar, unread, limits) ?: break
                unread = dataAfter(entry)
                val path = ArchiveNames.normalize(entry.name)
                val at = index++
                if (path.isEmpty()) continue
                if (items.size == limits.maxItems) {
                    truncated = true
                    break
                }
                items += ArchiveItem(
                    index = at,
                    path = path,
                    size = entry.size.takeIf { !entry.isDirectory && it >= 0 },
                    modified = entry.lastModifiedDate?.time?.takeIf { it > 0 },
                    isDirectory = entry.isDirectory,
                    problem = tarProblem(entry),
                )
            }
            ArchiveListing(format, items, truncated)
        }

    private fun extractStreamed(
        source: ArchiveSource,
        format: ArchiveFormat,
        item: ArchiveItem,
        out: OutputStream,
        limits: ArchiveLimits,
        deadline: Long,
    ) {
        streamed(source, format, limits, deadline) { pass ->
            val tar = pass.tar
            if (tar == null) {
                if (pass.singleName != item.path) throw ArchiveProblemException(ArchiveProblem.BROKEN)
                copyLimited(pass.stream, out, source.size, limits, deadline)
                return@streamed
            }
            var index = 0
            var unread = 0L
            while (true) {
                limits.check(deadline)
                val entry = pass.nextEntry(tar, unread, limits) ?: throw ArchiveProblemException(ArchiveProblem.BROKEN)
                unread = dataAfter(entry)
                if (index++ != item.index) continue
                // The archive changed since it was listed: not the file that was tapped.
                if (ArchiveNames.normalize(entry.name) != item.path) throw ArchiveProblemException(ArchiveProblem.BROKEN)
                tarProblem(entry)?.let { throw ArchiveProblemException(it) }
                if (entry.isDirectory) throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED)
                copyLimited(tar, out, source.size, limits, deadline)
                return@streamed
            }
        }
    }

    private fun tarProblem(entry: TarArchiveEntry): ArchiveProblem? = when {
        entry.isSymbolicLink || entry.isLink -> ArchiveProblem.LINK
        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> ArchiveProblem.UNSUPPORTED
        else -> null
    }

    /** One pass over a streamed archive: a tar's entries, or the one file a compressor holds; [meter] counts it. */
    private class Pass(
        val stream: InputStream,
        val tar: TarArchiveInputStream?,
        val singleName: String,
        val singleModified: Long?,
        val meter: Counted,
    ) {
        /**
         * [tar]'s next entry, its reads held to the [unread] data of the one before and
         * [ArchiveLimits.maxHeaderBytes] of headers: a long name past that isn't held whole.
         */
        fun nextEntry(tar: TarArchiveInputStream, unread: Long, limits: ArchiveLimits): TarArchiveEntry? {
            meter.window(unread + limits.maxHeaderBytes + BUFFER)
            try {
                return tar.nextEntry
            } finally {
                meter.window(null)
            }
        }
    }

    /** What [entry]'s data takes in its tar: as much as it holds, padded to the 512-byte record. */
    private fun dataAfter(entry: TarArchiveEntry): Long = (maxOf(entry.size, entry.realSize) + 511) / 512 * 512

    private inline fun <T> streamed(
        source: ArchiveSource,
        format: ArchiveFormat,
        limits: ArchiveLimits,
        deadline: Long,
        block: (Pass) -> T,
    ): T = source.open().use { channel ->
        val raw = BufferedInputStream(Channels.newInputStream(channel), BUFFER)
        var packedName: String? = null
        var packedModified: Long? = null
        val unpacked: InputStream = when (format) {
            ArchiveFormat.TAR -> raw
            ArchiveFormat.GZIP -> GzipMembers(raw).also { gzip ->
                packedName = gzip.firstName
                packedModified = gzip.firstModified
            }
            ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(raw, true)
            ArchiveFormat.XZ -> XZCompressorInputStream.builder()
                .setInputStream(raw)
                .setDecompressConcatenated(true)
                .setMemoryLimitKiB(limits.maxMemoryKiB)
                .get()
            else -> throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED)
        }
        // A plain tar costs nothing to pass over; a compressed one costs every byte it holds.
        val meter = Counted(unpacked, limits, deadline, if (format == ArchiveFormat.TAR) null else limits.maxScanBytes)
        val counted = BufferedInputStream(meter, BUFFER)
        counted.use {
            val tar = if (format == ArchiveFormat.TAR || startsAsTar(counted)) TarArchiveInputStream(counted, "UTF-8") else null
            val name = packedName?.let(ArchiveNames::normalize)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: defaultName(source)
            block(Pass(counted, tar, name, packedModified, meter))
        }
    }

    /**
     * A gzip's members one after another, as one stream, each one's header checked before the
     * reader takes it: the reader holds a member's name and comment whole, however long.
     */
    private class GzipMembers(private val raw: BufferedInputStream) : InputStream() {
        private var member = next()
        val firstName: String? = member.metaData.fileName
        val firstModified: Long? = member.metaData.modificationTime.takeIf { it > 0 }

        private fun next(): GzipCompressorInputStream {
            checkGzipHeader(raw)
            // One member at a time: the reader leaves [raw] just after it.
            return GzipCompressorInputStream.builder().setInputStream(raw).setDecompressConcatenated(false).get()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val read = member.read(b, off, len)
                if (read >= 0 || !startsAnother()) return read
                member = next()
            }
        }

        /** Whether another member follows; anything else after the last, gzip itself leaves be. */
        private fun startsAnother(): Boolean {
            raw.mark(2)
            val first = raw.read()
            val second = raw.read()
            raw.reset()
            return first == 0x1F && second == 0x8B
        }

        override fun close() = member.close()
    }

    /** A gzip header whose name or comment runs past [GZIP_FIELD_MAX] bytes is taken for a damaged one. */
    private fun checkGzipHeader(stream: BufferedInputStream) {
        stream.mark(GZIP_HEADER_PEEK)
        try {
            val fixed = ByteArray(10)
            if (!readFully(stream, fixed)) throw ArchiveProblemException(ArchiveProblem.BROKEN)
            val flags = fixed[3].toInt()
            if (flags and GZIP_EXTRA != 0) {
                val length = stream.read() or (stream.read() shl 8)
                if (length < 0 || !skipFully(stream, length.toLong())) throw ArchiveProblemException(ArchiveProblem.BROKEN)
            }
            for (field in listOf(GZIP_NAME, GZIP_COMMENT)) {
                if (flags and field == 0) continue
                var length = 0
                while (true) {
                    val byte = stream.read()
                    if (byte < 0 || ++length > GZIP_FIELD_MAX) throw ArchiveProblemException(ArchiveProblem.BROKEN)
                    if (byte == 0) break
                }
            }
        } finally {
            stream.reset()
        }
    }

    private const val GZIP_EXTRA = 0x04
    private const val GZIP_NAME = 0x08
    private const val GZIP_COMMENT = 0x10
    private const val GZIP_FIELD_MAX = 64 shl 10
    private const val GZIP_HEADER_PEEK = 10 + 2 + 0xFFFF + 2 * GZIP_FIELD_MAX + 16

    private fun startsAsTar(stream: BufferedInputStream): Boolean {
        stream.mark(TAR_HEADER)
        val head = ByteArray(TAR_HEADER)
        var read = 0
        while (read < head.size) {
            val n = stream.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        stream.reset()
        return isTar(head, read)
    }

    /**
     * Whether [head] starts with a tar header: marked `ustar`, as POSIX and GNU tar write, or, as
     * a V7 tar has no mark, holding the checksum of its own bytes.
     */
    private fun isTar(head: ByteArray, length: Int): Boolean {
        if (length < TAR_HEADER) return false
        val marked = "ustar".indices.all { head[TAR_MAGIC_AT + it] == "ustar"[it].code.toByte() }
        // It throws, rather than say no, on a checksum field that isn't octal.
        return marked || runCatching { TarUtils.verifyCheckSum(head.copyOf(TAR_HEADER)) }.getOrDefault(false)
    }

    private const val TAR_HEADER = 512
    private const val TAR_MAGIC_AT = 257

    // Shared

    private fun formatOf(source: ArchiveSource): ArchiveFormat = source.open().use { channel ->
        val head = ByteBuffer.allocate(TAR_HEADER)
        while (head.hasRemaining()) if (channel.read(head) < 0) break
        format(head.array().copyOf(head.position())) ?: throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED)
    }

    private fun defaultName(source: ArchiveSource): String {
        val name = source.name.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name).ifEmpty { "file" }
    }

    /** Copies [input] to [output], stopping past the size limit or [packed] × the ratio limit. */
    internal fun copyLimited(input: InputStream, output: OutputStream, packed: Long, limits: ArchiveLimits, deadline: Long) {
        val buffer = ByteArray(BUFFER)
        var written = 0L
        val ratioLimit = packed.coerceAtLeast(1).let { if (it > Long.MAX_VALUE / limits.maxRatio) Long.MAX_VALUE else it * limits.maxRatio }
        while (true) {
            limits.check(deadline)
            val read = input.read(buffer)
            if (read < 0) return
            written += read
            if (written > limits.maxEntryBytes || (written > limits.ratioFreeBytes && written > ratioLimit)) {
                throw ArchiveProblemException(ArchiveProblem.TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
    }

    /** [block], its failures told as an [ArchiveProblem]; being stopped stays what it is. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ArchiveProblemException) {
        throw e
    } catch (e: InterruptedIOException) {
        throw e
    } catch (e: ClosedByInterruptException) {
        throw e
    } catch (e: PasswordRequiredException) {
        throw ArchiveProblemException(ArchiveProblem.PASSWORD, e)
    } catch (e: MemoryLimitException) {
        throw ArchiveProblemException(ArchiveProblem.TOO_LARGE, e)
    } catch (e: UnsupportedZipFeatureException) {
        val problem = if (e.feature == UnsupportedZipFeatureException.Feature.ENCRYPTION) ArchiveProblem.PASSWORD else ArchiveProblem.UNSUPPORTED
        throw ArchiveProblemException(problem, e)
    } catch (e: IOException) {
        // Commons Compress names a 7z method it lacks, PPMd for one, only in its message.
        val problem = if (e.message?.startsWith("Unsupported compression method") == true) ArchiveProblem.UNSUPPORTED else ArchiveProblem.BROKEN
        throw ArchiveProblemException(problem, e)
    } catch (e: StackOverflowError) {
        // Commons Compress reads a tar's extended headers by recursing, one level for each.
        throw ArchiveProblemException(ArchiveProblem.BROKEN, e)
    } catch (e: LinkageError) {
        // A method whose decoder lives in a library the app doesn't ship.
        throw ArchiveProblemException(ArchiveProblem.UNSUPPORTED, e)
    } catch (e: OutOfMemoryError) {
        throw ArchiveProblemException(ArchiveProblem.TOO_LARGE, e)
    } catch (e: RuntimeException) {
        // A damaged archive can trip the readers' own checks.
        throw ArchiveProblemException(ArchiveProblem.BROKEN, e)
    }

    private const val BUFFER = 64 shl 10

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }
}

/** How the viewer reads and shows the names in an archive. */
object ArchiveNames {

    /**
     * A zip entry's name from its bytes: UTF-8 when the zip says so, or when they read as UTF-8;
     * otherwise CP950, as Windows in Taiwan writes them without saying; failing both, CP437, the
     * zip format's own code page, in which any bytes read as something.
     */
    fun zipName(raw: ByteArray, utf8: Boolean): String {
        if (utf8) return String(raw, Charsets.UTF_8)
        return strict(raw, Charsets.UTF_8) ?: CP950?.let { strict(raw, it) } ?: String(raw, CP437)
    }

    /** [path] with `/` between its parts and without empty or `.` ones, as the viewer shows it. */
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** The extension a file taken out keeps: letters and digits only, or none. */
    fun extension(path: String): String {
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension.takeIf { it.length in 1..10 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: ""
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun charset(vararg names: String): Charset? =
        names.firstNotNullOfOrNull { name -> runCatching { Charset.forName(name) }.getOrNull() }

    private val CP950: Charset? = charset("x-windows-950", "MS950", "windows-950", "Big5")
    private val CP437: Charset = charset("IBM437", "cp437") ?: Charsets.ISO_8859_1
}

/** A folder's entry in the viewer's list. */
sealed interface ArchiveNode {
    val name: String

    data class Folder(val path: String, override val name: String) : ArchiveNode

    data class File(val item: ArchiveItem, override val name: String) : ArchiveNode
}

/** An archive's entries as folders: those its files sit in count, whether or not it lists them. */
object ArchiveTree {

    /** What's directly in [folder] (`""` for the top): its folders first, then its files, each by name. */
    fun children(items: List<ArchiveItem>, folder: String): List<ArchiveNode> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val folders = linkedMapOf<String, ArchiveNode.Folder>()
        val files = ArrayList<ArchiveNode.File>()
        for (item in items) {
            if (!item.path.startsWith(prefix) || item.path == folder) continue
            val rest = item.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            when {
                slash >= 0 -> rest.substring(0, slash).let { name -> folders.getOrPut(name) { ArchiveNode.Folder(prefix + name, name) } }
                item.isDirectory -> folders.getOrPut(rest) { ArchiveNode.Folder(prefix + rest, rest) }
                else -> files += ArchiveNode.File(item, rest)
            }
        }
        val byName = compareBy<ArchiveNode>({ it.name.lowercase() }, { it.name })
        return folders.values.sortedWith(byName) + files.sortedWith(byName)
    }

    /** The folder [folder] is in, `""` for the top. */
    fun parent(folder: String): String = folder.substringBeforeLast('/', missingDelimiterValue = "")
}

/** Counts what's read through it, and stops a pass past [limit] bytes or the deadline. */
private const val SKIP_CHUNK = 64 shl 10

internal class Counted(input: InputStream, private val limits: ArchiveLimits, private val deadline: Long, private val limit: Long?) :
    FilterInputStream(input) {
    private var count = 0L
    private var windowLeft: Long? = null

    /** Holds the reads from here until it's lifted (null) to [bytes] more; past them, a pass stops. */
    fun window(bytes: Long?) {
        windowLeft = bytes
    }

    override fun read(): Int {
        limits.check(deadline)
        return super.read().also { if (it >= 0) counted(1) }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        limits.check(deadline)
        return super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }
    }

    override fun skip(n: Long): Long {
        limits.check(deadline)
        if (limit == null) return super.skip(n).also { if (it > 0) counted(it) }
        // Through bounded reads, so the limit stops a long skip partway, not once it's done.
        val buffer = ByteArray(minOf(n, SKIP_CHUNK.toLong()).toInt().coerceAtLeast(1))
        var skipped = 0L
        while (skipped < n) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    private fun counted(bytes: Long) {
        count += bytes
        if (limit != null && count > limit) throw ArchiveProblemException(ArchiveProblem.TOO_LARGE)
        windowLeft?.let { left ->
            if (bytes > left) throw ArchiveProblemException(ArchiveProblem.TOO_LARGE)
            windowLeft = left - bytes
        }
    }
}

/** Stops a pass when its thread is interrupted, or past [deadline]. */
internal fun ArchiveLimits.check(deadline: Long) {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Stopped")
    if (now() > deadline) throw ArchiveProblemException(ArchiveProblem.TOO_LARGE)
}

/**
 * [channel], every read of it stopped once the thread is interrupted or [deadline] passes: the
 * reader's own reads too, as when it decodes the files before one in a solid 7z block.
 */
internal class TimedChannel(
    private val channel: SeekableByteChannel,
    private val limits: ArchiveLimits,
    private val deadline: Long,
) : SeekableByteChannel by channel {
    override fun read(dst: ByteBuffer): Int {
        limits.check(deadline)
        return channel.read(dst)
    }
}
