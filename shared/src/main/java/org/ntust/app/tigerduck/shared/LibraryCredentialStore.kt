package org.ntust.app.tigerduck.shared

/**
 * Backing store for NTUST library credentials and the short-lived token issued
 * by `api.lib.ntust.edu.tw`. The phone keeps these in EncryptedSharedPreferences;
 * the watch keeps them in a DataStore that mirrors the phone via the Wearable
 * Data Layer. Both sides plug into [LibraryService] through this interface so
 * the request/response wire schema is defined exactly once.
 *
 * Read-only here: a sign-in stores its account through [saveLibrarySignIn],
 * and each store's own sign-out removes it, so nothing writes around the
 * [LibraryAccountGate] that keeps the two apart.
 */
interface LibraryCredentialStore {
    val libraryUsername: String?
    val libraryPassword: String?
    val libraryToken: String?
    val libraryTokenExpiry: Long
    val isLibraryTokenValid: Boolean

    /**
     * Moved by a sign-out of the library account and by another account being
     * stored. A sign-in reads it before its request and passes it to
     * [saveLibrarySignIn].
     */
    val libraryAccountGeneration: Int

    /**
     * Stores a sign-in's account and token, and says whether it did. Nothing is
     * stored once the account has signed out since [generation] was read; a
     * sign-in the app makes on its own ([byUser] false) also yields to another
     * account stored meanwhile, while one the user asked for replaces it.
     * Nothing is stored either while [stillCurrent] is false, for a caller
     * whose own account, such as the school one it signs in with, changed
     * meanwhile. Atomic with a sign-out, so one cannot fall between the check
     * and the writes.
     */
    fun saveLibrarySignIn(
        generation: Int,
        username: String,
        password: String,
        token: String?,
        tokenExpiry: Long,
        byUser: Boolean,
        stillCurrent: () -> Boolean = { true },
    ): Boolean

    /**
     * How many times in a row the library refused the stored password in a
     * sign-in the app made on its own; 0 with none. Each refusal counts toward
     * the account's lockout, so [LibraryService.ensureToken] waits
     * [StoredPasswordSchedule.delayMs] after the last, [libraryPasswordRefusedAt],
     * before it sends that password again. Kept across launches, and cleared by
     * a sign-in that saves credentials and by a sign-out. A store that does not
     * keep it reads 0.
     */
    val libraryPasswordRefusals: Int get() = 0

    /** Wall-clock ms of the last of [libraryPasswordRefusals]; 0 with none. */
    val libraryPasswordRefusedAt: Long get() = 0L

    /** Whether the library refused the stored password, and has not taken it since. */
    val libraryPasswordRefused: Boolean get() = libraryPasswordRefusals > 0

    /**
     * Counts a refusal of [password] [at] that time, if it is still the stored
     * one and the account has not changed since [generation] was read; says
     * whether it did.
     */
    fun refuseLibraryPassword(generation: Int, password: String, at: Long): Boolean = false
}

/**
 * The lock and counters behind [LibraryCredentialStore.libraryAccountGeneration]:
 * a store runs its sign-outs, the accounts handed to it and its saves of a
 * sign-in through one of these.
 */
class LibraryAccountGate {
    private var generation = 0

    // The generation the last sign-out moved to.
    private var signedOutAt = 0

    val current: Int get() = synchronized(this) { generation }

    /** A sign-out: [remove] runs with the generation already moved. */
    fun <T> signOut(remove: () -> T): T = synchronized(this) {
        generation++
        signedOutAt = generation
        remove()
    }

    /** Runs [block] atomic with sign-outs, the accounts handed over and saves. */
    fun <T> atomically(block: () -> T): T = synchronized(this) { block() }

    /** Runs [block] while the generation is still [read], atomic with sign-outs and saves; null once it moved. */
    fun <T> whileCurrent(read: Int, block: () -> T): T? = synchronized(this) {
        if (generation == read) block() else null
    }

    /** Another account stored other than by a sign-in, as the phone hands one to the watch. */
    fun <T> replaceAccount(store: () -> T): T = synchronized(this) {
        generation++
        store()
    }

    /**
     * Runs [save], which stores [username]'s sign-in, unless the account has
     * signed out since [read] was read, or, unless [byUser], another account
     * was stored since, or [stillCurrent] is false; says whether it did.
     * Replacing [storedUsername]'s account with another moves the generation.
     * Student IDs are compared as [sameStudentId] does.
     */
    fun saveIfCurrent(
        read: Int,
        byUser: Boolean,
        storedUsername: () -> String?,
        username: String,
        stillCurrent: () -> Boolean = { true },
        save: () -> Unit,
    ): Boolean = synchronized(this) {
        val refused = if (byUser) signedOutAt > read else generation != read
        if (refused || !stillCurrent()) return false
        if (!sameStudentId(storedUsername(), username)) generation++
        save()
        true
    }
}
