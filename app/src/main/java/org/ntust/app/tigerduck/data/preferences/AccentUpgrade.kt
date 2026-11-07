package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection

/*
 * The accent as the release before this one (v2.4.0) left it: a gradient stored as its start,
 * end and direction beside `accentColorHex`, which the watch drew with no switch of its own.
 */

/** The watch switch's key: the switch that release did not have. */
private const val WATCH_SWITCH_KEY = "accentGradientOnWatch"

/**
 * The gradient the release before stored, as two stops of a linear gradient in its direction, or
 * null for none: its -1 for no gradient, and any end that is no color. [AppPreferences.storeAccent]
 * drops the keys at the next pick, so this reads only until then.
 */
internal fun legacyAccentGradient(prefs: SharedPreferences): AccentGradient? = AccentGradient.legacy(
    prefs.getInt("accentGradientStartHex", AccentPolicy.NO_GRADIENT),
    prefs.getInt("accentGradientEndHex", AccentPolicy.NO_GRADIENT),
    prefs.getInt("accentGradientDirection", AccentGradientDirection.DEFAULT.code),
)

/**
 * Once, for an install upgraded from the release before with a gradient picked: turns the watch's
 * switch on, so the watch goes on drawing the gradient it drew with no switch. Only while the
 * gradient still counts, as it did then ([AccentGradient.saved]: a color an older build picked over
 * it replaced it), and only while the switch has never been set, so a switch turned off since,
 * and the next start, leave it alone. A new install and one with a single color keep it off, as on
 * the iPhone; the widgets and the Live Update drew the first stop alone then, and stay off too.
 */
internal fun keepWatchGradientFromBefore(prefs: SharedPreferences) {
    if (prefs.contains(WATCH_SWITCH_KEY)) return
    val gradient = legacyAccentGradient(prefs) ?: return
    val accentHex = AccentPolicy.fixedLightHex(prefs.getInt("accentColorHex", AccentPolicy.DEFAULT_LIGHT_HEX))
    if (AccentGradient.saved(gradient, accentHex) != gradient) return
    prefs.edit().putBoolean(WATCH_SWITCH_KEY, true).apply()
}
