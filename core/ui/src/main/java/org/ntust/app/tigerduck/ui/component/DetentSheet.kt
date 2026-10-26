// A modal sheet that moves itself, as iOS's sheets do: softer and slower than Material3's, quicker
// to change height, and never thrown past its top. The course sheet fills the window and stops at
// half height on the way; the app's other sheets are as tall as their content.
//
// TODO(#502): Material3 1.4.0's ModalBottomSheet settles with a spring an app can't change (its
//  MotionScheme is internal), and its own drag throws the sheet past its top. So this sheet keeps
//  ModalBottomSheet only as its window and moves itself. Material3 1.5.0 makes the motion scheme
//  public; once 1.5.0 is stable, re-evaluate going back to Material's sheet with a softer scheme.

package org.ntust.app.tigerduck.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R

/** The heights a [DetentSheet] rests at. */
enum class DetentSheetValue { Hidden, Half, Full }

/** How a [DetentSheet] moves. */
object DetentSheetMotion {
    /**
     * How long the sheet takes to get where it's going. Material3's sheet settles with a spring
     * (damping 0.9, stiffness 700) in about 250 ms, and with a fling behind it, past its top and
     * back. This one takes a little longer and eases out on a curve that never passes its end, and
     * it is done when its time is up: a spring's tail, still moving by fractions of a pixel long
     * after it looks still, would leave the sheet taking every tap meant for what's on it as a grab
     * of its own.
     */
    const val DURATION_MILLIS = 300

    /** How long the sheet takes to fade see-through, or back. */
    const val SEE_THROUGH_MILLIS = 150

    /** A fling at least this fast moves the sheet on to the next height. Material's is 125 dp/s. */
    val VelocityThreshold: Dp = 80.dp

    /** Let go at least this far from the last height it passed, the sheet goes on to the next. Material's is 56 dp. */
    val PositionalThreshold: Dp = 32.dp

    /**
     * The curve's starting slope, as a multiple of its average speed over [distance]: the fling's
     * own [velocity] where it can, between 2, a gentle ease-out, and 3, the steepest a cubic can
     * start with and still not pass its end. A fling the other way eases out from 2 all the same.
     */
    fun startSlope(velocity: Float, distance: Float): Float {
        if (distance == 0f) return 0f
        val fling = if (sign(velocity) == sign(distance)) {
            abs(velocity) * DURATION_MILLIS / 1000f / abs(distance)
        } else {
            0f
        }
        return fling.coerceIn(2f, 3f)
    }

    /**
     * How far along the sheet is, from 0 to 1, a fraction [t] of the way through: a cubic that
     * starts at [slope] and comes to rest at its end. With a slope up to 3 it only ever goes forward.
     */
    fun progress(t: Float, slope: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return (3f * t2 - 2f * t3) + slope * (t3 - 2f * t2 + t)
    }
}

/** Where a [DetentSheet] is and how it moves there. Offsets are from the top of the window, in px. */
@Stable
class DetentSheetState(initialValue: DetentSheetValue, private val density: Density) {

    val draggable = AnchoredDraggableState(initialValue)

    /** The height the sheet last came to rest at. */
    val currentValue: DetentSheetValue get() = draggable.settledValue

    /** The height the sheet is on its way to, or rests at. */
    val targetValue: DetentSheetValue get() = draggable.targetValue

    /**
     * Whether the sheet rests out of sight. Not just named so: a finger on a sheet still rising
     * stops its move and leaves it named for where it set out from, out of sight, while it shows.
     */
    val isClosed: Boolean
        get() = currentValue == DetentSheetValue.Hidden && !draggable.isAnimationRunning &&
            abs(draggable.offset - draggable.anchors.positionOf(DetentSheetValue.Hidden)) < 0.5f

    /** Whether the sheet is up, at rest or moving: out of sight only once it has closed. */
    val isVisible: Boolean get() = currentValue != DetentSheetValue.Hidden

    /** Whether the sheet stops at half height: one taller than half the window, that asked to. */
    var hasHalf by mutableStateOf(false)
        private set

    /** How far down the window the sheet's top is, in px; NaN until it is laid out. */
    val offset: Float get() = draggable.offset

    /** Whether the sheet has started up since it was made; until then, resting out of sight isn't closing. */
    internal var hasShown by mutableStateOf(initialValue != DetentSheetValue.Hidden)
        private set

