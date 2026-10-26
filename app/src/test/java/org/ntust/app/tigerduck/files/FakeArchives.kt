package org.ntust.app.tigerduck.files

import java.io.File

/**
 * This zip, with its entry [name] flagged as encrypted, in its local header and in the central
 * directory: no zip writer at hand writes an encrypted entry, and the flag is all a reader sees
 * before it would need the password.
 */
internal fun File.markedEncrypted(name: String): File = apply {
    val bytes = readBytes()
    val target = name.toByteArray()
    fun short(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun named(at: Int, length: Int) = length == target.size && target.indices.all { bytes[at + it] == target[it] }
    var marked = 0
    for (at in 0 until bytes.size - 4) {
        val signature = short(at) or (short(at + 2) shl 16)
        when {
            // Local file header: flags at 6, name length at 26, name at 30.
            signature == 0x04034B50 && named(at + 30, short(at + 26)) -> { bytes[at + 6] = (bytes[at + 6].toInt() or 1).toByte(); marked++ }
            // Central directory header: flags at 8, name length at 28, name at 46.
            signature == 0x02014B50 && named(at + 46, short(at + 28)) -> { bytes[at + 8] = (bytes[at + 8].toInt() or 1).toByte(); marked++ }
        }
    }
    check(marked == 2) { "$name found $marked times" }
    writeBytes(bytes)
}

/**
 * A V7 tar, as tar wrote before POSIX: each header with no `ustar` mark, only its checksum to
 * show it's a header. Made by hand, since tar writers today all mark theirs.
 */
internal fun v7Tar(vararg files: Pair<String, ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for ((name, data) in files) {
        val header = ByteArray(512)
        fun put(at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, at)
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, "%011o\u0000".format(data.size))
        put(136, "%011o\u0000".format(1_700_000_000L))
        header[156] = '0'.code.toByte()
        // The checksum counts its own field as spaces.
        for (at in 148 until 156) header[at] = ' '.code.toByte()
        put(148, "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xFF }))
        out.write(header)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}

/** A ustar header for [name], [size] bytes of [type] after it, its checksum filled in. */
internal fun tarHeader(name: String, size: Int, type: Char): ByteArray {
    val header = ByteArray(512)
    fun put(at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, at)
    put(0, name)
    put(100, "0000644\u0000")
    put(108, "0000000\u0000")
    put(116, "0000000\u0000")
    put(124, "%011o\u0000".format(size))
    put(136, "%011o\u0000".format(1_700_000_000L))
    header[156] = type.code.toByte()
    put(257, "ustar\u000000")
    for (at in 148 until 156) header[at] = ' '.code.toByte()
    put(148, "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xFF }))
    return header
}

/** This zip, its end of central directory record declaring [count] entries, whatever it holds. */
internal fun File.declaringEntries(count: Int): File = apply {
    val bytes = readBytes()
    val at = (bytes.size - 22 downTo 0).first { at ->
        bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() && bytes[at + 2] == 0x05.toByte() && bytes[at + 3] == 0x06.toByte()
    }
    for (field in listOf(at + 8, at + 10)) {
        bytes[field] = (count and 0xFF).toByte()
        bytes[field + 1] = (count shr 8 and 0xFF).toByte()
    }
    writeBytes(bytes)
}

/** This zip, its entry [name] saying it was packed with [method], in both its headers. */
internal fun File.packedWith(name: String, method: Int): File = apply {
    val bytes = readBytes()
    val target = name.toByteArray()
    fun short(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun named(at: Int, length: Int) = length == target.size && target.indices.all { bytes[at + it] == target[it] }
    fun setShort(at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = (value shr 8 and 0xFF).toByte()
    }
    for (at in 0 until bytes.size - 4) {
        val signature = short(at) or (short(at + 2) shl 16)
        if (signature == 0x04034B50 && named(at + 30, short(at + 26))) setShort(at + 8, method)
        if (signature == 0x02014B50 && named(at + 46, short(at + 28))) setShort(at + 10, method)
    }
    writeBytes(bytes)
}

/**
 * A 7z of nothing but a packed header claiming to grow to [unpackSize] bytes: LZMA2, from ten
 * bytes of junk. Its checksums are right, so a reader gets as far as reading that header.
 */
internal fun sevenZWithPackedHeader(unpackSize: Long, folders: Int = 1): ByteArray {
    fun number(value: Long): ByteArray {
        // 7z's own number: a first byte whose leading ones count the bytes after it.
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(0xFF)
        for (i in 0 until 8) bytes.write((value ushr (8 * i)).toInt() and 0xFF)
        return bytes.toByteArray()
    }
    val next = java.io.ByteArrayOutputStream().apply {
        write(byteArrayOf(0x17, 0x06, 0x00, 0x01, 0x09, 0x0A, 0x00)) // encoded header; pack info: at 0, one stream of 10
        write(byteArrayOf(0x07, 0x0B)) // unpack info: folders, inline
        write(number(folders.toLong()))
        write(0x00)
        // Each one coder: LZMA2, its one byte of properties a 4 KiB dictionary, small enough for any decoder limit.
        repeat(folders) { write(byteArrayOf(0x01, 0x21, 0x21, 0x01, 0x00)) }
        write(0x0C) // unpack sizes
        repeat(folders) { write(number(unpackSize)) }
        write(byteArrayOf(0x00, 0x00))
    }.toByteArray()
    val junk = ByteArray(10) { 0x55 }
    val tail = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putLong(junk.size.toLong())
        .putLong(next.size.toLong())
        .putInt(java.util.zip.CRC32().apply { update(next) }.value.toInt())
        .array()
    val start = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04))
        .putInt(java.util.zip.CRC32().apply { update(tail) }.value.toInt())
        .put(tail)
        .array()
    return start + junk + next
}

/**
 * This zip with [count] bytes before it, as a self-extracting one has, its offsets left as they
 * were. They start as a zip's local header does, so the file is still taken for a zip.
 */
internal fun File.prefixedWith(count: Int): File = apply {
    val prefix = ByteArray(count) { 0x20 }.also { byteArrayOf(0x50, 0x4B, 0x03, 0x04).copyInto(it) }
    writeBytes(prefix + readBytes())
}

/**
 * This zip with a Zip64 end record and locator added, pointing at its directory and declaring no
 * entries, and its own end record's offset and count made wrong: a reader that sees the locator
 * goes by the Zip64 record, as the zip reader does.
 */
internal fun File.withZip64Record(): File = apply {
    val bytes = readBytes()
    val end = (bytes.size - 22 downTo 0).first { at ->
        bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() && bytes[at + 2] == 0x05.toByte() && bytes[at + 3] == 0x06.toByte()
    }
    val record = java.nio.ByteBuffer.wrap(bytes, end, 22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val directorySize = record.getInt(end + 12).toLong() and 0xFFFFFFFFL
    val directoryAt = record.getInt(end + 16).toLong() and 0xFFFFFFFFL
    val zip64 = java.nio.ByteBuffer.allocate(56 + 20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt(0x06064B50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
        .putLong(0).putLong(0).putLong(directorySize).putLong(directoryAt)
        .putInt(0x07064B50).putInt(0).putLong(end.toLong()).putInt(1)
        .array()
    val oldEnd = bytes.copyOfRange(end, bytes.size)
    java.nio.ByteBuffer.wrap(oldEnd).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        putShort(8, 0)
        putShort(10, 0)
        putInt(16, 0)
    }
    writeBytes(bytes.copyOfRange(0, end) + zip64 + oldEnd)
}
