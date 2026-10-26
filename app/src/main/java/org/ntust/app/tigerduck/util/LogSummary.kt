package org.ntust.app.tigerduck.util

import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.push.PushApiException

/**
 * How a Log.w or Log.e line names a failure: by the exception's class, with
 * the HTTP status when the exception carries one. Release builds keep those
 * lines in logcat, which adb and bug reports read, and a message can quote a
 * server's reply, a course number or a student ID.
 */
fun Throwable.logSummary(): String {
    val status = when (this) {
        is PushApiException -> statusCode
        is MoodleWebserviceError.HttpStatus -> code
        else -> null
    }
    val name = javaClass.simpleName
    return if (status == null) name else "$name (HTTP $status)"
}
