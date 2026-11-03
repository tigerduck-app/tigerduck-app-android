package org.ntust.app.tigerduck.data.cache

import org.ntust.app.tigerduck.util.logSummary
import java.io.File

// Logged under DataCache's tag, as it was while the write was DataCache's own.
private const val TAG = "DataCache"

/**
 * Write-to-temp + rename so a crash or full disk mid-write can't leave a
 * truncated JSON file behind. This matters most for userDataDir: a
 * truncated `manual_courses_*.json` loses data that has no remote source
 * to re-fetch from. rename() within the same directory is atomic on the
 * filesystems Android uses. Failures are swallowed (callers can't do
 * anything useful with them) but logged — durable user data silently
 * failing to persist is otherwise undiagnosable in the field.
 */
internal fun atomicWrite(target: File, content: String) {
    val tmp = File(target.parentFile, target.name + DataCache.TEMP_SUFFIX)
    try {
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Rename-over-existing can fail on some filesystems; delete
            // the target only after the temp file write has succeeded so
            // we never lose existing data if the write itself fails.
            target.delete()
            if (!tmp.renameTo(target)) {
                // Both renames failed — fall back to direct write so
                // data isn't lost entirely. The temp file still holds
                // the content so the write is safe to attempt.
                target.writeText(content)
                runCatching { tmp.delete() }
                return
            }
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to write a data file: ${e.logSummary()}")
        runCatching { tmp.delete() }
    }
}
