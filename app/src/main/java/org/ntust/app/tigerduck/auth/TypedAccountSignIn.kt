package org.ntust.app.tigerduck.auth

import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt

/**
 * The sign-in with credentials the user typed, which [AuthService] runs as its login. Apart from
 * it so a test can run it: AuthService needs the Android Keystore.
 *
 * [schoolSignIn] posts the credentials to the school. Once the school takes them,
 * [signInInteractively] runs the rest as the sign-in under way, and the first thing it does is
 * [storeAccount]: the credentials become the stored account and the stored-password latch lifts.
 * Nothing that can suspend comes between the school's answer and that store, so a sign-in
 * cancelled at any point leaves either the old password with its refusal latched or the new one
 * without it, never the refused password stored and unlatched: the school's acceptance lifts
 * nothing by itself (SsoLoginService.signIn).
 *
 * The account then shows as signed in ([markSignedIn]). The library and the school mail are signed
 * in to by themselves: a school sign-in signs neither in (decided for Android on 2026-10-22). The
 * demo account ([isDemoAccount]) signs in on the device ([enterDemo]), stored the same way, with
 * nothing sent anywhere.
 *
 * A sign-out takes no sign-in lock, so it can land at any point, the moment the school answers
 * included. The store and the account showing as signed in each happen only while
 * [signInGeneration] is still the one read before the sign-in began, checked under [accountLock],
 * which the sign-out holds while it drops the stored account and moves the generation: a sign-in
 * a sign-out overtook stores nothing and shows nothing, and one it overtakes after the store has
 * its account dropped by the sign-out itself.
 */
internal class TypedAccountSignIn(
    private val schoolSignIn: suspend (studentId: String, password: String) -> Boolean,
    private val signInInteractively: suspend (
        signIn: suspend (signInGeneration: Int) -> SignInAttempt,
    ) -> Boolean,
    /** [AuthTokenManager.signInGeneration], which a sign-out moves while it holds [accountLock]. */
    private val signInGeneration: () -> Int,
    /** Held by a sign-out while it drops the stored account and moves [signInGeneration]. */
    private val accountLock: Any,
    private val storedStudentId: () -> String?,
    private val writeAccount: (studentId: String, password: String) -> Unit,
    private val latch: StoredPasswordLatch,
    /** Whether these are the demo account's credentials. Reads the bundled file and nothing else. */
    private val isDemoAccount: (studentId: String, password: String) -> Boolean,
    /** Signs the demo account in on the device. */
    private val enterDemo: suspend () -> Unit,
    /**
     * Writes, before anything of the new account reaches disk, what a process death right after
     * must not lose: the in-app browser's clearing, which the account change then owes.
     */
    private val accountChanging: () -> Unit,
    /** Shows the account as signed in. */
    private val markSignedIn: () -> Unit,
) {
    enum class Outcome {
        /** The school did not take the credentials, or the account signed out meanwhile. */
        NOT_SIGNED_IN,

        /** Signed in with the school. */
        SIGNED_IN,

        /** The demo account, signed in on the device. */
        DEMO,
    }

    /**
     * Signs in as [studentId] with [password]. [prepareSignedIn] runs what must hold before the
     * account shows as signed in; [afterShown] runs the rest once it does, with the generation
     * read before the store and whether the account stored before was this one.
     */
    suspend fun signIn(
        studentId: String,
        password: String,
        prepareSignedIn: suspend () -> Unit,
        afterShown: suspend (signInGeneration: Int, sameStudent: Boolean) -> SignInAttempt,
    ): Outcome {
        val generation = signInGeneration()
        // Checked before anything goes out, so these credentials never reach NTUST.
        if (isDemoAccount(studentId, password)) {
            // Demo mode is on disk once it goes on.
            accountChanging()
            enterDemo()
            if (!unlessSignedOutSince(generation) { storeAccount(studentId, password) }) {
                return Outcome.NOT_SIGNED_IN
            }
            prepareSignedIn()
            if (!unlessSignedOutSince(generation, markSignedIn)) return Outcome.NOT_SIGNED_IN
            return Outcome.DEMO
        }
        if (!schoolSignIn(studentId, password)) return Outcome.NOT_SIGNED_IN
        var outcome = Outcome.NOT_SIGNED_IN
        signInInteractively { signIn ->
            // Read before it is overwritten: a stored Moodle token is this student's only if
            // the account stored was.
            val sameStudent = storedStudentId() == studentId
            val stored = unlessSignedOutSince(generation) {
                accountChanging()
                storeAccount(studentId, password)
            }
            if (!stored) {
                return@signInInteractively SignInAttempt.NOTHING_TO_SEND
            }
            // Cancelled from here on, the sign-in still leaves the password the school took,
            // with no refusal of the old one held against it.
            prepareSignedIn()
            if (!unlessSignedOutSince(generation, markSignedIn)) {
                return@signInInteractively SignInAttempt.NOTHING_TO_SEND
            }
            outcome = Outcome.SIGNED_IN
            afterShown(signIn, sameStudent)
        }
        return outcome
    }

    /** Runs [block] under [accountLock] and says so, unless a sign-out has moved [generation]. */
    private inline fun unlessSignedOutSince(generation: Int, block: () -> Unit): Boolean =
        synchronized(accountLock) {
            if (signInGeneration() != generation) return false
            block()
            true
        }

    /**
     * Keeps [studentId] and [password] as the stored account and lifts the stored-password latch,
     * as one step: a refusal it held was of the password before.
     */
    private fun storeAccount(studentId: String, password: String) {
        writeAccount(studentId, password)
        latch.clear()
    }
}
