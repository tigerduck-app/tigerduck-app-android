package org.ntust.app.tigerduck.data.preferences

import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * One preference of an account. A write takes the sign-in it was read for, so one made after a
 * sign-out names the sign-in that ended and is dropped, atomic with [SignIn.ifCurrent]: it lands
 * before the sign-out clears the key, or not at all. A key the sign-out empties with [clear] holds
 * no sign-in there: the one it would name has ended by then.
 */
class AccountKey<T>(
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val empty: T,
) {
    fun get(): T = read()

    /** False, and nothing written, once [signIn] has ended. */
    fun set(value: T, signIn: SignIn): Boolean = signIn.ifCurrent { write(value) } != null

    /** The sign-out's. */
    fun clear() = write(empty)
}
