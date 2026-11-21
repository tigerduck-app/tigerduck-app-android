package org.ntust.app.tigerduck.widget

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.ui.component.CourseChangeInkLight
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * The class widgets drawn with an assessment and an online class (openspec
 * `course-change-assessments`), in a light theme and English: the assessed course's name has the
 * dashed line under it and is read with the assessment, and the online class shows Online where
 * its room was, as a moved room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetCourseChangeKindsDrawTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var prefs: AppPreferences

    private val exam = Course.fromSchedule("CC901", "Exam", classroom = "T1-101", schedule = mapOf(4 to listOf("2", "3")))
    private val remote = Course.fromSchedule("CC902", "Remote", classroom = "T1-102", schedule = mapOf(4 to listOf("6")))
    private val feed = changes(
        row(1, "assessment", values = mapOf("note_zh" to "期中考", "note_en" to "Midterm exam"), courseNo = "CC901"),
        row(2, "online", courseNo = "CC902"),
    )

    @Before
    fun setUp() {
        app.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(app).apply {
            appLanguage = "en"
            themeMode = "light"
        }
    }

    private fun state(time: String): WidgetState = WidgetDataLoader.state(
        courses = listOf(exam, remote),
        now = thursday.atTime(LocalTime.parse(time)),
        isLoggedIn = true,
        quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
        changes = feed,
        courseNameScale = 1f,
    )

    private fun widget(layout: WidgetLayout, state: WidgetState) = object : BaseClassTableWidget(
        layout,
        if (layout == WidgetLayout.Today) SizeMode.Single else SizeMode.Exact,
        preferencesOf = { prefs },
        loadState = { _, _ -> state },
    ) {}

    /** The widget's views, laid out at 300 by 300. */
    @OptIn(ExperimentalGlanceApi::class)
    private fun drawn(widget: GlanceAppWidget): View = runBlocking {
        val views: RemoteViews = withTimeout(10_000) {
            widget.runComposition(app, sizes = listOf(DpSize(300.dp, 300.dp))).first { all(it.apply(app, FrameLayout(app))).isNotEmpty() }
        }
        val px = (300 * app.resources.displayMetrics.density).toInt()
        views.apply(app, FrameLayout(app)).apply {
            measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
            layout(0, 0, px, px)
        }
    }

    private fun all(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(view: View) {
            if (view is TextView && view.text.isNotEmpty() || view is ImageView) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun List<View>.named(text: String): TextView =
        filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
            ?: error("no \"$text\" in ${filterIsInstance<TextView>().map { it.text.toString() }}")

    /** [view] and its parents, as `class width`, for a failure to say where a width came from. */
    private fun chain(view: View): String = generateSequence(view) { it.parent as? View }.take(6)
        .joinToString(" < ") { "${it.javaClass.simpleName} ${it.width} ${it.layoutParams?.width}" }

    /** The dashes drawn under [name]: the image beside it in the box that holds the two. */
    private fun dashesUnder(name: TextView): ImageView? {
        var view: View = name
        while (true) {
            val parent = view.parent as? ViewGroup ?: return null
            val image = (0 until parent.childCount).map(parent::getChildAt).firstNotNullOfOrNull { child ->
                child as? ImageView ?: (child as? ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt).filterIsInstance<ImageView>().firstOrNull() }
            }
            if (image != null) return image
            if (parent.childCount > 1) return null
            view = parent
        }
    }

    private val assessment get() = app.getString(org.ntust.app.tigerduck.R.string.course_change_assessment)
    private val online get() = app.getString(org.ntust.app.tigerduck.R.string.course_change_online)

    @Test
    fun `the Today widget dashes the assessed name, reads it, and shows the online class's room as online`() {
        val views = all(drawn(widget(WidgetLayout.Today, state("08:00"))))

        val name = views.named("Exam")
        assertEquals("Exam, $assessment", name.contentDescription)
        val dashes = dashesUnder(name)!!
        assertEquals("as wide as the name: ${chain(name)} / ${chain(dashes)}", name.width, dashes.width)
        assertTrue(dashes.height > 0)
        assertNull(views.named("Remote").contentDescription)
        assertNull(dashesUnder(views.named("Remote")))
        assertEquals(CourseChangeInkLight.toArgb(), views.named("⇄ $online").currentTextColor)
        assertTrue(views.filterIsInstance<TextView>().none { it.text.toString() == "T1-102" })
    }

    @Test
    fun `the Next class widget dashes the assessed class going on`() {
        val name = all(drawn(widget(WidgetLayout.NextClass, state("09:30")))).named("Exam")

        assertEquals("Exam, $assessment", name.contentDescription)
        assertEquals("${chain(name)} / ${chain(dashesUnder(name)!!)}", name.width, dashesUnder(name)!!.width)
    }

    @Test
    fun `the Next class widget shows the online class's room as online`() {
        val views = all(drawn(widget(WidgetLayout.NextClass, state("13:00"))))

        views.named("Remote")
        assertEquals(CourseChangeInkLight.toArgb(), views.named("⇄ $online").currentTextColor)
    }

    @Test
    fun `the Week widget dashes a block over hidden period 5 for an assessment of its later meeting`() {
        val split = Course.fromSchedule("CC903", "Split", classroom = "T1-103", schedule = mapOf(4 to listOf("4", "6")))
        val state = WidgetDataLoader.state(
            courses = listOf(split),
            now = thursday.atTime(LocalTime.parse("08:00")),
            isLoggedIn = true,
            quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
            changes = changes(row(1, "assessment", periods = listOf("6"), values = mapOf("note_zh" to "小考", "note_en" to "Quiz"), courseNo = "CC903")),
            courseNameScale = 1f,
        )

        assertEquals("Split, $assessment", all(drawn(widget(WidgetLayout.Week, state))).named("Split").contentDescription)
    }

    @Test
    fun `the Week widget dashes the assessed block and names the online class's room`() {
        val views = all(drawn(widget(WidgetLayout.Week, state("08:00"))))

        val name = views.named("Exam")
        assertEquals("Exam, $assessment", name.contentDescription)
        assertEquals("${chain(name)} / ${chain(dashesUnder(name)!!)}", name.width, dashesUnder(name)!!.width)
        assertNull(dashesUnder(views.named("Remote")))
    }
}
