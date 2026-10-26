package org.ntust.app.tigerduck.network.tls

import android.util.AtomicFile
import org.ntust.app.tigerduck.shared.tls.PinDocument
import org.ntust.app.tigerduck.shared.tls.PinDocumentResult
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import java.io.File

/**
 * The pin document's envelope as it was received (TLS pins spec, A5), in a file outside the
 * cache and the user-data folders a full reset empties. Every load verifies it again, so a file
 * changed on disk or signed by a retired key counts as no document. One past its expiry still
 * loads, so its serial keeps an older document out; [PinPolicy][org.ntust.app.tigerduck.shared.tls.PinPolicy]
 * applies none past its expiry.
 */
class PinDocumentStore(
    file: File,
    private val verifier: PinDocumentVerifier,
) {
    private val atomic = AtomicFile(file)

    /** The stored document if it still verifies, or null. */
    fun load(): PinDocument? {
        val bytes = runCatching { atomic.readFully() }.getOrNull() ?: return null
        return (verifier.verify(bytes, allowExpired = true) as? PinDocumentResult.Verified)?.document
    }

    fun save(document: PinDocument) {
        atomic.baseFile.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(document.envelope)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
