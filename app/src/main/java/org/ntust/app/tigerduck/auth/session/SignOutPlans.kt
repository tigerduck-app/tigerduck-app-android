package org.ntust.app.tigerduck.auth.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ClearingSteps
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.forgetEmptyClassroom
import org.ntust.app.tigerduck.library.LibrarySession
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

/** One account's sign-out, as [SignOutPlans] runs it. */
internal interface AccountSignOut {
    /** Whether there is anyone to sign out: an account stored, or one shown as signed in. */
    val held: Boolean

    fun signOut(reason: SignOutReason)
}

/** An account's sign-out by its two parts. */
internal class AccountPlan(private val holds: () -> Boolean, private val run: (SignOutReason) -> Unit) : AccountSignOut {
    override val held: Boolean get() = holds()

    override fun signOut(reason: SignOutReason) = run(reason)
}

/** The school's sign-out, and the next start's finish of one a process death cut short. */
internal interface SchoolPlan : AccountSignOut {
    /** At process start, first: finishes a sign-out a process death cut short, and says whether there was one. */
    fun finishOwedSignOut(): Boolean

    /** At process start, once [finishOwedSignOut] said so: that sign-out's wipe, after which the sign-in opens. */
    fun finishOwedWipe()
}

/**
 * Every sign-out, through one call: [signOut], with why. It sits above the sessions and the
 * stores, which depend on their session and never on this, and it is the only caller of each
 * account's end: the school's [SchoolSignOut] below, School Mail's [MailAccount.signOut] and the
 * library's [LibrarySession.signOut]. The `sign-out-start` check in tools/guardrails fails a call
 * of one anywhere else.
 *
 * The accounts sign in and out on their own (decided for Android on 2026-10-22), so a reason names
 * which go, each in the order it always has:
 * - [SignOutReason.SCHOOL], [SignOutReason.MAIL] and [SignOutReason.LIBRARY]: that account.
 * - [SignOutReason.ENDPOINT_CHANGE]: the school, the library and School Mail, each only if held.
 * - [SignOutReason.FULL_RESET]: School Mail, the school and the demo library. The library account
 *   goes with the credentials in the reset's own wipe, `FullReset`.
 *
 * The reason rides on every [AccountEvent.SignedOut] the sign-out tells.
 */
@Singleton
class SignOutPlans internal constructor(
    private val school: SchoolPlan,
    private val mail: AccountSignOut,
    private val library: AccountSignOut,
    /** Reads the library's state again after a school sign-out, which leaves the library account. */
    private val readLibraryAgain: () -> Unit,
    /** The demo library, which a school sign-out keeps, as it keeps a real one, and a full reset does not. */
    private val leaveDemoLibrary: () -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService,
        credentials: CredentialManager,
        authTokenManager: AuthTokenManager,
        notificationSettingsSync: NotificationSettingsSync,
        courseService: CourseService,
        moodleService: MoodleService,
        classroomUsage: ClassroomUsageService,
        prefs: AppPreferences,
        sessionManager: NtustSessionManager,
        stores: SchoolStores,
        pushRegistration: PushRegistrationService,
        demoAccount: DemoAccount,
        bulletinCache: BulletinCache,
        widgetUpdater: WidgetUpdater,
        sessions: AccountSessions,
        @ApplicationScope appScope: CoroutineScope,
        mailAccount: MailAccount,
        librarySession: LibrarySession,
    ) : this(
        school = SchoolSignOut(
            context, authService, credentials, authTokenManager, notificationSettingsSync, courseService, moodleService,
            classroomUsage, prefs, sessionManager, stores, pushRegistration, demoAccount, bulletinCache, widgetUpdater,
            sessions.school, appScope,
        ),
        mail = AccountPlan({ mailAccount.signedIn.value }, mailAccount::signOut),
        library = AccountPlan({ librarySession.signedIn.value }, librarySession::signOut),
        readLibraryAgain = { librarySession.refresh() },
        leaveDemoLibrary = demoAccount::signOutLibrary,
    )

    /** Signs out the accounts [reason] names, in their order. */
    fun signOut(reason: SignOutReason) {
        when (reason) {
            SignOutReason.SCHOOL -> signOutSchool(reason)
            SignOutReason.MAIL -> mail.signOut(reason)
            SignOutReason.LIBRARY -> library.signOut(reason)
            SignOutReason.ENDPOINT_CHANGE -> {
                // Each asked as its turn comes: one may go with the one before.
                if (school.held) signOutSchool(reason)
                if (library.held) library.signOut(reason)
                if (mail.held) mail.signOut(reason)
            }
            SignOutReason.FULL_RESET -> {
                mail.signOut(reason)
                school.signOut(reason)
                leaveDemoLibrary()
            }
        }
    }

    private fun signOutSchool(reason: SignOutReason) {
        school.signOut(reason)
        readLibraryAgain()
    }

    /** Whether [account] has anyone to sign out. */
    fun holds(account: Account): Boolean = when (account) {
        Account.SCHOOL -> school.held
        Account.MAIL -> mail.held
        Account.LIBRARY -> library.held
    }

    /** At process start, first, before anything acts on the account: see [SchoolPlan.finishOwedSignOut]. */
    fun finishOwedSignOut(): Boolean = school.finishOwedSignOut()

    /** At process start, once [finishOwedSignOut] said so: see [SchoolPlan.finishOwedWipe]. */
    fun finishOwedWipe() = school.finishOwedWipe()
}

