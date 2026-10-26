package org.ntust.app.tigerduck.ui.screen.browser

import android.os.Bundle

/**
 * The page the browser puts back when it is recreated: the WebView's saved state, the sign-out
 * generation it belongs to ([BrowserSession.generation]), and a sign-in's target if one was under
 * way. A recreated browser holds it until its page has started, so a save in between keeps it.
 */
internal class SavedPage(val webState: Bundle, val session: Int, val signInTarget: String?) {

    fun writeTo(out: Bundle) {
        out.putInt(KEY_SESSION, session)
        out.putBundle(KEY_WEB_STATE, webState)
        signInTarget?.let { out.putString(KEY_SIGN_IN_TARGET, it) }
    }

    companion object {
        private const val KEY_WEB_STATE = "web_state"
        private const val KEY_SIGN_IN_TARGET = "sign_in_target"
        private const val KEY_SESSION = "session"

        /** The page [state] holds, or null when it holds none. */
        fun readFrom(state: Bundle?): SavedPage? {
            val webState = state?.getBundle(KEY_WEB_STATE) ?: return null
            if (!state.containsKey(KEY_SESSION)) return null
            return SavedPage(webState, state.getInt(KEY_SESSION), state.getString(KEY_SIGN_IN_TARGET))
        }
    }
}