    /**
     * The heights for a [windowHeight] window and a [sheetHeight] sheet: out of sight below the
     * window, its whole height up at full, and half the window on the way if [halfHeight] and the
     * sheet is taller than that, as Material's sheet has it.
     *
     * A sheet without a half height has one all the same, at full: Foundation ends a move at the
     * height it set out for, and one that went away on the way, as a rotation can make it, would
     * leave the sheet with no place to be. Tied with full, full is the one found.
     */
    fun updateAnchors(windowHeight: Int, sheetHeight: Int, halfHeight: Boolean) {
        val h = windowHeight.toFloat()
        val half = halfHeight && sheetHeight > windowHeight / 2
        val full = (h - sheetHeight).coerceAtLeast(0f)
        val anchors = DraggableAnchors {
            DetentSheetValue.Hidden at h
            DetentSheetValue.Half at if (half) h / 2f else full
            DetentSheetValue.Full at full
        }
        if (hasHalf != half) hasHalf = half
        if (anchors == draggable.anchors) return
        // The height the sheet was at, or on its way to, by name: its offset in px could stand
        // for any height in a window of another size.
        val wanted = if (draggable.anchors.size == 0) draggable.currentValue else draggable.targetValue
        draggable.updateAnchors(anchors, resolve(wanted))
    }

    /** [value], or full for half height on a sheet that has none. */
    private fun resolve(value: DetentSheetValue): DetentSheetValue =
        if (value == DetentSheetValue.Half && !hasHalf) DetentSheetValue.Full else value

    /** Where the sheet's top is drawn: never above its full height, out of sight until laid out. */
    fun visibleOffset(): Int {
        val offset = draggable.offset
        if (offset.isNaN()) return OUT_OF_SIGHT
        return offset.coerceAtLeast(draggable.anchors.minPosition()).roundToInt()
    }

    /** The height a sheet let go at [offset], moving at [velocity], goes on to. */
    internal fun targetFor(offset: Float, velocity: Float): DetentSheetValue {
        val anchors = draggable.anchors
        val velocityThreshold = with(density) { DetentSheetMotion.VelocityThreshold.toPx() }
        if (abs(velocity) >= velocityThreshold) {
            return anchors.closestAnchor(offset, searchUpwards = velocity > 0f) ?: currentValue
        }
        val rested = anchors.positionOf(currentValue)
        if (rested.isNaN() || offset == rested) return anchors.closestAnchor(offset) ?: currentValue
        val down = offset > rested
        // The last height the sheet passed on its way here, and the next one ahead of it.
        val passed = anchors.closestAnchor(offset, searchUpwards = !down) ?: currentValue
        val ahead = anchors.closestAnchor(offset, searchUpwards = down) ?: currentValue
        val positionalThreshold = with(density) { DetentSheetMotion.PositionalThreshold.toPx() }
        return if (abs(offset - anchors.positionOf(passed)) >= positionalThreshold) ahead else passed
    }

    /** Settles a sheet let go moving at [velocity] at the height it goes on to. */
    suspend fun settle(velocity: Float) {
        val offset = draggable.offset
        if (offset.isNaN()) return
        animateTo(targetFor(offset, velocity), velocity)
    }

    /**
     * Moves the sheet to [value], starting at [velocity]. A sheet already there doesn't animate:
     * while it animates, it takes the next touch for its own, which a sheet at rest mustn't.
     */
    suspend fun animateTo(value: DetentSheetValue, velocity: Float = 0f) {
        val target = resolve(value)
        val to = draggable.anchors.positionOf(target)
        if (to.isNaN() || draggable.offset.isNaN()) return
        if (target != DetentSheetValue.Hidden) hasShown = true
        if (abs(to - draggable.offset) < 0.5f) {
            if (draggable.settledValue != target || draggable.isAnimationRunning) draggable.snapTo(target)
            return
        }
        draggable.anchoredDrag(target) { anchors, latest ->
            val from = draggable.offset
            val distance = anchors.positionOf(latest) - from
            val slope = DetentSheetMotion.startSlope(velocity, distance)
            animate(0f, 1f, animationSpec = tween(DetentSheetMotion.DURATION_MILLIS, easing = LinearEasing)) { t, _ ->
                dragTo(from + distance * DetentSheetMotion.progress(t, slope))
            }
        }
        // Set out for half height that went away on the way: it rests at full, under half's name.
        if (draggable.settledValue == DetentSheetValue.Half && !hasHalf) draggable.snapTo(DetentSheetValue.Full)
    }

    suspend fun hide() = animateTo(DetentSheetValue.Hidden)

    suspend fun expand() = animateTo(DetentSheetValue.Full)

    /** Down to half height, or up in full for a sheet that has none. */
    suspend fun partialExpand() = animateTo(DetentSheetValue.Half)

