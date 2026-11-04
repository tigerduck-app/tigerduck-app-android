package org.ntust.app.tigerduck.ui.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The library sign-in form's two fields, as typed.
 *
 * They start over at every library sign-in and sign-out, whichever way it came: the page's menu,
 * Settings' account row, or any other run of SignOutPlans, while the page stays composed or not.
 * Each one turns the sign-in state the page draws from ([LibraryViewModel.isLoggedIn], which
 * follows the library's session), so keying the fields on it covers them all, where clearing on
 * one sign-out path would miss the others. The password is then never left for the next student to
 * reveal or sign in with. The ID starts as a fresh page's does, prefilled on purpose with the stored
 * library ID or the student's own NTUST ID ([LibraryViewModel.suggestedUsername]).
 *
 * Keyed on [storedUsername] too, a StateFlow the plain suggestedUsername getter is not, so a sign-in
 * with a different NTUST account starts the ID over as well.
 */
@Stable
internal class LibraryLoginFields(username: String) {
    var username by mutableStateOf(username)
    var password by mutableStateOf("")
}

@Composable
internal fun rememberLibraryLoginFields(signedIn: Boolean, storedUsername: String?, suggested: () -> String): LibraryLoginFields =
    remember(signedIn, storedUsername) { LibraryLoginFields(suggested()) }
