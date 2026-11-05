package org.ntust.app.tigerduck.auth.session

/**
 * A store of one account's data, which that account's sign-out clears at once, in the order its
 * list gives ([SchoolStores.atOnce]). Cleared by name, from a static list rather than as it
 * registers itself, so a store this process never built is cleared too (refactor-v4 D6).
 */
interface AccountScoped {
    fun forgetAccount()
}

/**
 * [AccountScoped], for a store whose clearing suspends: the sign-out runs it after the account
 * shows as signed out, on the application scope ([SchoolStores.late]).
 */
interface LateAccountScoped {
    suspend fun forgetAccount()
}
