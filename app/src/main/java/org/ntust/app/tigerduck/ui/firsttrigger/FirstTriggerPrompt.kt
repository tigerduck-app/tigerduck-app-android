package org.ntust.app.tigerduck.ui.firsttrigger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.preferences.FirstTriggerSeenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strongly-typed identifier for every one-shot "first time you did X, want us
 * to keep doing it?" prompt. Adding a prompt = add a case.
 *
 * [storageKey] is appended to a fixed `firstTriggerPromptSeen.` prefix in
 * [AppPreferences], so renaming it is a one-way migration: the new key reverts
 * to "unseen" for upgrading users. The slug mirrors the iOS rawValue so the two
 * platforms stay legible against each other even though storage is separate.
 */
enum class FirstTriggerPromptKey(val storageKey: String) {
    FLIP_TO_LIBRARY("flipToLibrary"),
}

/**
 * Process-wide holder for the single pending first-trigger prompt. The root
 * composable observes [pending] via `FirstTriggerPromptHost` and renders the
 * dialog; at most one prompt is visible at a time, and rapid
 * [requestIfFirstTime] calls past the first are dropped silently.
 *
 * Modeled on the iOS `FirstTriggerPromptCenter`. The "seen" flag is written in
 * [finish] on a Keep / Turn-off choice — never on display — so a prompt
 * dismissed by anything other than a deliberate choice re-arms on the next
 * trigger. Treating "shown" as "agreed" would arm a gesture without consent.
 */
@Singleton
class FirstTriggerPromptController @Inject constructor(
    private val prefs: FirstTriggerSeenStore,
) {
    data class Pending(
        val key: FirstTriggerPromptKey,
        val onAccept: () -> Unit,
        val onDecline: () -> Unit,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun hasSeen(key: FirstTriggerPromptKey): Boolean =
        prefs.hasSeenFirstTriggerPrompt(key.storageKey)

    /**
     * Queue [key]'s prompt iff it has not been seen and nothing else is pending.
     * Cheap to call from a hot path (e.g. a sensor callback): the [onAccept] /
     * [onDecline] actions are captured but only invoked once the user chooses.
     *
     * Today every caller dispatches on the main thread, but we still take a
     * monitor lock around the check-then-set so a future caller from an IO
     * coroutine can't silently overwrite an in-flight prompt's closures.
     */
    @Synchronized
    fun requestIfFirstTime(
        key: FirstTriggerPromptKey,
        onAccept: () -> Unit,
        onDecline: () -> Unit,
    ) {
        if (_pending.value != null || hasSeen(key)) return
        _pending.value = Pending(key, onAccept, onDecline)
    }

    /**
     * Invoked by the dialog's buttons. Marks the prompt seen and clears
     * [pending] before running the chosen action so any state the action
     * mutates settles against an already-dismissed prompt.
     */
    fun finish(accept: Boolean) {
        val p = _pending.value ?: return
        markSeen(p.key)
        _pending.value = null
        if (accept) p.onAccept() else p.onDecline()
    }

    fun markSeen(key: FirstTriggerPromptKey) {
        prefs.setFirstTriggerPromptSeen(key.storageKey, true)
    }

    /**
     * Re-arm a previously-seen prompt so the next [requestIfFirstTime] for the
     * same key surfaces it again. Used by the debug Triggers screen to re-test
     * the first-trigger experience.
     */
    fun reset(key: FirstTriggerPromptKey) {
        prefs.setFirstTriggerPromptSeen(key.storageKey, false)
    }
}
