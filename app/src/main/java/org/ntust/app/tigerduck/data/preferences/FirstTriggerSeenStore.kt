package org.ntust.app.tigerduck.data.preferences

/**
 * Persistence seam for one-shot first-trigger prompt "seen" flags. Implemented
 * by [AppPreferences] over SharedPreferences; exists as an interface so the
 * prompt controller's gate logic is unit-testable with an in-memory fake.
 */
interface FirstTriggerSeenStore {
    fun hasSeenFirstTriggerPrompt(storageKey: String): Boolean
    fun setFirstTriggerPromptSeen(storageKey: String, seen: Boolean)
}
