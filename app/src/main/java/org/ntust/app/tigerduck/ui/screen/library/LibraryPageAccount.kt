package org.ntust.app.tigerduck.ui.screen.library

import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService

/**
 * The library account behind the Library page: whether the page shows the pass
 * or the sign-in form, whose code it shows, and where the code comes from. Apart
 * from [LibraryViewModel] so a test can run it.
 */
internal class LibraryPageAccount(
    private val credentials: LibraryCredentialStore,
    private val libraryService: LibraryService,
    /** The demo library's code, or null for a real account's. */
    private val demoCode: () -> String?,
    /** Whether the demo library is signed in, which stands in for a real account. */
    private val demoSignedIn: () -> Boolean,
) {
    /**
     * Whether the page shows the pass. Stored credentials count, as for
     * [LibraryService.hasAccount]: the code's fetch signs in again with them,
     * so an expired token shows a code, and a fetch that fails without a
     * refusal, such as a timeout at the gate, is tried again rather than
     * signing out. Only a refused stored password or no account shows the
     * sign-in form.
     */
    val hasAccount: Boolean
        get() = demoSignedIn() || libraryService.hasAccount

    /** The account a code belongs to; see [codeAccount]. */
    val codeAccount: Int
        get() = codeAccount(credentials.libraryAccountGeneration, demoSignedIn())

    suspend fun fetchCode(): String = demoCode() ?: libraryService.generateQRCode()

    companion object {
        /**
         * The account a QR code belongs to, for [LibraryQRCycle]: the library
         * account generation, and apart from it the demo library, whose code
         * stands in for a real account's without moving the generation.
         */
        fun codeAccount(generation: Int, demoLibrary: Boolean): Int =
            generation * 2 + if (demoLibrary) 1 else 0
    }
}
