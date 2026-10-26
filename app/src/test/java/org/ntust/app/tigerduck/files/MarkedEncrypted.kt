package org.ntust.app.tigerduck.files

import java.io.File

// A copy of :feature:files' test helper of the same name (FakeArchives.kt): ArchiveViewerTest
// stays in :app, beside the FileProvider it hands entries out through, and no module's tests see
// another's.

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
