package org.ntust.app.tigerduck.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.preferences.AppIconPreference
import org.ntust.app.tigerduck.library.LibraryQrShortcut
import org.ntust.app.tigerduck.util.logSummary

/**
 * The app's entries on the launcher: an `activity-alias` of MainActivityHost per [AppIcon], each
 * with the launcher's intent filter. The manifest enables [AppIcon.DEFAULT]'s alone, and [show]
 * enables another's in its place, so the launcher has exactly one entry for the app at a time.
 *
 * MainActivityHost has no launcher entry of its own and is never disabled, so every intent that
 * names it (links, notifications, widgets, the library QR shortcut) opens it whichever icon is
 * shown. The app's own intents name it, never an alias, which another pick may turn off.
 * Each change keeps the app running (DONT_KILL_APP); the system tells the launcher of it within
 * seconds, and the launcher swaps the icon, a home screen copy too where it can.
 *
 * The system closes the window the launcher opened through an alias set disabled, though not one
 * set back to the manifest's state. So an alias is turned off by setting it back, which leaves it
 * disabled as the manifest declares it, and only Default's, the one the manifest enables, is ever
 * set disabled: the window closes only at a pick away from the default made in a window the
 * launcher opened through Default's alias. The app keeps running, and opens again where it starts.
 */
class LauncherIcons(private val packageManager: PackageManager, private val packageName: String) {

    constructor(context: Context) : this(context.packageManager, context.packageName)

    /** The icon on the launcher, by the alias enabled: [AppIcon.DEFAULT] for the build's own. */
    fun shown(): AppIcon =
        AppIcon.entries.firstOrNull { it != AppIcon.DEFAULT && state(it) == COMPONENT_ENABLED_STATE_ENABLED } ?: AppIcon.DEFAULT

    /**
     * Shows [icon] on the launcher. The build's own icon is [AppIcon.DEFAULT]'s alias, as the iPhone
     * shows its primary icon for a pick of it: every alias goes back to the manifest's state. Any
     * other icon has its alias enabled, Default's disabled and the rest back to the manifest's
     * state. Only an alias whose state differs is written, the one that turns on first, so the app
     * never leaves the launcher. One show at a time, so Settings' and [showKeptIcon]'s never mix
     * their changes. Returns whether anything changed.
     */
    fun show(icon: AppIcon): Boolean = synchronized(SHOWING) {
        val changes = AppIcon.entries
            .map { alias -> alias to wanted(alias, icon) }
            .filter { (alias, state) -> state(alias) != state }
            .sortedByDescending { (alias, state) -> isOnLauncher(alias, state) }
        if (changes.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setAll(changes)
        } else {
            changes.forEach { (alias, state) ->
                packageManager.setComponentEnabledSetting(component(alias), state, PackageManager.DONT_KILL_APP)
            }
        }
        true
    }

    /** [changes] as one change, which the launcher hears of once. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setAll(changes: List<Pair<AppIcon, Int>>) {
        packageManager.setComponentEnabledSettings(
            changes.map { (alias, state) ->
                PackageManager.ComponentEnabledSetting(component(alias), state, PackageManager.DONT_KILL_APP)
            },
        )
    }

    private fun state(alias: AppIcon) = packageManager.getComponentEnabledSetting(component(alias))

    private fun component(alias: AppIcon) = ComponentName(packageName, alias.alias)

    companion object {
        private val SHOWING = Any()

        /** The icon the build ships as its own, which [AppIcon.DEFAULT]'s alias draws. */
        val PRIMARY = AppIcon.NAIHU

        /** The state [alias] takes while [icon] is shown. */
        private fun wanted(alias: AppIcon, icon: AppIcon): Int = when {
            icon == AppIcon.DEFAULT || icon == PRIMARY -> COMPONENT_ENABLED_STATE_DEFAULT
            alias == icon -> COMPONENT_ENABLED_STATE_ENABLED
            alias == AppIcon.DEFAULT -> COMPONENT_ENABLED_STATE_DISABLED
            else -> COMPONENT_ENABLED_STATE_DEFAULT
        }

        /** Whether [alias] is on the launcher in [state]; the manifest enables Default's alone. */
        private fun isOnLauncher(alias: AppIcon, state: Int) =
            state == COMPONENT_ENABLED_STATE_ENABLED || (state == COMPONENT_ENABLED_STATE_DEFAULT && alias == AppIcon.DEFAULT)
    }
}

private const val TAG = "LauncherIcons"

/**
 * [LauncherIcons.show], and when the launcher's entry changed, the library QR shortcut published
 * again, labelled in [shortcutLabels]' language, so it rides on the new entry: the system drops a
 * dynamic shortcut whose entry leaves the launcher.
 */
fun LauncherIcons.show(icon: AppIcon, shortcutLabels: Context): Boolean =
    show(icon).also { changed -> if (changed) LibraryQrShortcut.publish(shortcutLabels) }

/**
 * Settings' pick: [show] puts [icon] on the launcher first ([LauncherIcons.show]), and only then
 * is it kept, so Settings never marks an icon the launcher does not have, as on the iPhone.
 * Returns whether it was kept.
 */
fun AppIconPreference.choose(icon: AppIcon, show: (AppIcon) -> Unit): Boolean {
    try {
        show(icon)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not show the app icon: ${e.logSummary()}")
        return false
    }
    value = icon
    return true
}

/**
 * Keeps the launcher on the [kept] pick with [show]: at the app's start, which mends a change a
 * process death cut short, and when a full reset forgets the pick, which puts the build's own icon
 * back. One that fails waits for the next pick, or the next start.
 */
fun CoroutineScope.showKeptIcon(kept: StateFlow<AppIcon>, show: (AppIcon) -> Unit): Job = launch {
    kept.collect { icon ->
        try {
            show(icon)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not show the app icon: ${e.logSummary()}")
        }
    }
}
