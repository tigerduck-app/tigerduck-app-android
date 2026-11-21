package org.ntust.app.tigerduck.appicon

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.library.LibraryQrShortcut
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app icon picked in Settings, on the launcher, as on the iPhone (tigerduck-app#1026): an
 * `activity-alias` of MainActivity per icon, exactly one of them the launcher's at a time, and
 * MainActivity reachable by name whichever is shown. Runs on API 33, where [LauncherIcons] sets
 * every alias in one change; [LauncherIconsBeforeApi33Test] runs it one alias at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
open class LauncherIconsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val packageManager = context.packageManager
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    /** What the launcher lists for the app: the aliases with its intent filter that are enabled. */
    private fun launcherEntries(): List<String> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        ).map { it.activityInfo.name }

    private fun state(icon: AppIcon) = packageManager.getComponentEnabledSetting(ComponentName(context, icon.alias))

    @Test
    fun `an install that never picked an icon has the build's own on the launcher, and MainActivity no entry`() {
        assertEquals(listOf(AppIcon.DEFAULT.alias), launcherEntries())
    }

    @Test
    fun `each icon has an alias of MainActivity that draws it`() {
        AppIcon.entries.forEach { icon ->
            val info = packageManager.getActivityInfo(
                ComponentName(context, icon.alias),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(icon.name, MainActivity::class.java.name, info.targetActivity)
            assertEquals(icon.name, icon.icon, info.iconResource)
            assertEquals(icon.name, icon == AppIcon.DEFAULT, info.enabled)
        }
    }

    @Test
    fun `the default draws Naihu, the build's own icon, as the app's own icon does`() {
        assertEquals(AppIcon.NAIHU, LauncherIcons.PRIMARY)
        assertEquals(R.mipmap.ic_launcher, AppIcon.DEFAULT.icon)
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
    }

    @Test
    fun `showing an icon makes its alias the launcher's only entry`() {
        listOf(AppIcon.TIGER_DUCK, AppIcon.TIGER_DUCK_CHRISTMAS).forEach { icon ->
            assertTrue(LauncherIcons(context).show(icon))

            assertEquals(icon.name, listOf(icon.alias), launcherEntries())
        }
    }

    @Test
    fun `MainActivity opens by name whichever icon is shown`() {
        AppIcon.entries.forEach { icon ->
            LauncherIcons(context).show(icon)

            assertNotNull(icon.name, packageManager.resolveActivity(Intent(context, MainActivity::class.java), 0))
            assertEquals(
                icon.name,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java)),
            )
        }
    }

    @Test
    fun `Naihu is shown as the default is, the build's own icon, with nothing to change`() {
        assertFalse(LauncherIcons(context).show(AppIcon.NAIHU))

        assertEquals(listOf(AppIcon.DEFAULT.alias), launcherEntries())
        AppIcon.entries.forEach { assertEquals(it.name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(it)) }
    }

    @Test
    fun `back to the default, every alias is as the manifest has it again`() {
        val launcher = LauncherIcons(context)
        launcher.show(AppIcon.TIGER_DUCK)

        assertTrue(launcher.show(AppIcon.DEFAULT))

        assertEquals(listOf(AppIcon.DEFAULT.alias), launcherEntries())
        AppIcon.entries.forEach { assertEquals(it.name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(it)) }
    }

    /**
     * The system closes a window the launcher opened through an alias set disabled, but not one set
     * back to the manifest's state, which is how the others are turned off: so only a first pick
     * away from the default can close it.
     */
    @Test
    fun `only the default's alias is ever set disabled`() {
        val launcher = LauncherIcons(context)

        launcher.show(AppIcon.TIGER_DUCK)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(AppIcon.DEFAULT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(AppIcon.TIGER_DUCK))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(AppIcon.NAIHU))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(AppIcon.TIGER_DUCK_CHRISTMAS))

        launcher.show(AppIcon.TIGER_DUCK_CHRISTMAS)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(AppIcon.DEFAULT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(AppIcon.TIGER_DUCK))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(AppIcon.TIGER_DUCK_CHRISTMAS))
        assertEquals(listOf(AppIcon.TIGER_DUCK_CHRISTMAS.alias), launcherEntries())
    }

    @Test
    fun `what the launcher shows is read back from the aliases, for the notifications' large icon`() {
        val launcher = LauncherIcons(context)
        assertEquals(AppIcon.DEFAULT, launcher.shown())

        launcher.show(AppIcon.TIGER_DUCK_CHRISTMAS)
        assertEquals(AppIcon.TIGER_DUCK_CHRISTMAS, launcher.shown())
        assertEquals(R.mipmap.ic_launcher_tigerduck_christmas, launcher.shown().icon)

        launcher.show(AppIcon.NAIHU)
        assertEquals(AppIcon.DEFAULT, launcher.shown())
    }

    /** The system drops a dynamic shortcut whose launcher entry leaves the launcher. */
    @Test
    fun `a change of entry puts the library QR shortcut on the new one, and no change leaves it`() {
        val launcher = LauncherIcons(context)
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        assertTrue(launcher.show(AppIcon.TIGER_DUCK, shortcutLabels = context))
        assertEquals(listOf(LibraryQrShortcut.ID), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })

        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        assertFalse(launcher.show(AppIcon.TIGER_DUCK, shortcutLabels = context))
        assertEquals(emptyList<String>(), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
    }

    @Test
    fun `an icon already shown changes nothing`() {
        val launcher = LauncherIcons(context)
        launcher.show(AppIcon.TIGER_DUCK_CHRISTMAS)

        assertFalse(launcher.show(AppIcon.TIGER_DUCK_CHRISTMAS))
        assertFalse(LauncherIcons(context).show(AppIcon.TIGER_DUCK_CHRISTMAS))
    }

    @Test
    fun `a pick is kept once the launcher shows it`() {
        assertTrue(prefs.appIcon.choose(AppIcon.TIGER_DUCK, LauncherIcons(context)::show))

        assertEquals(listOf(AppIcon.TIGER_DUCK.alias), launcherEntries())
        assertEquals(AppIcon.TIGER_DUCK, prefs.appIcon.value)
        assertEquals(AppIcon.TIGER_DUCK, AppPreferences(context).appIcon.value)
    }

    @Test
    fun `a pick the launcher cannot show is not kept, so Settings never marks it`() {
        assertFalse(prefs.appIcon.choose(AppIcon.TIGER_DUCK) { throw IllegalArgumentException("Unknown component") })

        assertEquals(AppIcon.DEFAULT, prefs.appIcon.value)
        assertEquals(listOf(AppIcon.DEFAULT.alias), launcherEntries())
    }

    @Test
    fun `the launcher follows the kept pick, and goes back to the build's own when a full reset forgets it`() = runTest(UnconfinedTestDispatcher()) {
        prefs.appIcon.value = AppIcon.TIGER_DUCK_CHRISTMAS
        backgroundScope.showKeptIcon(prefs.appIcon.flow, LauncherIcons(context)::show)
        assertEquals(listOf(AppIcon.TIGER_DUCK_CHRISTMAS.alias), launcherEntries())

        prefs.appIcon.value = AppIcon.TIGER_DUCK
        assertEquals(listOf(AppIcon.TIGER_DUCK.alias), launcherEntries())

        prefs.clearAllPrefs()
        assertEquals(AppIcon.DEFAULT, prefs.appIcon.value)
        assertEquals(listOf(AppIcon.DEFAULT.alias), launcherEntries())
    }

    @Test
    fun `a pick the launcher refuses leaves the follower for the next one`() = runTest(UnconfinedTestDispatcher()) {
        val kept = MutableStateFlow(AppIcon.TIGER_DUCK)
        val shown = mutableListOf<AppIcon>()
        backgroundScope.showKeptIcon(kept) { icon ->
            if (icon == AppIcon.TIGER_DUCK) throw IllegalArgumentException("Unknown component")
            shown += icon
        }

        kept.value = AppIcon.TIGER_DUCK_CHRISTMAS

        assertEquals(listOf(AppIcon.TIGER_DUCK_CHRISTMAS), shown)
    }
}

/** [LauncherIconsTest] below API 33, where each alias is set on its own. */
@Config(sdk = [30], application = Application::class)
class LauncherIconsBeforeApi33Test : LauncherIconsTest()
