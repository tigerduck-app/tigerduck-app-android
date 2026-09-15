package org.ntust.app.tigerduck.mail.store

import com.google.gson.Gson
import org.ntust.app.tigerduck.data.model.mail.BodyCacheDto
import org.ntust.app.tigerduck.data.model.mail.FolderCacheDto
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailPage
import java.io.File
import java.security.MessageDigest

/**
 * Versioned JSON files under cacheDir/mail (spec §8.1). The server is the
 * source of truth: anything unreadable is deleted and fetched again.
 */
class MailCache(
    private val root: File,
    private val gson: Gson = Gson(),
    private val maxBodyBytes: Long = 20L * 1024 * 1024,
) {
    private val foldersDir get() = File(root, "folders")
    private val bodiesDir get() = File(root, "bodies")
    val attachmentsDir: File get() = File(root, "attachments")

    @Synchronized
    fun loadFolder(folder: String): FolderCacheDto? {
        val file = File(foldersDir, key(folder))
        val dto = read(file, FolderCacheDto::class.java) ?: return null
        if (dto.version != VERSION) {
            file.delete(); return null
        }
        return dto
    }

    @Synchronized
    fun saveFolder(folder: String, page: MailPage) {
        write(
            File(foldersDir, key(folder)),
            FolderCacheDto(VERSION, page.uidValidity, page.totalMessages, page.nextBeforeSeq ?: 0, page.messages.map { it.toDto() }),
        )
    }

    /** Drops [folder]'s cached page, e.g. once its UIDVALIDITY no longer matches the server's. */
    @Synchronized
    fun deleteFolder(folder: String) {
        File(foldersDir, key(folder)).delete()
    }

    @Synchronized
    fun loadBody(folder: String, uid: Long, uidValidity: Long): MailBody? {
        val file = File(bodiesDir, key("$folder\u0000$uid"))
        val dto = read(file, BodyCacheDto::class.java) ?: return null
        if (dto.version != VERSION || dto.uidValidity != uidValidity || dto.uid != uid) {
            file.delete(); return null
        }
        file.setLastModified(System.currentTimeMillis())
        return dto.toModel()
    }

    @Synchronized
    fun saveBody(folder: String, uid: Long, uidValidity: Long, body: MailBody) {
        write(File(bodiesDir, key("$folder\u0000$uid")), body.toDto(uidValidity, uid))
        evictBodies()
    }

    @Synchronized
    fun clearAll() {
        root.deleteRecursively()
    }

    private fun <T> read(file: File, type: Class<T>): T? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), type)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(file: File, value: Any) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(gson.toJson(value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: java.io.IOException) {
            tmp.delete()
        }
    }

    private fun evictBodies() {
        val files = bodiesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBodyBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"

    companion object {
        const val VERSION = 1
    }
}
