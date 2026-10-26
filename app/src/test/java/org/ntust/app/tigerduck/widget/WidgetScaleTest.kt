package org.ntust.app.tigerduck.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.runComposition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DisplayScale
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widgets at the display size and text size picked for them: their type
 * and their own padding multiplied, the phone's sizes left as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetScaleTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        app.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(app).apply { appLanguage = "en" }
    }

    private fun todayWidget() = object : BaseClassTableWidget(
        WidgetLayout.Today,
        SizeMode.Single,
        preferencesOf = { prefs },
        loadState = {
            WidgetState(
                courses = emptyList(),
                activeWeekdays = (1..5).toList(),
                activePeriodIds = emptyList(),
                currentWeekday = 1,
                currentMinuteOfDay = 7 * 60,
                isLoggedIn = true,
                isQuietToday = false,
                ongoingBlocks = emptyList(),
                nextTodayBlock = null,
                tomorrowFirstCourseNo = null,
                tomorrowFirstCourseWeekday = null,
                tomorrowFirstCoursePeriodId = null,
                courseColors = emptyMap(),
                courseNameScale = 1f,
            )
        },
    ) {}

    @OptIn(ExperimentalGlanceApi::class)
    private fun draw(widget: GlanceAppWidget): View = runBlocking {
        val views: RemoteViews = withTimeout(10_000) {
            widget.runComposition(app).first { texts(it.apply(app, FrameLayout(app))).isNotEmpty() }
        }
        views.apply(app, FrameLayout(app))
    }

    private fun texts(view: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.text.isNotEmpty()) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }

    private fun textSize(root: View, text: String): Float =
        texts(root).single { it.text.toString() == text }.textSize

    /** The largest left padding in the widget: the Today list's own, around everything. */
    private fun outerPadding(root: View): Int {
        var most = 0
        fun walk(v: View) {
            most = maxOf(most, v.paddingLeft)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return most
    }

    @Test
    fun `the widget text size multiplies the widget's type`() {
        val before = textSize(draw(todayWidget()), "No classes today")

        prefs.setDisplayScale(DisplayScale.WidgetText, 1.5f)
        val after = textSize(draw(todayWidget()), "No classes today")

        assertEquals(before * 1.5f, after, 0.01f)
    }

    @Test
    fun `the widget display size multiplies the widget's own padding, not its type`() {
        val plain = draw(todayWidget())
        val before = outerPadding(plain)
        val beforeText = textSize(plain, "No classes today")

        prefs.setDisplayScale(DisplayScale.WidgetUi, 1.3f)
        val scaled = draw(todayWidget())

        assertTrue("$before", before > 0)
        assertEquals(before * 1.3f, outerPadding(scaled).toFloat(), 1f)
        assertEquals(beforeText, textSize(scaled, "No classes today"), 0.01f)
    }

    @Test
    fun `the library shortcut follows the widget text size too`() {
        val before = textSize(draw(LibraryShortcutWidget(preferencesOf = { prefs })), "Library")

        prefs.setDisplayScale(DisplayScale.WidgetText, 0.8f)
        val after = textSize(draw(LibraryShortcutWidget(preferencesOf = { prefs })), "Library")

        assertEquals(before * 0.8f, after, 0.01f)
    }

    private fun weekWidget(courses: List<Course>) = object : BaseClassTableWidget(
        WidgetLayout.Week,
        preferencesOf = { prefs },
        loadState = {
            WidgetState(
                courses = courses,
                activeWeekdays = listOf(1),
                activePeriodIds = listOf("1", "2", "3"),
                currentWeekday = 3,
                currentMinuteOfDay = 7 * 60,
                isLoggedIn = true,
                isQuietToday = false,
                ongoingBlocks = emptyList(),
                nextTodayBlock = null,
                tomorrowFirstCourseNo = null,
                tomorrowFirstCourseWeekday = null,
                tomorrowFirstCoursePeriodId = null,
                courseColors = emptyMap(),
                courseNameScale = 1f,
            )
        },
    ) {}

    /** The first conflict layer's top left corner: how many of its pixels are left clear by the rounding. */
    @OptIn(ExperimentalGlanceApi::class)
    private fun conflictCornerClearPixels(): Int = runBlocking {
        val widget = weekWidget(
            listOf(
                Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("1", "2"))),
                Course.fromSchedule("CS1002301", "Physics", schedule = mapOf(1 to listOf("2", "3"))),
            ),
        )
        val views: RemoteViews = withTimeout(10_000) {
            widget.runComposition(app, sizes = listOf(DpSize(240.dp, 240.dp)))
                .first { layers(it.apply(app, FrameLayout(app))).isNotEmpty() }
        }
        val layer = layers(views.apply(app, FrameLayout(app))).first()
        (0 until 12).sumOf { x -> (0 until 12).count { y -> Color.alpha(layer.getPixel(x, y)) < 128 } }
    }

    private fun layers(view: View): List<Bitmap> {
        val out = mutableListOf<Bitmap>()
        fun walk(v: View) {
            ((v as? ImageView)?.drawable as? BitmapDrawable)?.bitmap?.let { out += it }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xhdpi")
    fun `a conflict's corners round at the widget display size, as the other tiles' do`() {
        val before = conflictCornerClearPixels()

        prefs.setDisplayScale(DisplayScale.WidgetUi, 1.3f)
        val after = conflictCornerClearPixels()

        assertTrue("$before -> $after", before > 0)
        assertTrue("$before -> $after", after > before * 1.4)
    }

    @Test
    fun `the app's own sizes leave the widgets alone`() {
        val before = textSize(draw(todayWidget()), "No classes today")

        prefs.setDisplayScale(DisplayScale.AppText, 1.5f)
        prefs.setDisplayScale(DisplayScale.AppUi, 1.3f)

        assertEquals(before, textSize(draw(todayWidget()), "No classes today"), 0.01f)
    }
}
