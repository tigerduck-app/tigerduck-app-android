package org.ntust.app.tigerduck.push

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/**
 * A [SettingsDocumentTransport] that goes to the network for the first
 * [read] and answers every later one with what that read produced — the same
 * envelope, or the same failure thrown again.
 *
 * [NotificationSettingsSync.pullNow] wraps one of these around the real
 * transport for the span of a single pull. The two sections it applies,
 * `live_activity` and `assignments`, are independent functions that each
 * read the document they own a section of — which is the right shape for
 * them, but it means the *same* document was fetched twice per pull, now on
 * every successful full sync rather than only when a settings screen opens.
 * One request answers both.
 *
 * Nothing else about either section changes: each still makes no request at
 * all when its own gate is closed (whichever section reads first pays for
 * it), still asks its dirty check and its generation check after the read
 * has come back, and still degrades on its own terms.
 *
 * A failure is cached and rethrown rather than retried, so a pull cannot
 * turn into two requests on exactly the path where requests are failing;
 * both sections then report the same failure, which is what one shared read
 * means. [CancellationException] is never cached — it belongs to the
 * coroutine being cancelled, not to the document.
 */
internal class SingleReadTransport(
    private val delegate: SettingsDocumentTransport,
    /** The document is known to be missing, so the read answers as a 404 without going out. */
    private val knownMissing: Boolean = false,
    /** Runs when the read finds no document. */
    private val onMissing: () -> Unit = {},
) : SettingsDocumentTransport {
    private var answered = false
    private var envelope: SettingsDocumentEnvelope<JsonObject>? = null
    private var failure: Exception? = null

    override suspend fun read(): SettingsDocumentEnvelope<JsonObject>? {
        if (!answered) {
            try {
                if (!knownMissing) {
                    envelope = delegate.read()
                    if (envelope == null) onMissing()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            answered = true
        }
        failure?.let { throw it }
        return envelope
    }

    override suspend fun write(
        document: JsonObject,
        baseRevision: Long?,
        isCurrent: () -> Boolean,
    ): SettingsWriteResult<JsonObject> = delegate.write(document, baseRevision, isCurrent)
}
