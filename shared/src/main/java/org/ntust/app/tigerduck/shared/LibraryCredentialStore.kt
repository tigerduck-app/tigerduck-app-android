package org.ntust.app.tigerduck.shared

/**
 * Backing store for NTUST library credentials and the short-lived token issued
 * by `api.lib.ntust.edu.tw`. The phone keeps these in EncryptedSharedPreferences;
 * the watch keeps them in a DataStore that mirrors the phone via the Wearable
 * Data Layer. Both sides plug into [LibraryService] through this interface so
 * the request/response wire schema is defined exactly once.
 */
interface LibraryCredentialStore {
    var libraryUsername: String?
    var libraryPassword: String?
    var libraryToken: String?
    var libraryTokenExpiry: Long
    val isLibraryTokenValid: Boolean
}