    /**
     * A drag on the sheet itself, let go: settled by a move of the sheet's own, in [scope], once
     * Foundation's drag is done. Settled inside that drag, the sheet would read as animating for
     * good after a window resize during it: Foundation's drag doesn't clear the target the resize
     * sets, and a sheet reading as animating takes the next touch for its own.
     */
    fun flingBehavior(scope: CoroutineScope) = object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            scope.launch { settle(initialVelocity) }
            return 0f
        }
    }

    /**
     * Between the sheet and its scrolling content, as Material's sheet does it: an upward drag
     * raises a sheet below full height before the content scrolls, and what the content leaves of a
     * drag or a fling moves the sheet.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f && source == NestedScrollSource.UserInput && !draggable.offset.isNaN()) {
                Offset(0f, draggable.dispatchRawDelta(available.y))
            } else {
                Offset.Zero
            }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && !draggable.offset.isNaN()) {
                Offset(0f, draggable.dispatchRawDelta(available.y))
            } else {
                Offset.Zero
            }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val offset = draggable.offset
            return if (available.y < 0f && !offset.isNaN() && offset > draggable.anchors.minPosition()) {
                settle(available.y)
                available
            } else {
                Velocity.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle(available.y)
            return available
        }
    }

    companion object {
        private const val OUT_OF_SIGHT = Int.MAX_VALUE / 2

        fun Saver(density: Density) = Saver<DetentSheetState, DetentSheetValue>(
            save = { it.currentValue },
            restore = { DetentSheetState(it, density) },
        )
    }
}

/** A [DetentSheetState] that comes back as it was after the activity is recreated, as for a change of dark mode. */
@Composable
fun rememberDetentSheetState(): DetentSheetState {
    val density = LocalDensity.current
    return rememberSaveable(saver = DetentSheetState.Saver(density)) {
        DetentSheetState(DetentSheetValue.Hidden, density)
    }
}

/**
 * A modal sheet that rises as it opens. Dragged or flung down out of sight, tapped outside, backed
 * out of (with [dismissOnBack]) or hidden through [state], it slides away and then calls
 * [onDismissed], once.
 *
 * As tall as its [content], or with [fillHeight] the whole window; [halfHeight] lets a sheet
 * taller than half the window stop there on the way up and down, as Material's sheet does. It opens
 * at [openAt], or by default as high as the first height it can stop at.
 *
 * [seeThrough] fades its background, its handle and the scrim away, so what is under it shows: the
 * content then draws what of itself should stay, as the browser's zoom controls do while the page
 * they zoom shows through.
 *
 * Material's [ModalBottomSheet] is only its window here: full screen, see-through, its own gestures,
 * scrim and back handling off. The sheet inside moves itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetentSheet(
    onDismissed: () -> Unit,
    state: DetentSheetState = rememberDetentSheetState(),
    halfHeight: Boolean = false,
    fillHeight: Boolean = false,
    openAt: DetentSheetValue? = null,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dismissOnBack: Boolean = true,
    seeThrough: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Up from the start and never moved: it only holds the window.
    val window = remember {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { 0f },
            velocityThreshold = { 0f },
            initialValue = SheetValue.Expanded,
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismissed,
        sheetState = window,
        sheetMaxWidth = Dp.Unspecified,
        sheetGesturesEnabled = false,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        // Sets the status and navigation bar icons the way the sheet's own color calls for.
        contentColor = contentColorFor(containerColor),
        scrimColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        DetentSheetLayer(
            state = state,
            onDismissed = onDismissed,
            halfHeight = halfHeight,
            fillHeight = fillHeight,
            openAt = openAt,
            containerColor = containerColor,
            dismissOnBack = dismissOnBack,
            seeThrough = seeThrough,
            content = content,
        )
    }
}

/** The scrim and the sheet, filling the window they're given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetentSheetLayer(
    state: DetentSheetState,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    halfHeight: Boolean = false,
    fillHeight: Boolean = false,
    openAt: DetentSheetValue? = null,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dismissOnBack: Boolean = true,
    seeThrough: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(onDismissed)
    val close: () -> Unit = remember(state, scope) { { scope.launch { state.hide() } } }
    val open by rememberUpdatedState(openAt)
    val flingBehavior = remember(state, scope) { state.flingBehavior(scope) }
    // 1 shown, 0 see-through. Read while drawing, so the fade doesn't recompose the sheet.
    val solid = animateFloatAsState(
        if (seeThrough) 0f else 1f,
        animationSpec = tween(DetentSheetMotion.SEE_THROUGH_MILLIS),
        label = "see-through",
    )

    // Rises once laid out; one that comes back with the activity is already up.
    LaunchedEffect(state) {
        snapshotFlow { state.draggable.anchors.size }.first { it > 0 }
        if (!state.hasShown) {
            state.animateTo(open ?: DetentSheetValue.Half)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.hasShown && state.isClosed }.first { it }
        dismissed()
    }
    // On its way up too: Material's own Back is off, so one that did nothing then would leave
    // Back doing nothing at all.
    BackHandler(
        enabled = dismissOnBack &&
            (state.currentValue != DetentSheetValue.Hidden || state.targetValue != DetentSheetValue.Hidden),
    ) {
        if (state.currentValue == DetentSheetValue.Full && state.hasHalf) {
            scope.launch { state.animateTo(DetentSheetValue.Half) }
        } else {
            close()
        }
    }

    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Scrim(visible = state.targetValue != DetentSheetValue.Hidden, onClose = close, solid = solid)
            Column(
                modifier = Modifier
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxWidth()
                    .nestedScroll(state.nestedScroll)
                    .anchoredDraggable(state.draggable, Orientation.Vertical, flingBehavior = flingBehavior)
                    .clip(BottomSheetDefaults.ExpandedShape)
                    .drawBehind { drawRect(containerColor, alpha = solid.value) }
                    .belowStatusBar(state, statusBarTop)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {
                Handle(state, onClose = close, solid = solid, seeThrough = seeThrough)
                content()
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scrim = measurables[0].measure(Constraints.fixed(width, height))
        // A sheet as tall as its content stops below the status bar, so its height never depends
        // on where it is: one that did would move its own heights as it moved.
        val tallest = if (fillHeight) height else (height - statusBarTop).coerceAtLeast(0)
        val sheet = measurables[1].measure(
            Constraints(maxWidth = width, minHeight = if (fillHeight) tallest else 0, maxHeight = tallest),
        )
        layout(width, height) {
            // Set before the sheet is placed, so its first frame is right.
            state.updateAnchors(height, sheet.height, halfHeight)
            scrim.place(0, 0)
            sheet.place((width - sheet.width) / 2, state.visibleOffset())
        }
    }
}

/**
 * Pads a sheet that fills the window by however much of the status bar its top is under, as
 * Material's sheet does: none at half height, all of it at full. The sheet stays the same height.
 * Read while laying out, so a moving sheet doesn't recompose.
 */
