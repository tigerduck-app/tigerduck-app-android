package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether an account is signed in, and the one way that changes: together with telling of it, in
 * one hold, so two threads changing it tell in the order they changed it, and a reactor left at
 * the end of the stream reads what the state reads. Nothing that can wait on a sign-out runs in
 * the hold, and a reactor takes no lock it would wait on.
 */
class SignedInState(initial: Boolean) {
    private val flag = MutableStateFlow(initial)

    val signedIn: StateFlow<Boolean> = flag.asStateFlow()

    /**
     * Moves the flag, unless [stands] says this change no longer may, and calls [told] in the same
     * hold, with whether the flag moved. The flag first, so what follows a change reads the
     * account as it now is. Says whether it stood.
     */
    fun set(signedIn: Boolean, stands: () -> Boolean = { true }, told: (changed: Boolean) -> Unit = {}): Boolean =
        synchronized(flag) {
            if (!stands()) return@synchronized false
            val changed = flag.value != signedIn
            flag.value = signedIn
            told(changed)
            true
        }
}
