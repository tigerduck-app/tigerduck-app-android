package org.ntust.app.tigerduck.auth

import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.shared.account.SignIn
import kotlin.coroutines.cancellation.CancellationException

/**
 * The sign-in with credentials the user typed, which [AuthService] runs as its login. Apart from
 * it so a test can run it: AuthService needs the Android Keystore.
 *
 * [schoolSignIn] posts the credentials to the school. Once the school takes them,
 * [signInInteractively] runs the rest as the sign-in under way, and the first thing it does is
 * [storeAccount]: the credentials become the stored account and the stored-password latch lifts.
 * For the same student nothing that can suspend comes between the school's answer and that store,
 * so a sign-in cancelled at any point leaves either the old password with its refusal latched or
 * the new one without it, never the refused password stored and unlatched: the school's acceptance
 * lifts nothing by itself (SsoLoginService.signIn).
 *
 * Another student stored is signed out in between ([leaveStoredAccount]), after the school
 * accepts, so a wrong password leaves that student signed in. The sign-out's wipe runs, this
 * sign-in waits for it, and goes on as a sign-in over nobody: as iOS (ADR 0026), the sign-in of
 * the student who left ends here, and nothing of theirs is left for the next one. The sign-out
 * clears the session cookies the post just got; the next fetch signs in again from the stored
 * credentials. Cancelled in that wait, it leaves nobody stored.
 *
 * The account then shows as signed in ([markSignedIn]). The library and the school mail are signed
 * in to by themselves: a school sign-in signs neither in (decided for Android on 2026-10-22). The
 * demo account ([isDemoAccount]) signs in on the device ([enterDemo]), stored the same way, with
 * nothing sent anywhere.
 *
 * A sign-out takes no sign-in lock, so it can land at any point, the moment the school answers
 * included. The store and the account showing as signed in each happen only while the [SignIn]
 * read before the sign-in began stands, asked under [accountLock], which the sign-out holds as it
 * ends that sign-in and again while it drops the stored account: a sign-in a sign-out overtook
 * stores nothing and shows nothing, and one that stored first is the student on the sign-out's
 * marker, its account dropped by the sign-out itself.
 */
internal class TypedAccountSignIn(
    private val schoolSignIn: suspend (studentId: String, password: String) -> Boolean,
    private val signInInteractively: suspend (
        signIn: suspend (SignIn) -> SignInAttempt,
    ) -> Boolean,
    /**
     * Returns once no sign-out is under way: a sign-in made during one would store an account its
     * wipe then takes. A cancelled wait leaves the next sign-in free.
     */
    private val awaitOpen: suspend () -> Unit,
    /** The session's sign-in as it is now, which a sign-out ends while it holds [accountLock]. */
    private val currentSignIn: () -> SignIn,
    /** Held by a sign-out as it ends the sign-in, and while it drops the stored account. */
    private val accountLock: Any,
    private val storedStudentId: () -> String?,
    private val writeAccount: (studentId: String, password: String) -> Unit,
    private val latch: StoredPasswordLatch,
    /** Signs the stored account out, as any sign-out does. Its wipe runs after this returns. */
    private val leaveStoredAccount: suspend () -> Unit,
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
     * account shows as signed in; [afterShown] runs the rest once it does, with the sign-in
     * read before the store and whether the account stored before was this one.
     */
    suspend fun signIn(
        studentId: String,
        password: String,
        prepareSignedIn: suspend () -> Unit,
        afterShown: suspend (SignIn, sameStudent: Boolean) -> SignInAttempt,
    ): Outcome {
        var started = signInOnceOpen()

        // A sign-out that overtook this sign-in has taken the account already, and the next check stops it.
        suspend fun overStoredAccount() {
            var another = false
            unlessSignedOutSince(started) { another = storedStudentId().let { it != null && it != studentId } }
            if (!another) return
            leaveStoredAccount()
            started = signInOnceOpen()
        }

        // Checked before anything goes out, so these credentials never reach NTUST.
        if (isDemoAccount(studentId, password)) {
            overStoredAccount()
            // Demo mode is on disk once it goes on.
            accountChanging()
            enterDemo()
            if (!unlessSignedOutSince(started) { storeAccount(studentId, password) }) {
                return Outcome.NOT_SIGNED_IN
            }
            prepareSignedIn()
            if (!unlessSignedOutSince(started, markSignedIn)) return Outcome.NOT_SIGNED_IN
            return Outcome.DEMO
        }
        if (!schoolSignIn(studentId, password)) return Outcome.NOT_SIGNED_IN
        overStoredAccount()
        var outcome = Outcome.NOT_SIGNED_IN
        signInInteractively { signIn ->
            // Read before it is overwritten: a stored Moodle token is this student's only if
            // the account stored was.
            val sameStudent = storedStudentId() == studentId
            val stored = unlessSignedOutSince(started) {
                accountChanging()
                storeAccount(studentId, password)
            }
            if (!stored) {
                return@signInInteractively SignInAttempt.NOTHING_TO_SEND
            }
            // Cancelled from here on, the sign-in still leaves the password the school took,
            // with no refusal of the old one held against it.
            prepareSignedIn()
            if (!unlessSignedOutSince(started, markSignedIn)) {
                return@signInInteractively SignInAttempt.NOTHING_TO_SEND
            }
            outcome = Outcome.SIGNED_IN
            afterShown(signIn, sameStudent)
        }
        return outcome
    }

    /**
     * The sign-in of a moment with no sign-out under way. One that landed before it is read has
     * ended it, and the session is then waited out again; one that lands after ends it later.
     */
    private suspend fun signInOnceOpen(): SignIn {
        while (true) {
            awaitOpen()
            val signIn = currentSignIn()
            if (signIn.isCurrent) return signIn
        }
    }

    /** Runs [block] under [accountLock] and says so, unless a sign-out has ended [signIn]. */
    private inline fun unlessSignedOutSince(signIn: SignIn, block: () -> Unit): Boolean =
        synchronized(accountLock) {
            if (!signIn.isCurrent) return false
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

/**
 * What [AuthService.login] does once the school took a typed sign-in, after
 * its backend login, which stored the session if it got one. Apart from
 * AuthService so a test can run it: AuthService needs the Android Keystore.
 *
 * [startSettingsCopy] is the catch-up the automatic sign-in runs too
 * (NotificationSettingsSync.pushIfUnconfirmed), and it goes first, without
 * suspending: it starts the copy of the notification settings a push tells
 * this phone's edits apart by. Only the backend login's own return to the
 * caller comes between the session being stored and this call. [register],
 * the push registration, is a network call, and while it ran first an edit
 * made in its wait found no copy: its push counted nothing as changed, sent
 * nothing, and confirmed the edit as if it had gone up. iOS starts the copy
 * first too, in onV3SignedIn.
 */
internal suspend fun followTypedSignIn(startSettingsCopy: () -> Unit, register: suspend () -> Unit) {
    startSettingsCopy()
    runCatching { register() }
        .onFailure { e -> if (e is CancellationException) throw e }
}
