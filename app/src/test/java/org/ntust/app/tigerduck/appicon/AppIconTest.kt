package org.ntust.app.tigerduck.appicon

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The icons Settings offers and how a pick is kept, as on the iPhone (tigerduck-app#1026). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppIconTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the choices are the iPhone's, in its order, each kept under the iPhone's name`() {
        assertEquals(
            listOf(null, "TigerDuck", "Naihu", "TigerDuckChristmas"),
            AppIcon.entries.map { it.stored },
        )
        assertEquals(
            listOf(
                R.string.settings_app_icon_default,
                R.string.app_icon_name_tigerduck,
                R.string.app_icon_name_naihu,
                R.string.app_icon_name_tigerduck_christmas,
            ),
            AppIcon.entries.map { it.title },
        )
    }

    @Test
    fun `nothing kept, or a name this build does not have, is the default`() {
        assertEquals(AppIcon.DEFAULT, AppIcon.of(null))
        assertEquals(AppIcon.DEFAULT, AppIcon.of("Halloween"))
        AppIcon.entries.forEach { assertEquals(it, AppIcon.of(it.stored)) }
    }

    @Test
    fun `a pick is kept per device under appIcon, and the default keeps nothing`() {
        val file = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)
        file.edit().clear().commit()
        val prefs = AppPreferences(context)
        assertEquals(AppIcon.DEFAULT, prefs.appIcon.value)

        prefs.appIcon.value = AppIcon.TIGER_DUCK_CHRISTMAS
        assertEquals("TigerDuckChristmas", file.getString("appIcon", null))
        assertEquals(AppIcon.TIGER_DUCK_CHRISTMAS, AppPreferences(context).appIcon.value)

        prefs.appIcon.value = AppIcon.DEFAULT
        assertEquals(false, file.contains("appIcon"))
        assertEquals(AppIcon.DEFAULT, prefs.appIcon.flow.value)
    }
}
