package org.ntust.app.tigerduck.library

import org.ntust.app.tigerduck.shared.LibraryCredentialStore

/**
 * The library account the phone hands the watch, as user name to password, or
 * null for none, which wipes the watch's copy: signed out, or while the library
 * has refused the stored password, so the watch does not sign in with it
 * either. A sign-in that works hands it over again.
 */
internal fun libraryCredentialsForWatch(store: LibraryCredentialStore): Pair<String, String>? {
    if (store.libraryPasswordRefused) return null
    val username = store.libraryUsername ?: return null
    val password = store.libraryPassword ?: return null
    return username to password
}
