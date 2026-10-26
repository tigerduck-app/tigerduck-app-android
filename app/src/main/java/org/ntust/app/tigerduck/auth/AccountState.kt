package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the school account is signed in, and how many times that has changed since the process
 * started. A watcher that sees it late can tell a sign-out and a sign-in in between, two changes
 * to the same value, from nothing having happened.
 */
data class AccountState(val signedIn: Boolean, val changes: Long)

/** Whether the school account is signed in, as a plain flag ([signedIn]) and counted ([counted]). */
class SignedInState(initial: Boolean) {
    private val flag = MutableStateFlow(initial)
    private val count = MutableStateFlow(AccountState(initial, changes = 0))

    val signedIn: StateFlow<Boolean> = flag
    val counted: StateFlow<AccountState> = count

    /**
     * The one way either changes: the flag first, so what follows a counted change, such as a
     * sign-out's stopping the Live Update, reads the account as it now is.
     */
    fun set(signedIn: Boolean) = synchronized(count) {
        if (flag.value == signedIn) return@synchronized
        flag.value = signedIn
        count.value = AccountState(signedIn, count.value.changes + 1)
    }
}
