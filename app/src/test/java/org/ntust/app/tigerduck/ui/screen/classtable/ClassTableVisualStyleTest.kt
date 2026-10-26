// Draws the class table's pieces under each interface style and reads the
// pixels back: TigerDuck keeps one block per course run, TAT gives each
// period a pastel tile of its own, drops the period times and bands the rows.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.FocusDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.ui.theme.pastelOutline
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassTableVisualStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val physics = Course(courseNo = "PH2002", courseName = "Physics")
    private val statistics =
        Course(courseNo = "MA3003", courseName = "Introduction to Probability and Statistics")

    // At density 1 a dp is a pixel, so the cell geometry reads straight off the image.
    private val width = 60
    private val cellHeight = 52

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    private fun draw(
        preset: VisualPreset,
        widthPx: Int,
        heightPx: Int,
        density: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalVisualStylePolicy provides VisualStylePolicy(preset),
                LocalDensity provides Density(density, 1f),
            ) {
                Box(Modifier.size(widthPx.dp, heightPx.dp).background(Color.White)) { content() }
            }
        }
    }

    private fun pixels(): PixelMap = composeRule.onRoot().captureToImage().toPixelMap()

    private fun soloCell(
        preset: VisualPreset,
        spanCount: Int,
        course: Course = calculus,
        hasAssignment: Boolean = false,
    ) = draw(preset, width, cellHeight * spanCount) { SoloCellContent(course, spanCount, hasAssignment) }

    @Composable
    private fun SoloCellContent(
        course: Course,
        spanCount: Int,
        hasAssignment: Boolean = false,
        cellWidth: Int = width,
        outlineRequired: Boolean = false,
    ) = SoloCourseCell(
        course = course,
        roomHint = null,
        spanCount = spanCount,
        dayColWidth = cellWidth.dp,
        cellHeight = cellHeight.dp,
        x = 0.dp,
        y = 0.dp,
        weekday = 1,
        hasAssignment = hasAssignment,
        assignmentsIgnored = false,
        outlineRequired = outlineRequired,
        onTap = {},
        onLongPress = {},
        onRename = {},
        onPickColor = {},
        onToggleIgnoreAssignments = null,
        onDelete = {},
    )

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    /** [colour] at [alpha] over white, as a translucent block shows on the test's white page. */
    private fun overWhite(colour: Color, alpha: Float) = Color(
        red = colour.red * alpha + (1 - alpha),
        green = colour.green * alpha + (1 - alpha),
        blue = colour.blue * alpha + (1 - alpha),
    )

    private val calculusColour get() = TigerDuckTheme.courseColorVibrant(calculus.courseNo)
    private val physicsColour get() = TigerDuckTheme.courseColorVibrant(physics.courseNo)
    private val chemistryColour get() = TigerDuckTheme.courseColorVibrant(chemistry.courseNo)

    // ---- a course's run ----

    @Test
    fun `TigerDuck draws a two-period run as one block across the row boundary`() {
        soloCell(VisualPreset.TigerDuck, spanCount = 2)
        val image = pixels()
        val block = overWhite(calculusColour, 0.5f)
        assertNear("first period", block, image[8, 8])
        // Beside the name, which is centred on the boundary.
        assertNear("row boundary", block, image[3, cellHeight])
        composeRule.onAllNodesWithText(calculus.courseName, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `TAT draws a pastel tile for each period with a gap between them`() {
        soloCell(VisualPreset.Tat, spanCount = 2)
        val image = pixels()
        val tile = calculusColour.pastel()
        assertNear("first tile", tile, image[8, 8])
        assertNear("second tile", tile, image[8, cellHeight + 8])
        assertNear("gap at the row boundary", Color.White, image[3, cellHeight])
        // Each tile names the course; the run stays one tap target and one TalkBack element.
        composeRule.onAllNodesWithText(calculus.courseName, useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `TAT tiles keep the light pastel in dark mode`() {
        TigerDuckTheme.setDarkMode(true)
        soloCell(VisualPreset.Tat, spanCount = 1)
        assertNear("tile", calculusColour.pastel(), pixels()[8, 8])
    }

    // ---- required courses ----

    private val requiredCalculus = calculus.copy(requireOption = "R")
    private val electiveCalculus = calculus.copy(requireOption = "E")

    // Drawn at 3x, so the 1dp outline has a solid middle pixel to read.
    private val outlineDensity = 3f

    /** The pixel in the middle of a 1dp outline whose outer edge sits at [edge] dp. */
    private fun outlineAt(edge: Float): Int = ((edge + 0.5f) * outlineDensity).toInt()

    private fun outlinedSoloCell(preset: VisualPreset, course: Course, outlineRequired: Boolean) =
        draw(preset, width, cellHeight, outlineDensity) {
            SoloCellContent(course, spanCount = 1, outlineRequired = outlineRequired)
        }

    @Test
    fun `TigerDuck outlines a required course in its own colour when the setting is on`() {
        outlinedSoloCell(VisualPreset.TigerDuck, requiredCalculus, outlineRequired = true)
        val image = pixels()
        // The block is inset 1dp in its cell; the outline runs just inside it.
        assertNear("outline", calculusColour, image[outlineAt(1f), outlineAt(cellHeight / 2f)])
        assertNear("fill", overWhite(calculusColour, 0.5f), image[outlineAt(8f), outlineAt(8f)])
    }

    @Test
    fun `a required course is not outlined until the setting is on`() {
        outlinedSoloCell(VisualPreset.TigerDuck, requiredCalculus, outlineRequired = false)
        assertNear("edge", overWhite(calculusColour, 0.5f), pixels()[outlineAt(1f), outlineAt(cellHeight / 2f)])
    }

    @Test
    fun `an elective course is not outlined`() {
        outlinedSoloCell(VisualPreset.TigerDuck, electiveCalculus, outlineRequired = true)
        assertNear("edge", overWhite(calculusColour, 0.5f), pixels()[outlineAt(1f), outlineAt(cellHeight / 2f)])
    }

    @Test
    fun `TAT outlines a required course's tile in the pastel outline`() {
        outlinedSoloCell(VisualPreset.Tat, requiredCalculus, outlineRequired = true)
        val image = pixels()
        assertNear("outline", calculusColour.pastelOutline(), image[outlineAt(1f), outlineAt(cellHeight / 2f)])
        assertNear("fill", calculusColour.pastel(), image[outlineAt(8f), outlineAt(8f)])
    }

    /**
     * As [outlinedSoloCell] under TAT, but inside the root theme with the
     * wallpaper's colors on: they recolor the accent, and a course keeps its
     * own color.
     */
    private fun outlinedTatCellUnderWallpaper(dark: Boolean) {
        TigerDuckTheme.setDarkMode(dark)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = dark, useDynamicColor = true, visualPreset = VisualPreset.Tat) {
                CompositionLocalProvider(LocalDensity provides Density(outlineDensity, 1f)) {
                    Box(Modifier.size(width.dp, cellHeight.dp).background(Color.White)) {
                        SoloCellContent(requiredCalculus, spanCount = 1, outlineRequired = true)
                    }
                }
            }
        }
    }

    @Test
    fun `under the wallpaper's light colors a TAT tile keeps its pastel and outline`() {
        outlinedTatCellUnderWallpaper(dark = false)
        val image = pixels()
        assertNear("outline", calculusColour.pastelOutline(), image[outlineAt(1f), outlineAt(cellHeight / 2f)])
        assertNear("fill", calculusColour.pastel(), image[outlineAt(8f), outlineAt(8f)])
    }

    @Test
    fun `under the wallpaper's dark colors a TAT tile keeps the light pastel and outline`() {
        outlinedTatCellUnderWallpaper(dark = true)
        val image = pixels()
        assertNear("outline", calculusColour.pastelOutline(), image[outlineAt(1f), outlineAt(cellHeight / 2f)])
        assertNear("fill", calculusColour.pastel(), image[outlineAt(8f), outlineAt(8f)])
    }

    @Test
    fun `a required course in a two-course conflict is outlined too`() {
        conflictCell(VisualPreset.TigerDuck, density = outlineDensity, courseA = requiredCalculus, outlineRequired = true)
        val image = pixels()
        // The first course is a Γ: its bar's top edge runs 1dp under the cell's top.
        assertNear("required course", calculusColour, image[outlineAt(width / 2f), outlineAt(1f)])
        // The second course is an L whose tail runs down the left edge, clear of its rounded
        // corners at mid-height; elective, it keeps its bare edge.
        assertNear("elective course", overWhite(physicsColour, 0.5f), image[outlineAt(1f), outlineAt(cellHeight.toFloat())])
    }

    @Test
    fun `a required course in a conflict of three or more is outlined in its lane`() {
        multiConflictCell(VisualPreset.TigerDuck, density = outlineDensity, courseA = requiredCalculus, outlineRequired = true)
        val image = pixels()
        // The first lane's course is inset 1dp in the cell and 0.5dp in its lane.
        assertNear("required course", calculusColour, image[outlineAt(1.5f), outlineAt(cellHeight.toFloat())])
        assertNear("elective course", overWhite(physicsColour, 0.5f), image[outlineAt(width - 2.5f), outlineAt(cellHeight.toFloat())])
    }

    // ---- conflicts ----

    /** With [marks], the first course has work due and the second's is ignored. */
    private fun conflictCell(
        preset: VisualPreset,
        marks: Boolean = false,
        density: Float = 1f,
        courseA: Course = calculus,
        outlineRequired: Boolean = false,
    ) {
        val role = CellRole.ConflictStart(
            courseA = courseA, spanA = 2, offsetA = 0,
            courseB = physics, spanB = 2, offsetB = 0,
            combinedSpan = 2,
        )
        draw(preset, width, cellHeight * 2, density) {
            ConflictCourseCell(
                cellRole = role,
                dayColWidth = width.dp,
                cellHeight = cellHeight.dp,
                x = 0.dp,
                y = 0.dp,
                weekday = 1,
                periodId = "1",
                hasAssignmentA = marks,
                hasAssignmentB = false,
                assignmentsIgnoredA = false,
                assignmentsIgnoredB = marks,
                outlineRequired = outlineRequired,
                onPickConflict = { _, _, _, _ -> },
                onLongPress = {},
                onRename = {},
                onPickColor = {},
                onToggleIgnoreAssignments = null,
                onDelete = {},
            )
        }
    }

    @Test
    fun `a two-course conflict keeps its translucent course colours under TigerDuck`() {
        conflictCell(VisualPreset.TigerDuck)
        val image = pixels()
        // The first course is a Γ along the top and right edges, the second an
        // L along the left and bottom ones.
        assertNear("first course", overWhite(calculusColour, 0.5f), image[width - 5, 6])
        assertNear("second course", overWhite(physicsColour, 0.5f), image[4, cellHeight * 2 - 6])
    }

    @Test
    fun `a two-course conflict is recoloured to pastels under TAT`() {
        conflictCell(VisualPreset.Tat)
        val image = pixels()
        assertNear("first course", calculusColour.pastel(), image[width - 5, 6])
        assertNear("second course", physicsColour.pastel(), image[4, cellHeight * 2 - 6])
    }

    /** With [marks], the first lane's course has work due and the second's is ignored. */
    private fun multiConflictCell(
        preset: VisualPreset,
        marks: Boolean = false,
        density: Float = 1f,
        courseA: Course = calculus,
        outlineRequired: Boolean = false,
    ) {
        val role = CellRole.MultiConflictStart(
            members = listOf(
                CellRole.MultiConflictStart.Member(courseA, span = 2, offset = 0, lane = 0, firstPeriodId = "1"),
                CellRole.MultiConflictStart.Member(physics, span = 2, offset = 0, lane = 1, firstPeriodId = "1"),
            ),
            combinedSpan = 2,
            laneCount = 2,
        )
        draw(preset, width, cellHeight * 2, density) {
            MultiConflictCourseCell(
                cellRole = role,
                dayColWidth = width.dp,
                cellHeight = cellHeight.dp,
                x = 0.dp,
                y = 0.dp,
                weekday = 1,
                hasAssignment = { marks && it == calculus.courseNo },
                assignmentsIgnored = { marks && it == physics.courseNo },
                outlineRequired = outlineRequired,
                onSelect = { _, _ -> },
                onLongPress = {},
                onRename = {},
                onPickColor = {},
                onToggleIgnoreAssignments = null,
                onDelete = {},
            )
        }
    }

    @Test
    fun `a conflict of three or more is recoloured to pastels under TAT`() {
        multiConflictCell(VisualPreset.Tat)
        val image = pixels()
        assertNear("first lane", calculusColour.pastel(), image[width / 4, 6])
        assertNear("second lane", physicsColour.pastel(), image[width * 3 / 4, 6])
    }

    // ---- lanes that widen (tigerduck-app-android#85, as the iPhone's tigerduck-app#153) ----

    private val chemistry = Course(courseNo = "CH4004", courseName = "Chemistry")

    /**
     * Calculus on rows 0-1, Physics on 0-2, Chemistry on 2-3, in two lanes: Chemistry meets
     * Physics on row 2 and is alone on row 3. At density 1 the inner area starts at (1, 1), its
     * lanes are 29px wide and its rows 51.5px tall.
     */
    private val chain = CellRole.MultiConflictStart(
        members = listOf(
            CellRole.MultiConflictStart.Member(calculus, span = 2, offset = 0, lane = 0, firstPeriodId = "1"),
            CellRole.MultiConflictStart.Member(physics, span = 3, offset = 0, lane = 1, firstPeriodId = "1"),
            CellRole.MultiConflictStart.Member(chemistry, span = 2, offset = 2, lane = 0, firstPeriodId = "3"),
        ),
        combinedSpan = 4,
        laneCount = 2,
    )

    private fun chainCell(
        role: CellRole.MultiConflictStart = chain,
        direction: LayoutDirection = LayoutDirection.Ltr,
        onFocusManager: (FocusManager, InputModeManager) -> Unit = { _, _ -> },
        onSelect: (Course) -> Unit = {},
    ) {
        draw(VisualPreset.TigerDuck, width, cellHeight * role.combinedSpan) {
            onFocusManager(LocalFocusManager.current, LocalInputModeManager.current)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MultiConflictCourseCell(
                  cellRole = role,
                  dayColWidth = width.dp,
                  cellHeight = cellHeight.dp,
                  x = 0.dp,
                  y = 0.dp,
                  weekday = 1,
                  hasAssignment = { false },
                  assignmentsIgnored = { false },
                  onSelect = { course, _ -> onSelect(course) },
                  onLongPress = {},
                  onRename = {},
                  onPickColor = {},
                  onToggleIgnoreAssignments = null,
                  onDelete = {},
                )
            }
        }
    }

    @Test
    fun `a course alone in a row of a cluster takes the whole width there`() {
        chainCell()
        val image = pixels()
        assertNear("Chemistry, alone on row 3, across the other lane", overWhite(chemistryColour, 0.5f), image[50, 160])
        assertNear("Physics beside it on row 2", overWhite(physicsColour, 0.5f), image[52, 112])
        assertNear("Chemistry in its own lane on row 2", overWhite(chemistryColour, 0.5f), image[8, 112])
    }

    @Test
    fun `a tap lands on the course drawn there, and each course is one button`() {
        var picked: Course? = null
        chainCell(onSelect = { picked = it })

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(3)
        composeRule.onRoot().performTouchInput { click(Offset(50f, 160f)) }
        assertEquals(chemistry, picked)
        composeRule.onRoot().performTouchInput { click(Offset(52f, 112f)) }
        assertEquals(physics, picked)
        composeRule.onRoot().performTouchInput { click(Offset(8f, 112f)) }
        assertEquals(chemistry, picked)
    }

    /**
     * A course whose name sits in a run narrower than its whole shape: Calculus in its own lane
     * beside Physics, then Statistics, and alone on the last row. The grid places by absolute
     * offsets, so in a right-to-left language the name must stay in that run, not at the shape's
     * right edge, where the shape is cut away.
     */
    @Test
    fun `in a right-to-left language a course's name stays in its own run`() {
        val role = CellRole.MultiConflictStart(
            members = listOf(
                CellRole.MultiConflictStart.Member(calculus, span = 5, offset = 0, lane = 0, firstPeriodId = "1"),
                CellRole.MultiConflictStart.Member(physics, span = 2, offset = 0, lane = 1, firstPeriodId = "1"),
                CellRole.MultiConflictStart.Member(statistics, span = 2, offset = 2, lane = 1, firstPeriodId = "3"),
            ),
            combinedSpan = 5,
            laneCount = 2,
        )
        chainCell(role, LayoutDirection.Rtl)

        val name = composeRule.onNodeWithText("Calculus", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Calculus's name runs ${name.left}..${name.right}, its lane 1..30", name.right.value <= 31f)
    }

    @Test
    fun `the keyboard stops once on each course`() {
        lateinit var focus: FocusManager
        lateinit var inputMode: InputModeManager
        chainCell(onFocusManager = { manager, mode -> focus = manager; inputMode = mode })
        // A clickable takes focus from a keyboard, not in touch mode.
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        fun focused() = listOf(calculus, physics, chemistry).firstOrNull { course ->
            runCatching {
                composeRule.onNode(hasContentDescription(course.courseName, substring = true)).assertIsFocused()
            }.isSuccess
        }?.courseName

        composeRule.onNode(hasContentDescription("Calculus", substring = true)).requestFocus()
        val stops = listOf(focused()) + (1..2).map {
            composeRule.runOnIdle { focus.moveFocus(FocusDirection.Next) }
            composeRule.waitForIdle()
            focused()
        }
        assertEquals(listOf("Calculus", "Physics", "Chemistry"), stops)
    }

    // ---- conflict marks ----

    // Drawn at 3x, so the 10dp marks have solid pixels to read.
    private val markDensity = 3f

    /**
     * The pixel of a 10dp mark whose top-left corner sits at ([left], [top])
     * dp: on the book's cover, clear of its bookmark, its edges and the slash
     * of the ignored mark.
     */
    private fun PixelMap.markAt(left: Float, top: Float): Color =
        this[((left + 7.5f) * markDensity).toInt(), ((top + 3f) * markDensity).toInt()]

    /** [ink] at [alpha] over the opaque [fill]. */
    private fun blend(ink: Color, alpha: Float, fill: Color) = Color(
        red = ink.red * alpha + fill.red * (1 - alpha),
        green = ink.green * alpha + fill.green * (1 - alpha),
        blue = ink.blue * alpha + fill.blue * (1 - alpha),
    )

    private val blockInk = Color(0xFF1C1C1E)

    // Inside the cell's 1dp inset: the first course's mark in its top-right
    // corner, the second's in its bottom-left, each at a 3dp inset.
    private val firstMarkLeft = width - 1 - 3 - 10f
    private val firstMarkTop = 1 + 3f
    private val secondMarkLeft = 1 + 3f
    private val secondMarkTop = cellHeight * 2 - 1 - 3 - 10f

    @Test
    fun `a two-course conflict keeps its marks at 70 percent under TigerDuck`() {
        conflictCell(VisualPreset.TigerDuck, marks = true, density = markDensity)
        val image = pixels()
        assertNear(
            "work due",
            blend(blockInk, 0.7f, overWhite(calculusColour, 0.5f)),
            image.markAt(firstMarkLeft, firstMarkTop),
        )
        assertNear(
            "work ignored",
            blend(blockInk, 0.7f, overWhite(physicsColour, 0.5f)),
            image.markAt(secondMarkLeft, secondMarkTop),
        )
    }

    @Test
    fun `a two-course conflict draws its marks at 80 percent on TAT pastels`() {
        conflictCell(VisualPreset.Tat, marks = true, density = markDensity)
        val image = pixels()
        val ink = VisualStylePolicy.TextOnPastel
        assertNear("work due", blend(ink, 0.8f, calculusColour.pastel()), image.markAt(firstMarkLeft, firstMarkTop))
        assertNear("work ignored", blend(ink, 0.8f, physicsColour.pastel()), image.markAt(secondMarkLeft, secondMarkTop))
    }

    // Each lane is 29dp of the cell's 58, its course inset 0.5dp; the mark sits
    // in the lane's bottom-right corner at a 3dp inset.
    private val laneMarkLeft = 1 + 29 - 0.5f - 3 - 10
    private val laneMarkTop = cellHeight * 2 - 1 - 0.5f - 3 - 10

    @Test
    fun `a conflict of three or more keeps its marks at 70 percent under TigerDuck`() {
        multiConflictCell(VisualPreset.TigerDuck, marks = true, density = markDensity)
        val image = pixels()
        assertNear(
            "work due",
            blend(blockInk, 0.7f, overWhite(calculusColour, 0.5f)),
            image.markAt(laneMarkLeft, laneMarkTop),
        )
        assertNear(
            "work ignored",
            blend(blockInk, 0.7f, overWhite(physicsColour, 0.5f)),
            image.markAt(laneMarkLeft + 29, laneMarkTop),
        )
    }

    @Test
    fun `a conflict of three or more draws its marks at 80 percent on TAT pastels`() {
        multiConflictCell(VisualPreset.Tat, marks = true, density = markDensity)
        val image = pixels()
        val ink = VisualStylePolicy.TextOnPastel
        assertNear("work due", blend(ink, 0.8f, calculusColour.pastel()), image.markAt(laneMarkLeft, laneMarkTop))
        assertNear(
            "work ignored",
            blend(ink, 0.8f, physicsColour.pastel()),
            image.markAt(laneMarkLeft + 29, laneMarkTop),
        )
    }

    // ---- period column and rows ----

    private val firstPeriod = TimetablePeriod(id = "1", startTime = "08:10", endTime = "09:00")

    @Test
    fun `the period column shows the times under TigerDuck`() {
        draw(VisualPreset.TigerDuck, 40, cellHeight) { ClassTablePeriodLabel(firstPeriod, cellHeight.dp) }
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("08:10").assertExists()
        composeRule.onNodeWithText("09:00").assertExists()
    }

    @Test
    fun `the period column shows only the period under TAT`() {
        draw(VisualPreset.Tat, 40, cellHeight) { ClassTablePeriodLabel(firstPeriod, cellHeight.dp) }
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("08:10").assertDoesNotExist()
        composeRule.onNodeWithText("09:00").assertDoesNotExist()
    }

    // ---- the corner mark ----

    @Test
    fun `a one-period TAT tile keeps a long name clear of the corner mark`() {
        soloCell(VisualPreset.Tat, spanCount = 1, course = statistics, hasAssignment = true)
        val name = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
        // The tile ends 1dp inside the cell; the mark is 12dp at a 3dp inset.
        val tileBottom = cellHeight - 1f
        val clearance = tileBottom - name.boundsInRoot.bottom
        assertTrue("the name ends $clearance dp above the tile's bottom", clearance >= 13f)
    }

    // ---- empty slots ----

    private fun emptySlot(preset: VisualPreset): Color {
        var surfaceVariant = Color.Unspecified
        draw(preset, width, cellHeight) {
            surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
            ClassTableEmptySlot(Modifier.size(width.dp, cellHeight.dp))
        }
        return surfaceVariant
    }

    @Test
    fun `TigerDuck draws an empty slot as a faint tile`() {
        val surfaceVariant = emptySlot(VisualPreset.TigerDuck)
        assertNear("empty slot", overWhite(surfaceVariant, 0.3f), pixels()[width / 2, cellHeight / 2])
    }

    @Test
    fun `TAT leaves an empty slot bare`() {
        emptySlot(VisualPreset.Tat)
        assertNear("empty slot", Color.White, pixels()[width / 2, cellHeight / 2])
    }

    // ---- period column ----

    @Test
    fun `the period column narrows to the period alone under TAT`() {
        val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
        val tat = VisualStylePolicy(VisualPreset.Tat)
        assertEquals(36.dp, tigerDuck.classTablePeriodColumnWidth(fontScale = 1f))
        assertEquals(20.dp, tat.classTablePeriodColumnWidth(fontScale = 1f))
        // Both grow with a large font, from where the scaled width passes the floor.
        assertEquals(42.dp, tigerDuck.classTablePeriodColumnWidth(fontScale = 1.5f))
        assertEquals(24.dp, tat.classTablePeriodColumnWidth(fontScale = 1.5f))
    }

    // ---- row bands ----

    private val headerHeight = 20
    private val inset = 4

    /**
     * The grid's frame: the bands on the outside, the padding inside them, then
     * a header and a two-period run, as TimetableGrid lays them out.
     */
    private fun bandedGrid(preset: VisualPreset) = draw(preset, width, headerHeight + cellHeight * 2) {
        Box(
            Modifier
                .fillMaxSize()
                .classTableRowBands(
                    LocalVisualStylePolicy.current,
                    MaterialTheme.colorScheme.onSurface,
                    rowCount = 2,
                    cellHeight = cellHeight.dp,
                )
                .padding(horizontal = inset.dp)
        ) {
            Column {
                Spacer(Modifier.height(headerHeight.dp))
                Box { SoloCellContent(calculus, spanCount = 2, cellWidth = width - 2 * inset) }
            }
        }
    }

    @Test
    fun `TAT bands the period rows from edge to edge, under the cells`() {
        bandedGrid(VisualPreset.Tat)
        val image = pixels()
        val firstRow = headerHeight + 8
        val secondRow = headerHeight + cellHeight + 8
        assertNear("header", Color.White, image[1, 5])
        // Inside the grid's side padding: the bands reach the edge.
        val first = image[1, firstRow]
        val second = image[1, secondRow]
        assertTrue("first band $first is darker than the second $second", first.red < second.red)
        assertTrue("second band $second is not bare", second.red < 1f)
        // The cells sit on top, and the band shows only in the gap between tiles.
        assertNear("first tile", calculusColour.pastel(), image[inset + 8, firstRow])
        assertNear("second tile", calculusColour.pastel(), image[inset + 8, secondRow])
        assertNear("gap between the tiles", second, image[inset + 3, headerHeight + cellHeight])
    }

    @Test
    fun `TigerDuck leaves the rows plain`() {
        bandedGrid(VisualPreset.TigerDuck)
        val image = pixels()
        assertNear("first row", Color.White, image[1, headerHeight + 8])
        assertNear("second row", Color.White, image[1, headerHeight + cellHeight + 8])
    }
}
