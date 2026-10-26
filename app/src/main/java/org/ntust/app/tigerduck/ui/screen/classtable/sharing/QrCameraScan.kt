package org.ntust.app.tigerduck.ui.screen.classtable.sharing

/**
 * Lets a code through once while it stays in view. A camera reads a code on every frame that
 * shows it, and without this a code still in front of it would open its confirm step again the
 * moment it was cancelled. The iOS app's `RepeatedCodeFilter`.
 *
 * A code [release]d passes again at the next frame: one the import answered it could not take
 * yet, as while the Enter link dialog or another code's step was open
 * ([ClassTableImportModel.ScanAnswer.BUSY]), which would otherwise stay held for as long as it
 * stayed in view, and scan only once moved out of it. Called from the camera's thread and the
 * main thread.
 */
class RepeatedQrCodeFilter {
    private val lastSeen = mutableMapOf<String, Long>()

    /** Whether [text], read at [nowMs], is new: unseen, or out of view for longer than [GAP_MS]. */
    @Synchronized
    fun isNew(text: String, nowMs: Long): Boolean {
        val seen = lastSeen[text]
        lastSeen.entries.removeAll { nowMs - it.value > GAP_MS }
        lastSeen[text] = nowMs
        return seen == null || nowMs - seen > GAP_MS
    }

    /** [text] is new again at the next frame that shows it. */
    @Synchronized
    fun release(text: String) {
        lastSeen.remove(text)
    }

    companion object {
        /** Longer than the time between two frames that show the same code. */
        const val GAP_MS = 1_000L
    }
}

/**
 * Hands the codes the camera reads ([read], on its thread) to the import through [post], on the
 * main thread where [take] answers, and has [filter] let go of each the import could not take
 * yet. Nothing more is handed over once [close]d, including what was posted before.
 */
class QrCodeHandOff(
    private val filter: RepeatedQrCodeFilter,
    private val post: (Runnable) -> Unit,
    private val take: (String) -> ClassTableImportModel.ScanAnswer,
) {
    @Volatile
    private var closed = false

    fun read(text: String, nowMs: Long) {
        if (closed || !filter.isNew(text, nowMs)) return
        post(
            Runnable {
                if (closed) return@Runnable
                if (take(text) == ClassTableImportModel.ScanAnswer.BUSY) filter.release(text)
            },
        )
    }

    /** The camera is let go: a frame still being read, or one posted, reaches no one. */
    fun close() {
        closed = true
    }
}

/**
 * A camera bound for as long as the import page is there. The camera provider answers some time
 * after the page asks, and the page can have left by then: binding the camera at that point
 * would keep it running over the class table, and start it again on every return to the app.
 * Called on the main thread only.
 */
class CameraSession {
    private var disposed = false
    private var unbind: (() -> Unit)? = null

    /** Until the page leaves. */
    val isActive: Boolean get() = !disposed

    /** Binds with [bind], which returns how to let go, unless the page has left; says whether it bound. */
    fun start(bind: () -> (() -> Unit)): Boolean {
        if (disposed) return false
        unbind = bind()
        return true
    }

    /** The page left: lets go of what was bound, and binds nothing from now on. */
    fun dispose() {
        disposed = true
        unbind?.invoke()
        unbind = null
    }
}
