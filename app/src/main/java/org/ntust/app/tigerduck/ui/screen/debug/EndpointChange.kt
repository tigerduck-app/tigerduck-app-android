package org.ntust.app.tigerduck.ui.screen.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.SignOutPlans
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLossCheck
import javax.inject.Inject

/**
 * A change of the API endpoint setting, the only place it is stored. A change to another backend
 * asks first, then signs out of every account held ([SignOutReason.ENDPOINT_CHANGE]), and only then
 * stores the endpoint, as the iPhone's does (tigerduck-app#745): that backend never issued the
 * session this phone holds, and at the next sign-in it receives the student ID and the Moodle
 * tokens. It then lets go of the bulletins, which no account owns (tigerduck-app#908). Saving the
 * endpoint already in use asks nothing and signs nothing out.
 *
 * Nothing waits for the sign-out's unregister to go out first: it goes to the backend that issued
 * its tokens whatever the setting names, until that backend has it (DepartingDeviceUnregister).
 */
class EndpointChange internal constructor(
    /** The setting as stored; null is the default. */
    private val stored: () -> String?,
    private val store: (String?) -> Unit,
    /** A school account stored, or a backend session: with neither, there is no one to sign out. */
    private val holdsSchoolAccount: () -> Boolean,
    private val signOutLosses: suspend () -> Set<SignOutLoss>,
    /** Signs out of every account held, each in its turn. */
    private val signOutAccounts: () -> Unit,
    /** Registers the device at the backend stored, without waiting for it. */
    private val registerAgain: () -> Unit,
    /** Lets go of what the backend left stored, which no account owns: the bulletins. */
    private val forgetBackendData: () -> Unit,
    /** Told after a move to another backend, for the data fetched from it to come from the new one. */
    private val movedBackend: () -> Unit = {},
) {
    @Inject
    constructor(
        prefs: AppPreferences,
        signOutPlans: SignOutPlans,
        signOutLossCheck: SignOutLossCheck,
        pushRegistration: PushRegistrationService,
        courseChangeStore: org.ntust.app.tigerduck.coursechanges.CourseChangeStore,
        bulletinCache: BulletinCache,
        bulletinRepository: BulletinRepository,
        bulletinReadState: BulletinReadStateStore,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        stored = { prefs.announcementApiBaseUrlOverride },
        store = { prefs.announcementApiBaseUrlOverride = it },
        holdsSchoolAccount = { signOutPlans.holds(Account.SCHOOL) },
        signOutLosses = signOutLossCheck::losses,
        signOutAccounts = { signOutPlans.signOut(SignOutReason.ENDPOINT_CHANGE) },
        registerAgain = { scope.launch { pushRegistration.syncNow() } },
        // The list, its date and its details, and what this process holds of them; the read
        // marks too, which are by the old backend's ids. The files go on the application scope,
        // as the sign-out's, and every read and write of them waits for that.
        forgetBackendData = {
            bulletinRepository.forget()
            bulletinReadState.clear()
            bulletinCache.clearIn(scope)
        },
        // Drops the old backend's course changes at once and fetches the new one's.
        movedBackend = {
            courseChangeStore.endpointMoved()
            scope.launch { courseChangeStore.refresh() }
        },
    )

    /** Whether storing [target] (null: the default) moves the app to another backend, and so asks first. */
    fun asks(target: String?): Boolean =
        resolveAnnouncementEndpoint(target).url != resolveAnnouncementEndpoint(stored()).url

    /** What the sign-out deletes, for the question's lines: nothing without a school account. */
    suspend fun losses(): Set<SignOutLoss> = if (holdsSchoolAccount()) signOutLosses() else emptySet()

    /**
     * Stores [target] (null: the default), once the user agreed when [asks]. A move to another
     * backend signs out of every account held before it is stored.
     */
    fun apply(target: String?) {
        val moves = asks(target)
        if (moves) signOutAccounts()
        store(target)
        // Signed in or not: the old backend's pages would merge into the new one's rows by id.
        // After the store, so a page fetched from the old backend that lands from now on is
        // dropped (AnnouncementsViewModel).
        if (moves) forgetBackendData()
        registerAgain()
        if (moves) movedBackend()
    }
}