/**
 * The school's plan, in the order its sign-out has always run (SignOutClearingTest pins it):
 *
 * | Step | What |
 * |---|---|
 * | Mark | the sign-out's marker ([SignOutOwed]), then the browser's ([BrowserCleanup.markPending]) |
 * | End | under the account lock: the credentials, then the token manager's logout, which ends the sign-in and cancels its work |
 * | Stop | the pushes queued, the shared fetches, the empty-classroom finder, the school session |
 * | Clear at once | [SchoolStores.atOnce] |
 * | Show | signed out, and [AccountEvent.SignedOut] with the reason |
 * | Calls | the departing unregister, then the demo's exit, after everything that must still see the demo |
 * | Clear late | the bulletins' clearing started, then, on the application scope, [SchoolStores.late] and the widgets |
 * | Open | once the last wipe is done ([SignOutWipe]) |
 *
 * Lock order: the account lock, then [SignOutWipe]'s, then the sign-in series'; the token
 * manager's sign-in lock, then the series'. The account shows as signed out outside every lock.
 */
internal class SchoolSignOut(
    private val context: Context,
    private val auth: AuthService,
    private val credentials: CredentialManager,
    private val authTokenManager: AuthTokenManager,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val courseService: CourseService,
    private val moodleService: MoodleService,
    private val classroomUsage: ClassroomUsageService,
    private val prefs: AppPreferences,
    private val sessionManager: NtustSessionManager,
    private val stores: SchoolStores,
    private val pushRegistration: PushRegistrationService,
    private val demoAccount: DemoAccount,
    private val bulletinCache: BulletinCache,
    widgetUpdater: WidgetUpdater,
    session: AccountSession,
    private val appScope: CoroutineScope,
) : SchoolPlan {
    // Held by a sign-out as it ends the sign-in and while it drops the stored account, and by a
    // sign-in while it asks whether its own has ended and stores what it got.
    private val accountLock: Any get() = auth.accountLock

    // The sign-outs under way, which hold the next sign-in, and the wipe each leaves behind.
    private val wipe = SignOutWipe(stores, widgetUpdater, session, appScope)

    override val held: Boolean
        get() = credentials.ntustStudentId != null || authTokenManager.isLoggedIn || auth.authState.value

    override fun signOut(reason: SignOutReason) {
        // Mark: first, on disk, so what follows is owed wherever the process dies. Under the lock a
        // sign-in stores with: one that came first is on the marker, and a later one stores nothing.
        synchronized(accountLock) { wipe.begin(credentials.ntustStudentId) }
        wipe.finish(cleared = clearAccount(reason))
    }

    /**
     * What a school sign-out clears at once, in one place: run by [signOut], and by the next start
     * when a process death cut that short ([finishOwedSignOut]), in the same order, whatever of it
     * was already gone. Every step is tried; false means the next start tries them all again.
     */
    private fun clearAccount(reason: SignOutReason): Boolean = with(ClearingSteps()) {
        // The school account alone: the library account and the school mail stay signed in
        // (decided for Android on 2026-10-22). One step for a sign-in under way, which asks about
        // its own sign-in under the same lock before it stores anything. The session it wipes is
        // handed back here: the unregister below sends its DELETE afterwards.
        // On disk before the account goes: the browser's clearing is owed if the process dies.
        once("browser") { BrowserCleanup.of(context).markPending() }
        var departing: DepartingSession? = null
        synchronized(accountLock) {
            must("credentials") { credentials.signOutSchoolAccount() }
            must("tokens") { departing = authTokenManager.logout() }
        }
        // A queued live_activity push, and an edit still waiting to be
        // confirmed, both belong to the account that is leaving. Neither may
        // reach whoever signs in next.
        must("pushes") { notificationSettingsSync.cancelPendingPushes() }
        // Before the session goes: a 選課清單 fetch still running would
        // otherwise log the departing account back in, and the next one to
        // sign in would find its session warm and read its roster.
        once("course fetches") { courseService.cancelSharedFetches() }
        once("Moodle fetches") { moodleService.cancelSharedFetches() }
        // Likewise the empty-classroom finder: its kept days came from this session, and a read
        // still queued for it must not sign the departing account in again. The next account
        // opens on its own building.
        must("empty classrooms") { forgetEmptyClassroom(classroomUsage, prefs) }
        once("school session") { sessionManager.signOut() }
        // The account's stores that clear at once, each a step of its own, in their list's order.
        stores.atOnce.forEach { store -> must(store.javaClass.simpleName) { store.forgetAccount() } }
        once("signed-in state") { auth.showSignedOut(reason) }
        once("device unregister") { pushRegistration.unregister(departing) }
        // Last, so everything above still sees the demo account and keeps
        // its requests on the device.
        must("demo") { demoAccount.exit() }
        // Bulletins are school-wide rather than private, but the read-state
        // store is already cleared above — clear the content snapshot too
        // so the next session starts coherent instead of half-stale. Started
        // now, so every read and write of it waits for it, as an API endpoint
        // move's clear (BulletinCache.clearIn).
        once("bulletins") { bulletinCache.clearIn(appScope) }
        cleared
    }

    // A recovered sign-out's reason is not on the marker: it was the school's.
    override fun finishOwedSignOut(): Boolean =
        wipe.recover({ credentials.ntustStudentId }) { clearAccount(SignOutReason.SCHOOL) }

    override fun finishOwedWipe() = wipe.finish()
}
