package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule

/**
 * A [StoredPasswordLatch] in memory, for tests that need no persisted one. Its clock stands
 * still, so a refusal's wait never ends.
 */
internal class InMemoryStoredPasswordLatch : StoredPasswordLatch {
    private val state = MutableStateFlow<StoredPasswordLatch.Rejection?>(null)
    override val rejection: StateFlow<StoredPasswordLatch.Rejection?> = state.asStateFlow()

    private val count = MutableStateFlow(0)
    override val refusals: StateFlow<Int> = count.asStateFlow()

    private val changed = MutableStateFlow(0)
    override val changes: StateFlow<Int> = changed.asStateFlow()

    override fun retryAt(): Long? = StoredPasswordSchedule.retryAt(count.value, NOW, NOW)

    override fun postMustWait() = count.value > 0

    override var generation = 0
        private set

    override val automaticPosts = Mutex()

    override fun markRejected(generation: Int, studentId: String, password: String, reason: String?) {
        if (generation != this.generation) return
        if (state.value == null) state.value = StoredPasswordLatch.Rejection(reason)
        count.value++
        changed.value++
    }

    override fun markAccepted(generation: Int, studentId: String, password: String) {
        if (generation != this.generation) return
        state.value = null
        count.value = 0
        changed.value++
    }

    override fun settle(generation: Int, changesSeen: Int, studentId: String, password: String) {
        if (changed.value != changesSeen) return
        markAccepted(generation, studentId, password)
    }

    /** It keeps no account, so any one counts as the stored one. */
    override fun isStoredAccount(studentId: String, password: String) = true

    override fun clear() {
        generation++
        state.value = null
        count.value = 0
        changed.value++
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
