package org.ntust.app.tigerduck.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.shared.LibraryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a library account is signed in, as the library row in Settings, the
 * Library page and flip-to-library all count it: [LibraryService.hasAccount] (a
 * live token, or stored credentials the library has not refused), or the demo
 * library. Flip-to-library listens only while one is, and its switch reads off
 * until then.
 *
 * The stores behind it say nothing when they change, and a token also runs out
 * with time, so whatever changes them calls [refresh]: a library sign-in or
 * sign-out, the school's, which can take the library with it, and each return
 * to the app. Signed out, the QR code kept in [LibraryQRCache] goes too: it
 * still scans as the account's pass. With it goes when the library last issued
 * one ([AppPreferences.librarySyncedAtMs]), the Library page's "Last synced".
 */
@Singleton
class LibrarySession internal constructor(
    private val read: () -> Boolean,
    private val onSignedOut: () -> Unit = {},
) {

    @Inject
    constructor(
        libraryService: LibraryService,
        demoAccount: DemoAccount,
        qrCache: LibraryQRBitmapCache,
        prefs: AppPreferences,
    ) : this({ libraryService.hasAccount || demoAccount.isLibrarySignedIn }, qrCache, prefs)

    /** Signed out, [qrCache]'s code goes, and the page's date with it. */
    internal constructor(read: () -> Boolean, qrCache: LibraryQRCache<*>, prefs: AppPreferences) :
        this(read, {
            qrCache.clear()
            prefs.forgetLibrarySynced()
        })

    private val _signedIn = MutableStateFlow(read())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * Reads the stores again; returns whether a library account is signed in.
     * One at a time, so a read that started earlier cannot write its older
     * answer over a later one's: it runs from the main thread and from the
     * sign-ins' own.
     */
    @Synchronized
    fun refresh(): Boolean = read().also {
        _signedIn.value = it
        if (!it) onSignedOut()
    }
}
