package org.ntust.app.tigerduck.library

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.core.strings.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The library QR opened from outside the app, as the iPhone's App Intents are to (tigerduck-app
 * #308): the launcher shortcut and the Quick Settings tile open the Library page's link, the one
 * the Library widget opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryQrShortcutTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the launcher shortcut opens the library QR in this app`() {
        val shortcut = LibraryQrShortcut.shortcut(context)

        assertEquals(LibraryQrShortcut.ID, shortcut.id)
        assertEquals(context.getString(R.string.library_virtual_pass_title), shortcut.shortLabel.toString())
        val intent = shortcut.intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("tigerduck://library", intent.dataString)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `publishing puts the shortcut on the app's icon`() {
        LibraryQrShortcut.publish(context)

        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == LibraryQrShortcut.ID })
    }

    @Test
    fun `publishing again in another language relabels the shortcut`() {
        LibraryQrShortcut.publish(context)
        val chinese = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.TAIWAN) },
        )

        LibraryQrShortcut.publish(chinese)

        val label = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == LibraryQrShortcut.ID }.shortLabel
        assertEquals(chinese.getString(R.string.library_virtual_pass_title), label.toString())
    }

    /**
     * A language change reaches MainActivity as a configuration change, as the manifest handles
     * `locale`, not as a new onCreate: the shortcut is relabelled there too.
     */
    @Test
    fun `the app's page publishes the shortcut again when the language changes`() {
        val source = File(appDir(), "src/main/java/org/ntust/app/tigerduck/MainActivity.kt").readText()
        val start = source.indexOf("override fun onConfigurationChanged(")
        assertTrue("MainActivity has no onConfigurationChanged", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))

        assertTrue(body.contains("LibraryQrShortcut.publish(this)"))
    }

    @Test
    fun `the Quick Settings tile is declared for the system to bind`() {
        val manifest = File(appDir(), "src/main/AndroidManifest.xml").readText()
        val service = Regex("""<service[^>]*android:name="org\.ntust\.app\.tigerduck\.library\.LibraryQrTileService"[\s\S]*?</service>""")
            .find(manifest)?.value ?: throw AssertionError("the manifest declares no library QR tile")

        assertTrue(service.contains("""android:permission="android.permission.BIND_QUICK_SETTINGS_TILE""""))
        assertTrue(service.contains("""android:exported="true""""))
        assertTrue(service.contains("""android.service.quicksettings.action.QS_TILE"""))
    }

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
            if (File(here, "src/main/java").isDirectory) return here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }
}
