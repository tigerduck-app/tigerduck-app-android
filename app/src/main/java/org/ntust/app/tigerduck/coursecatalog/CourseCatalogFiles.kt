package org.ntust.app.tigerduck.coursecatalog

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.clubs.ETaggedBody
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each term's catalog in each language as the backend sent it, with its ETag, in
 * `cacheDir/course_catalog/`: the ETag on the first line, the body after it. The catalog is the
 * school's, the same for every account, so no sign-out clears it (`docs/account-state.md`).
 * As the iPhone's `CourseCatalogFiles`.
 */
@Singleton
class CourseCatalogFiles internal constructor(private val folder: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, DIR))

    /** Replaces the stored copy whole: a write cut short leaves the one before, never half of each. */
    fun save(copy: ETaggedBody, semester: String, language: String) {
        val file = file(semester, language)
        val partial = File(folder, file.name + ".tmp")
        try {
            folder.mkdirs()
            partial.writeText(copy.etag.orEmpty() + "\n" + copy.body)
            if (!partial.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: Exception) {
            partial.delete()
            Log.w(TAG, "saving the course catalog failed: ${e.logSummary()}")
        }
    }

    /** The stored copy, or null when there is none or it cannot be read. */
    fun load(semester: String, language: String): ETaggedBody? {
        val text = try {
            file(semester, language).takeIf { it.isFile }?.readText() ?: return null
        } catch (e: IOException) {
            Log.w(TAG, "reading the course catalog failed: ${e.logSummary()}")
            return null
        }
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        return ETaggedBody(text.substring(newline + 1), text.substring(0, newline).ifEmpty { null })
    }

    private fun file(semester: String, language: String): File =
        File(folder, "course_catalog_${semester.filter(Char::isLetterOrDigit)}_${language.filter(Char::isLetterOrDigit)}.txt")

    companion object {
        private const val TAG = "CourseCatalog"
        const val DIR = "course_catalog"
    }
}
