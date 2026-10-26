package org.ntust.app.tigerduck.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school refused the stored password in a sign-in the user did not start. Each refusal counts
 * toward the account's lockout, which keeps the student out of every school system, so the next
 * automatic post of that password waits [StoredPasswordSchedule.delayMs] after the last refusal:
 * not the SSO sign-ins the pages and the background sync make, and not the Moodle token harvest.
 * [StoredPasswordRetry] tries it again when the wait is over. A post the school takes ends the
 * count, as a sign-in by hand that stores the password the school took does, which is how a
 * changed password gets in, and a sign-out. The iPhone app keeps the same count
 * (`SSOLoginService.storedPasswordRefusals`, tigerduck-app/tigerduck-app#768).
 *
 * One for the whole app, and kept across restarts: a process the background sync starts must wait
 * too. It holds a count, a time and the school's message, never the password or anything made
 * from it.
 */
interface StoredPasswordLatch {
    /**
     * The first of the refusals in a row, for the banner, which shows while it holds; null once
     * the school takes the password again.
     */
    val rejection: StateFlow<Rejection?>

    /** Whether a refusal holds for the stored account: never with none stored. */
    val isRejected: Boolean get() = rejection.value != null

    /** The refusals in a row since the school last took the password: 0 with none. */
    val refusals: StateFlow<Int>

    /**
     * Moves with every refusal counted and every end of the count. What a wait keys on: a clear
     * and a new refusal can bring [refusals] back to the value it had before anyone looked.
     */
    val changes: StateFlow<Int>

    /**
     * When an automatic post of the stored password may go next, which is now or earlier once the
     * wait is over; null while no refusal holds, or no account is stored.
     */
    fun retryAt(): Long?

    /** Whether an automatic post of the stored password has to wait now. */
    fun postMustWait(): Boolean

    /**
     * Moved by [clear]. An automatic sign-in reads it before it posts the password and hands it to
     * [markRejected] or [markAccepted], so an answer that arrives after the user signed in again,
     * or signed out, counts for nothing.
     */
    val generation: Int

    /**
     * Counts a refusal of [password] for [studentId] in a sign-in the user did not start, which
     * read [generation] before it posted them. [reason] is the school's message, when the page
     * carried one. Nothing counts once [clear] has run since, or when the stored account is no
     * longer this one: a sign-in with a new password can finish while one with the old password is
     * still under way. A refusal that follows others keeps their [rejection].
     */
    fun markRejected(generation: Int, studentId: String, password: String, reason: String?)

    /**
     * Ends the count after the school took [password] for [studentId] in a sign-in the user did not
     * start, under the same rules as [markRejected]. Only for a post: a live session that let a
     * sign-in through without one says nothing of the password.
     */
    fun markAccepted(generation: Int, studentId: String, password: String)

    /**
     * [markAccepted] for the retry of a refused password ([RetrySettlement]), which settles the
     * count only after its Moodle token step too: nothing ends unless [changes] still reads
     * [changesSeen], what it read as the school took the retry's post. A refusal counted since,
     * another sign-in's say, stands, and so does an end of the count.
     */
    fun settle(generation: Int, changesSeen: Int, studentId: String, password: String)

    /** When a sign-in the user made stores the password the school took, and on sign-out. */
    fun clear()

    /** Whether [studentId] and [password] are the stored account's. */
    fun isStoredAccount(studentId: String, password: String): Boolean

    /**
     * Held by every automatic post of the stored password, the SSO sign-ins' and the Moodle token
     * renewal's alike, from its last look at the count right before the post until it has read
     * the school's answer and counted it ([postAutomatically]): a refusal one of them gets lands
     * before the next one looks, never while it is already posting. A sign-in the user makes never
     * takes it.
     */
    val automaticPosts: Mutex

    /**
     * One run of refusals. Not a data class: two runs with the same message are still two, and a
     * banner closed for one shows again for the next.
     */
    class Rejection(val reason: String?)
}

/**
 * Runs [post], an automatic sign-in's post of the stored password through to reading the school's
 * answer, holding [StoredPasswordLatch.automaticPosts], and only for credentials still worth
 * posting. The look that counts is this one, made holding the lock, so no other automatic post's
 * refusal can land between it and this post. [post] counts the answer it gets before it returns.
 *
 * Not at all while a refusal's wait holds: [refused] makes the error then. Nor once [studentId]
 * and [password] are no longer the stored account's, or [generation], read before the sign-in
 * began, has moved: a sign-in by hand stored another password, or the account signed out, while
 * this one was on its way. [stale] makes the error then.
 */
internal suspend fun <T> StoredPasswordLatch.postAutomatically(
    generation: Int,
    studentId: String,
    password: String,
    refused: (StoredPasswordLatch.Rejection) -> Exception,
    stale: () -> Exception,
    post: suspend () -> T,
): T = automaticPosts.withLock {
    if (postMustWait()) throw refused(rejection.value ?: StoredPasswordLatch.Rejection(null))
    if (this.generation != generation || !isStoredAccount(studentId, password)) throw stale()
    post()
}

/**
 * How a Moodle token harvest posts the password to NTUST's SSO: [post] runs `send`, which posts it
 * and reads SSO's answer, throwing its refusal as [MoodleWebserviceError.SsoLoginRejected].
 */
internal interface PasswordPosting {
    suspend fun <T> post(send: suspend () -> T): T

    /** For credentials the user just typed: posted as they are, whatever the count holds. */
    object Typed : PasswordPosting {
        override suspend fun <T> post(send: suspend () -> T): T = send()
    }
}

/**
 * Runs [harvest], a Moodle token harvest the user did not start, which posts the stored
 * [password] to NTUST's SSO with the [PasswordPosting] it is handed: not at all while a refusal's
 * wait holds, counting a refusal of the password it posted, and ending the count when SSO takes
 * it. That posting takes [StoredPasswordLatch.automaticPosts] and checks the count again right
 * before the password goes out ([postAutomatically]), so a refusal another sign-in counted
 * meanwhile stops it too. A harvest that gets its token from a live session posts nothing and
 * leaves the count. The harvest a sign-in the user makes posts with [PasswordPosting.Typed].
 */
internal suspend fun <T> StoredPasswordLatch.guardAutomaticHarvest(
    studentId: String,
    password: String,
    harvest: suspend (posting: PasswordPosting) -> T,
): T {
    if (postMustWait()) throw MoodleWebserviceError.SsoLoginRejected(rejection.value?.reason)
    val before = generation
    val latch = this
    return harvest(object : PasswordPosting {
        override suspend fun <R> post(send: suspend () -> R): R =
            latch.postAutomatically(
                generation = before,
                studentId = studentId,
                password = password,
                refused = { MoodleWebserviceError.SsoLoginRejected(it.reason) },
                // As a harvest that finds the account signed out once it has the token.
                stale = { MoodleWebserviceError.MissingStoredCredentials() },
            ) {
                try {
                    send().also { latch.markAccepted(before, studentId, password) }
                } catch (e: MoodleWebserviceError.SsoLoginRejected) {
                    latch.markRejected(before, studentId, password, e.reason)
                    throw e
                }
            }
    })
}

/**
 * [StoredPasswordLatch] in its own preferences file, as a count, a time and a nullable message: a
 * missing key reads as no refusal.
 */
@Singleton
class PersistedStoredPasswordLatch internal constructor(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    /** The stored student ID and password, or null with none stored. */
    private val storedAccount: () -> Pair<String, String>?,
) : StoredPasswordLatch {

    @Inject
    constructor(@ApplicationContext context: Context, credentials: CredentialManager) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        storedAccount = {
            val studentId = credentials.ntustStudentId
            val password = credentials.ntustPassword
            if (studentId == null || password == null) null else studentId to password
        },
    )

    private val lock = Any()
    private var currentGeneration = 0

    override val automaticPosts = Mutex()

    private val _refusals = MutableStateFlow(takeUpLatch(prefs))
    override val refusals: StateFlow<Int> = _refusals.asStateFlow()

    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private var refusedAt = prefs.getLong(KEY_REFUSED_AT, 0L)

    private val _rejection = MutableStateFlow(
        if (_refusals.value > 0) StoredPasswordLatch.Rejection(prefs.getString(KEY_REASON, null)) else null,
    )
    override val rejection: StateFlow<StoredPasswordLatch.Rejection?> = _rejection.asStateFlow()

    // The refusal was of the stored password; with no account stored, there is none to refuse,
    // whatever a sign-out that never ran its clear left behind.
    override val isRejected: Boolean get() = _rejection.value != null && storedAccount() != null

    override fun retryAt(): Long? {
        if (storedAccount() == null) return null
        return synchronized(lock) { StoredPasswordSchedule.retryAt(_refusals.value, refusedAt, now()) }
    }

    override fun postMustWait(): Boolean {
        if (storedAccount() == null) return false
        return synchronized(lock) {
            val at = now()
            val retryAt = StoredPasswordSchedule.retryAt(_refusals.value, refusedAt, at)
            retryAt != null && retryAt > at
        }
    }

    override val generation: Int get() = synchronized(lock) { currentGeneration }

    override fun markRejected(
        generation: Int,
        studentId: String,
        password: String,
        reason: String?,
    ) {
        synchronized(lock) {
            if (generation != currentGeneration) return
            if (!isStoredAccount(studentId, password)) return
            val count = _refusals.value + 1
            val at = now()
            // One already holds: its message stays, as a new one would show a banner the user
            // closed again.
            val held = _rejection.value
            val message = reason?.trim()?.take(MAX_REASON_LENGTH)?.takeIf { it.isNotEmpty() }
            // Written through before anything reads it: a process the system ends right
            // after must not start the next one without the wait.
            prefs.edit().apply {
                putInt(KEY_REFUSALS, count)
                putLong(KEY_REFUSED_AT, at)
                if (held == null) {
                    if (message == null) remove(KEY_REASON) else putString(KEY_REASON, message)
                }
            }.commit()
            refusedAt = at
            if (held == null) _rejection.value = StoredPasswordLatch.Rejection(message)
            _refusals.value = count
            _changes.value++
        }
    }

    override fun markAccepted(generation: Int, studentId: String, password: String) {
        synchronized(lock) {
            if (generation != currentGeneration) return
            if (!isStoredAccount(studentId, password)) return
            forget()
        }
    }

    override fun settle(generation: Int, changesSeen: Int, studentId: String, password: String) {
        synchronized(lock) {
            if (generation != currentGeneration || _changes.value != changesSeen) return
            if (!isStoredAccount(studentId, password)) return
            forget()
        }
    }

    override fun isStoredAccount(studentId: String, password: String): Boolean {
        val (storedId, storedPassword) = storedAccount() ?: return false
        return normalized(storedId) == normalized(studentId) && storedPassword == password
    }

    override fun clear() {
        synchronized(lock) {
            currentGeneration++
            forget()
        }
    }

    private fun forget() {
        if (_refusals.value == 0 && _rejection.value == null && !prefs.contains(KEY_REFUSALS)) return
        prefs.edit().remove(KEY_REFUSALS).remove(KEY_REFUSED_AT).remove(KEY_REASON).apply()
        refusedAt = 0L
        _rejection.value = null
        _refusals.value = 0
        _changes.value++
    }

    private fun normalized(studentId: String) = studentId.trim().uppercase()

    private companion object {
        const val PREFS_NAME = "tigerduck_auth"
        const val KEY_REFUSALS = "stored_password_refusals"
        const val KEY_REFUSED_AT = "stored_password_refused_at"
        const val KEY_REASON = "stored_password_rejected_reason"

        /** Before the retries, a refusal held every automatic post until the user signed in again. */
        const val KEY_LATCHED = "stored_password_rejected"

        // The school's message is a line or two; anything longer is not one.
        const val MAX_REASON_LENGTH = 200

        /**
         * The refusals stored, after moving a latch from a build before the retries onto them with
         * the quick ones spent: one try at once, then one every 6 hours, as on the iPhone
         * (`StoredPasswordLatchRetryMigration`).
         */
        fun takeUpLatch(prefs: SharedPreferences): Int {
            if (!prefs.getBoolean(KEY_LATCHED, false)) return prefs.getInt(KEY_REFUSALS, 0)
            val refusals = StoredPasswordSchedule.quickRetries
            prefs.edit()
                .remove(KEY_LATCHED)
                .putInt(KEY_REFUSALS, refusals)
                .putLong(KEY_REFUSED_AT, 0L)
                .commit()
            return refusals
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StoredPasswordLatchModule {
    @Binds
    abstract fun bindStoredPasswordLatch(latch: PersistedStoredPasswordLatch): StoredPasswordLatch
}
