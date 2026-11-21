package org.ntust.app.tigerduck.appicon

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

/**
 * The icons the launcher can show for the app, in the order Settings offers them, as on the
 * iPhone (tigerduck-app#1026): [DEFAULT], the build's own icon, which is Naihu, then each by name.
 *
 * Each is an `activity-alias` of MainActivityHost with the launcher's intent filter, named [alias]
 * in the manifest, and [LauncherIcons] keeps exactly one of them enabled. A launcher knows the
 * app's icon by that name, a home screen icon among it, so a released one never changes.
 * [DEFAULT]'s is `org.ntust.app.tigerduck.MainActivity`, the launcher's entry in every build before
 * the icons, so an update leaves the home screen as it was. [stored] is the iPhone's name for the
 * icon, kept per device ([org.ntust.app.tigerduck.data.preferences.AppIconPreference]); [DEFAULT]
 * stores nothing, so it follows whatever icon a later build makes its own.
 */
enum class AppIcon(val stored: String?, val alias: String, @param:StringRes val title: Int, val icon: Int) {
    DEFAULT(null, "org.ntust.app.tigerduck.MainActivity", R.string.settings_app_icon_default, R.mipmap.ic_launcher),
    TIGER_DUCK("TigerDuck", "org.ntust.app.tigerduck.icon.TigerDuck", R.string.app_icon_name_tigerduck, R.mipmap.ic_launcher_tigerduck),
    NAIHU("Naihu", "org.ntust.app.tigerduck.icon.Naihu", R.string.app_icon_name_naihu, R.mipmap.ic_launcher_naihu),
    TIGER_DUCK_CHRISTMAS(
        "TigerDuckChristmas",
        "org.ntust.app.tigerduck.icon.TigerDuckChristmas",
        R.string.app_icon_name_tigerduck_christmas,
        R.mipmap.ic_launcher_tigerduck_christmas,
    ),
    ;

    companion object {
        /** The icon [stored] names, or [DEFAULT] for nothing or a name this build does not have. */
        fun of(stored: String?): AppIcon = entries.firstOrNull { it.stored != null && it.stored == stored } ?: DEFAULT
    }
}
