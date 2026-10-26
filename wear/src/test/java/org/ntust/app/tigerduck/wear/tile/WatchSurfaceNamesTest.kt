package org.ntust.app.tigerduck.wear.tile

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.complication.NextClassComplicationService
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the watch face and tile pickers name and draw the Next class tile and
 * complication: in the watch's language, with the app's icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW")
class WatchSurfaceNamesTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun service(cls: Class<*>): ServiceInfo =
        app.packageManager.getServiceInfo(ComponentName(app, cls), PackageManager.GET_META_DATA)

    @Test
    fun `the tile is named and described in the watch's language`() {
        val tile = service(NextClassTileService::class.java)

        assertEquals("下一堂課", tile.loadLabel(app.packageManager).toString())
        assertEquals(R.string.watch_widget_next_class_description, tile.descriptionRes)
    }

    @Test
    fun `the tile's preview is not the generic Android icon`() {
        val preview = service(NextClassTileService::class.java).metaData.getInt("androidx.wear.tiles.PREVIEW")

        assertNotEquals(android.R.drawable.sym_def_app_icon, preview)
    }

    @Test
    fun `the complication is named in the watch's language and drawn with the app's icon`() {
        val complication = service(NextClassComplicationService::class.java)

        assertEquals("下一堂課", complication.loadLabel(app.packageManager).toString())
        assertEquals(R.mipmap.ic_launcher, complication.icon)
    }
}