private fun Modifier.belowStatusBar(state: DetentSheetState, statusBarTop: Int): Modifier =
    layout { measurable, constraints ->
        val offset = state.draggable.offset
        val top = if (offset.isNaN()) 0 else (statusBarTop - offset.roundToInt()).coerceIn(0, statusBarTop)
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = (constraints.minHeight - top).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - top).coerceAtLeast(0),
            ),
        )
        layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
    }

/** Material's scrim: dims what's under the sheet, and a tap on it closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scrim(visible: Boolean, onClose: () -> Unit, solid: State<Float>) {
    // In step with the sheet it dims for.
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f,
        animationSpec = tween(DetentSheetMotion.DURATION_MILLIS),
        label = "scrim",
    )
    val closeLabel = stringResource(R.string.action_close)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha * solid.value }
            .background(BottomSheetDefaults.ScrimColor)
            .then(
                if (visible) {
                    Modifier
                        .pointerInput(onClose) { detectTapGestures { onClose() } }
                        .semantics(mergeDescendants = true) {
                            traversalIndex = 1f
                            contentDescription = closeLabel
                            onClick { onClose(); true }
                        }
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * Material's drag handle, with what Material's sheet gives it: a tap raises a sheet at half height
 * and closes one at full, and TalkBack offers the same as expand, collapse and dismiss. Its name is
 * Material's own, already translated; the actions take TalkBack's own names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Handle(state: DetentSheetState, onClose: () -> Unit, solid: State<Float>, seeThrough: Boolean) {
    val scope = rememberCoroutineScope()
    val expand = { scope.launch { state.expand() }; Unit }
    val collapse = { scope.launch { state.partialExpand() }; Unit }
    val canCollapse = state.currentValue == DetentSheetValue.Full && state.hasHalf
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = solid.value }
            // Out of sight, and out of TalkBack's way, while the sheet is see-through.
            .then(if (seeThrough) Modifier.clearAndSetSemantics {} else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // Out of sight, it neither raises nor closes the sheet.
            modifier = Modifier
                .clickable(enabled = !seeThrough) {
                    when (state.currentValue) {
                        DetentSheetValue.Half -> expand()
                        DetentSheetValue.Full -> onClose()
                        DetentSheetValue.Hidden -> Unit
                    }
                }
                .semantics(mergeDescendants = true) {
                    dismiss { onClose(); true }
                    if (state.currentValue == DetentSheetValue.Half) expand { expand(); true }
                    if (canCollapse) collapse { collapse(); true }
                },
        ) {
            BottomSheetDefaults.DragHandle()
        }
    }
}
