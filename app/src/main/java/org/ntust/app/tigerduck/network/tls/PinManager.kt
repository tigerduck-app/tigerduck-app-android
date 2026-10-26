package org.ntust.app.tigerduck.network.tls

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.shared.tls.PinDocument
import org.ntust.app.tigerduck.shared.tls.PinDocumentKeys
import org.ntust.app.tigerduck.shared.tls.PinDocumentResult
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import org.ntust.app.tigerduck.shared.tls.PinMismatchResolver
import org.ntust.app.tigerduck.shared.tls.PinPolicy
import org.ntust.app.tigerduck.shared.tls.PinState
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's TLS pins (TLS pins spec, A4 to A6): the [state] every connection reads, the
 * TigerSync document it fetches and keeps, the student's acceptances, and what a mismatch leads
 * to. On a mismatch it refreshes the document, at most once per set per launch, past every
 * cache; a chain the new document allows is retried silently. Otherwise, for a chain anchored in
 * the document's `prompt_roots` (D18), it queues a prompt, which a foreground request waits for
 * and a background one doesn't (D5). Any other mismatch fails with no prompt, logged.
 */
@Singleton
class PinManager internal constructor(
    private val documents: PinDocumentStore,
    private val acceptances: PinAcceptanceStore,
    private val client: PinDocumentSource,
    private val verifier: PinDocumentVerifier,
    versionCode: Long,
    private val clock: () -> Long,
    private val isForeground: () -> Boolean,
    private val executor: Executor,
) : PinMismatchResolver {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.applicationContext,
        PinDocumentVerifier(PinDocumentKeys.keys),
    )

    private constructor(context: Context, verifier: PinDocumentVerifier) : this(
        documents = PinDocumentStore(File(context.noBackupFilesDir, "tls/pin-document.json"), verifier),
        acceptances = PinAcceptanceStore(
            context.getSharedPreferences(PinAcceptanceStore.FILE, Context.MODE_PRIVATE),
            BuildConfig.VERSION_CODE.toLong(),
        ),
        client = PinDocumentClient(),
        verifier = verifier,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        clock = System::currentTimeMillis,
        isForeground = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "tls-pins").apply { isDaemon = true } },
    )

    /** The pins every connection reads. */
    val state = PinState(PinPolicy(document = documents.load(), acceptances = acceptances.all, appVersion = versionCode))

    val prompts = PinMismatchPrompts(acceptances, clock, ::accept) { mismatch ->
        // Asked again when shown and when answered: the document that made it eligible (D18)
        // may have expired, or been replaced by one without its root, since it was queued.
        state.policy.mayPrompt(mismatch, Instant.ofEpochMilli(clock()))
    }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** A new document or a new acceptance: what the watch holds is out of date (A8). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val _accepted = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The set a Continue was just made for, so features can refresh as after a network error. */
    val accepted: SharedFlow<String> = _accepted.asSharedFlow()

    /** The document's envelope as received, for the watch, or null while none is held. */
    val envelope: ByteArray? get() = state.policy.document?.envelope

    /** The acceptances in force, for the watch. */
    val currentAcceptances get() = acceptances.all

    private val lastFetchAt = AtomicLong(0)
    private val mismatchRefreshes = ConcurrentHashMap<String, FutureTask<Boolean>>()

    /**
     * Fetches the document and takes it if it verifies and supersedes the one held (A5).
     * True when a new document came into force.
     */
    fun refresh(fresh: Boolean = false): Boolean {
        lastFetchAt.set(clock())
        val bytes = client.fetch(fresh) ?: return false
        val document = (verifier.verify(bytes) as? PinDocumentResult.Verified)?.document ?: return false
        synchronized(this) {
            val held = state.policy.document
            if (held != null && held.envelope.contentEquals(document.envelope)) return false
            if (!PinDocumentVerifier.supersedes(document, held)) return false
            runCatching { documents.save(document) }.onFailure { Log.w(TAG, "pin document not saved: ${it.javaClass.simpleName}") }
            state.update { it.with(document = document) }
        }
        // A prompt waiting may have lost the root that made it eligible.
        prompts.revalidate()
        _changes.tryEmit(Unit)
        return true
    }

    /** At app start and while in use, at most every 6 hours (A5), off the calling thread. */
    fun refreshIfDue() {
        val last = lastFetchAt.get()
        if (last != 0L && clock() - last in 0 until REFRESH_INTERVAL_MS) return
        // Marked at once, so a check meanwhile starts no second fetch.
        if (!lastFetchAt.compareAndSet(last, clock())) return
        executor.execute { refresh() }
    }

    /**
     * While the app is in use (A5): the document fetched on coming to the foreground if it is
     * due, then each time it falls due, every 6 hours, until leaving the foreground cancels this.
     */
    suspend fun keepFresh() {
        while (true) {
            refreshIfDue()
            val since = clock() - lastFetchAt.get()
            delay((REFRESH_INTERVAL_MS - since).coerceIn(MIN_RECHECK_MS, REFRESH_INTERVAL_MS))
        }
    }

    /** What [org.ntust.app.tigerduck.shared.tls.PinnedTrustManager] reports, from the handshake's thread. */
    fun onMismatch(mismatch: PinMismatch) {
        executor.execute { settle(mismatch) }
    }

    /**
     * A foreground request waits for the student's answer, but no longer than its call lives:
     * once the call is given up, by its own timeout or its caller, it stops waiting and fails. A
     * Continue made after that still holds for the requests that follow.
     */
    override fun mayRetry(mismatch: PinMismatch, isCanceled: () -> Boolean): Boolean {
        if (settle(mismatch, isCanceled)) return true
        if (!isForeground() || isCanceled()) return false
        return prompts.awaitAnswer(mismatch.setId, ANSWER_WAIT_MS, isCanceled) && !isCanceled() && allows(mismatch)
    }

    /**
     * Refreshes the document once per set per launch, then decides (A4): true when the pins now
     * allow the chain; otherwise a prompt is queued for a chain the student may be offered. A call
     * given up stops waiting for the refresh, which goes on for the calls after it, and the
     * handshake's own [onMismatch] still settles.
     */
    private fun settle(mismatch: PinMismatch, isCanceled: () -> Boolean = { false }): Boolean {
        val refresh = mismatchRefreshes.computeIfAbsent(mismatch.setId) { _ ->
            FutureTask { refresh(fresh = true) }.also(executor::execute)
        }
        if (!awaitRefresh(refresh, isCanceled)) return false
        if (allows(mismatch)) return true
        if (state.policy.mayPrompt(mismatch, Instant.ofEpochMilli(clock()))) {
            prompts.queue(mismatch)
        } else {
            Log.w(TAG, "pin mismatch with no prompt: set ${mismatch.setId}, host ${mismatch.host}")
        }
        return false
    }

    /** Waits for [refresh] up to [REFRESH_WAIT_MS], in slices; false once the call is given up. */
    private fun awaitRefresh(refresh: FutureTask<Boolean>, isCanceled: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REFRESH_WAIT_MS)
        while (!refresh.isDone) {
            if (isCanceled()) return false
            val left = deadline - System.nanoTime()
            if (left <= 0) return true
            try {
                refresh.get(minOf(left, TimeUnit.MILLISECONDS.toNanos(WAIT_SLICE_MS)), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Not fetched yet: look at the call again.
            } catch (_: Exception) {
                return true
            }
        }
        return true
    }

    private fun allows(mismatch: PinMismatch) = state.policy.allows(mismatch, Instant.ofEpochMilli(clock()))

    /** Continue: the chain's leaf and intermediates are trusted for its set until the build changes. */
    private fun accept(mismatch: PinMismatch) {
        val all = acceptances.accept(mismatch.setId, mismatch.acceptableSpkis, clock())
        state.update { it.with(acceptances = all) }
        _changes.tryEmit(Unit)
        _accepted.tryEmit(mismatch.setId)
    }

    /** Debug builds only: [setId]'s pins become one no chain has, so the prompt can be seen. */
    fun simulateMismatch(setId: String?) {
        if (!BuildConfig.DEBUG) return
        state.update { it.with(simulatedMismatch = setId) }
    }

    /** The document in force, for tests and the developer page. */
    val document: PinDocument? get() = state.policy.document

    private companion object {
        const val TAG = "TlsPins"
        const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** The least a wait for the next fetch lasts, should the clock jump. */
        const val MIN_RECHECK_MS = 60_000L

        /** The fetch's own timeout is 10 s. */
        const val REFRESH_WAIT_MS = 12_000L

        /** How often a wait looks at its call again. */
        const val WAIT_SLICE_MS = 250L

        /** How long a foreground request waits for the student's answer. */
        const val ANSWER_WAIT_MS = 120_000L
    }
}
