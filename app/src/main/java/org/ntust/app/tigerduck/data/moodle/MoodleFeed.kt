package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Type

/** Where a course's Moodle data is cached. [org.ntust.app.tigerduck.data.cache.DataCache] in the app. */
interface MoodleCachePort {
    suspend fun <T> load(type: Type, name: String): Pair<T, Long>?
    suspend fun save(value: Any, fetchedAtMs: Long, name: String)
}

/** Where a course's Moodle data is cached, and whether the sign-in it is fetched under still holds. */
class MoodleCacheContext(
    val account: String,
    val cache: MoodleCachePort,
    /** False once the account signs out, after which nothing is written or shown. */
    val isCurrent: () -> Boolean,
    /** Real time, not the debug clock: the age of fetched data is real. */
    val now: () -> Long = System::currentTimeMillis,
)

/**
 * One kind of a course's Moodle data as its screen shows it: the cached copy as soon as it is
 * read, then each fetch in place, with the fetch's progress and outcome. Ported from iOS
 * `MoodleFeed`.
 */
class MoodleFeed<T : Any>(
    private val name: String,
    private val type: Type,
    /** How long a copy counts as fresh; the screen's refresh timer fetches it again once older. */
    val ttlMs: Long,
    private val context: MoodleCacheContext,
    private val scope: CoroutineScope,
) {
    data class State<T>(
        val value: T? = null,
        val fetchedAtMs: Long? = null,
        val isRefreshing: Boolean = false,
        /** The last fetch's error, cleared by the next success. A copy already shown stays. */
        val failure: Throwable? = null,
        /** Whether the cached copy, if any, has been read; a screen waits for it before fetching. */
        val cacheLoaded: Boolean = false,
    ) {
        /** The last fetch's error while a copy shows, which stays: shown beside it with Retry. */
        val failureBesideCopy: Throwable? get() = failure.takeIf { !isRefreshing && value != null }
    }

    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state.asStateFlow()

    val value: T? get() = _state.value.value

    private val cacheRead: Deferred<Unit> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        val cached = runCatching { context.cache.load<T>(type, name) }.getOrNull()
        _state.update { current ->
            if (current.value == null && cached != null && context.isCurrent()) {
                current.copy(value = cached.first, fetchedAtMs = cached.second, cacheLoaded = true)
            } else {
                current.copy(cacheLoaded = true)
            }
        }
    }

    /** Returns once the cached copy, if any, is on [state]. */
    suspend fun awaitCache() {
        cacheRead.await()
    }

    private val lock = Mutex()
    private var running: Deferred<Unit>? = null

    /**
     * Fetches through [fetch] and keeps the answer. A refresh already running is joined, not
     * repeated, so a pull during a timed refresh waits for that one; without [joinRunning] a
     * fetch of its own is queued behind the one running instead, for a refresh that must see
     * what changed after the running one's request went out. The fetch runs in the store's scope:
     * a screen that goes away does not cancel it, and its answer is cached for the next.
     */
    suspend fun refresh(joinRunning: Boolean = true, fetch: suspend () -> T) {
        val job = lock.withLock {
            val current = running?.takeIf { it.isActive }
            when {
                current == null -> scope.async { run(fetch) }
                joinRunning -> current
                else -> scope.async {
                    current.join()
                    run(fetch)
                }
            }.also { running = it }
        }
        job.await()
    }

    /**
     * Changes made here while a fetch runs, to apply again on its answer, which was read from
     * Moodle before them; null while none runs.
     */
    private var changesDuringFetch: MutableList<(T) -> T>? = null

    /**
     * Applies a change made here, such as a discussion read, keeping the time of the fetch the
     * value came from. A fetch under way applies it again to its answer, so the answer does not
     * undo it.
     */
    fun update(change: (T) -> T) {
        if (!context.isCurrent()) return
        // Every write of the value takes this lock, so a fetch's answer and a change made here
        // cannot pass each other.
        val saved = synchronized(this) {
            changesDuringFetch?.add(change)
            val current = _state.value
            val value = current.value ?: return
            val fetchedAt = current.fetchedAtMs ?: return
            val changed = change(value)
            _state.update { it.copy(value = changed) }
            changed to fetchedAt
        }
        scope.launch { context.cache.save(saved.first, saved.second, name) }
    }

    /**
     * Marks the value out of date, here and in the cache, so the next screen that shows it fetches
     * it again at once; for a change made elsewhere that this copy does not show, such as the
     * account's new picture changed from another course.
     */
    fun expire() {
        if (!context.isCurrent()) return
        val value = synchronized(this) {
            val current = _state.value
            val value = current.value ?: return
            if (current.fetchedAtMs == null) return
            _state.update { it.copy(fetchedAtMs = 0L) }
            value
        }
        scope.launch { context.cache.save(value, 0L, name) }
    }

    private suspend fun run(fetch: suspend () -> T) {
        awaitCache()
        _state.update { it.copy(isRefreshing = true) }
        synchronized(this) { changesDuringFetch = mutableListOf() }
        try {
            val answer = fetch()
            if (!context.isCurrent()) return
            val now = context.now()
            val fresh = synchronized(this) {
                val changes = changesDuringFetch.orEmpty()
                changesDuringFetch = null
                changes.fold(answer) { value, change -> change(value) }
                    .also { kept -> _state.update { it.copy(value = kept, fetchedAtMs = now, failure = null) } }
            }
            context.cache.save(fresh, now, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (context.isCurrent()) _state.update { it.copy(failure = e) }
        } finally {
            synchronized(this) { changesDuringFetch = null }
            _state.update { it.copy(isRefreshing = false) }
        }
    }
}

/**
 * How long each kind of a course's Moodle data counts as fresh. A screen showing it fetches it
 * again once it is older: when the screen appears, when the app comes back to the foreground, and
 * on its own while the screen stays up. A pull always fetches.
 */
object MoodleRefreshInterval {
    const val FORUMS_MS = 5 * 60_000L
    const val ANNOUNCEMENTS_MS = 5 * 60_000L
    const val MATERIALS_MS = 5 * 60_000L
    const val GRADES_MS = 5 * 60_000L
    const val SUMMARY_MS = 5 * 60_000L
    const val MEMBERS_MS = 30 * 60_000L
}

object MoodleRefreshSchedule {
    /**
     * Milliseconds until the next refresh, 0 when it is due, never more than [ttlMs]. Data stamped
     * ahead of the clock, left by a clock set back, counts as old, but the last try still holds
     * the next one back, so a failing fetch is not repeated at once.
     */
    fun waitMs(fetchedAtMs: Long?, lastTryMs: Long?, ttlMs: Long, nowMs: Long): Long {
        val fetched = fetchedAtMs?.takeIf { it <= nowMs }
        val last = listOfNotNull(fetched, lastTryMs).maxOrNull() ?: return 0
        return (ttlMs - (nowMs - last)).coerceIn(0, ttlMs)
    }
}
