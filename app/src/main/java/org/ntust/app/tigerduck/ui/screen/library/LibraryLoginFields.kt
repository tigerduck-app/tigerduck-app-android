package org.ntust.app.tigerduck.ui.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Nothing else starts them over, so what the student types stays through a resume, a recomposition
 * or a rotation. The stored ID settles after a sign-out only at the next resume, which reads it
 * again; that moves the prefill, which the ID shows until the student edits it, and nothing else.
 */
@Stable
internal class LibraryLoginFields(private val prefill: State<String>) {
    /** The ID as the student edited it; null while it is still the prefill. */
    private var edited by mutableStateOf<String?>(null)

    var username: String
        get() = edited ?: prefill.value
        set(value) {
            edited = value
        }

    var password by mutableStateOf("")
}

/**
 * Keyed on the sign-in state alone. The prefill is read again as [storedUsername] moves, a StateFlow's,
 * which the plain suggestedUsername getter is not: an ID the student has not edited follows it.
 */
@Composable
internal fun rememberLibraryLoginFields(signedIn: Boolean, storedUsername: String?, suggested: () -> String): LibraryLoginFields {
    val prefill = rememberUpdatedState(remember(signedIn, storedUsername) { suggested() })
    return remember(signedIn) { LibraryLoginFields(prefill) }
}
