package org.ntust.app.tigerduck.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The widget picker lists each widget once. The class widgets were once
 * shipped as a light and a dark provider each; both now draw in the theme the
 * app follows, so only one of each pair is offered, and the other stays
 * declared for the widgets already placed from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetPickerEntriesTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private val providers: List<ActivityInfo> =
        app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA)
            .receivers
            .orEmpty()
            .filter { it.metaData?.containsKey(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) == true }

    private fun ActivityInfo.isHiddenFromPicker(): Boolean {
        val xml = app.resources.getXml(metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))
        while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { /* skip to the root element */ }
        val features = xml.getAttributeIntValue(ANDROID_NS, "widgetFeatures", 0)
        return features and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0
    }

    private fun ActivityInfo.label(): String = loadLabel(app.packageManager).toString()

    @Test
    fun `the picker lists each widget once`() {
        val listed = providers.filterNot { it.isHiddenFromPicker() }.map { it.label() }

        assertEquals(listed.distinct().sorted(), listed.sorted())
        assertEquals(4, listed.size)
    }

    @Test
    fun `a provider kept for placed widgets has a listed twin`() {
        val listed = providers.filterNot { it.isHiddenFromPicker() }.map { it.label() }.toSet()
        val hidden = providers.filter { it.isHiddenFromPicker() }

        assertEquals(3, hidden.size)
        hidden.forEach { assertEquals(it.name, true, it.label() in listed) }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
