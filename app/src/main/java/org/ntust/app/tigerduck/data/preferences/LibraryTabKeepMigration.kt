package org.ntust.app.tigerduck.data.preferences

import org.ntust.app.tigerduck.data.model.AppFeature

/**
 * One-shot: keeps an existing user's untouched bottom bar as it was when
 * Library joined [AppFeature.defaultTabs] in 2.4.0, so the bar doesn't change
 * under them; 2.4.0's What's New asks whether to add Library instead.
 *
 * An untouched bar is not stored and follows the default, so it is stored as
 * [AppFeature.defaultTabsBeforeLibrary]. Only for an install that predates the
 * change: a new install's first launch, before onboarding, gets the new
 * default. A stored bar is the user's own and stays as it is. The library
 * used to sit behind an opt-in switch; the switch is gone and its stored
 * value is deleted.
 */
internal object LibraryTabKeepMigration {

    /** Returns whether it stored a bar. */
    fun runIfNeeded(prefs: AppPreferences): Boolean {
        if (prefs.libraryTabKeepChecked) return false
        prefs.libraryTabKeepChecked = true
        prefs.forgetLegacyLibraryOptIn()
        if (!prefs.hasCompletedOnboarding || prefs.hasStoredConfiguredTabs) return false
        prefs.configuredTabs = AppFeature.defaultTabsBeforeLibrary
        return true
    }
}
