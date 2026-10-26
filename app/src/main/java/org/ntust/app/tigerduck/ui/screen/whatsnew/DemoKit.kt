package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The pieces the feature demos are built from: a small screen to draw in, a
// clock that loops through a demo's steps, a fingertip that taps, and grey
// lines that stand in for text. A demo shows a still frame of its main step
// when animations are off.

/** How wide and tall a demo's [MockScreen] is. */
internal val MockScreenWidth = 220.dp
internal val MockScreenHeight = 250.dp

/**
 * Where a demo is in its loop, 0 to 1 over [millis] and again; [still] when
 * [animate] is off. Read it while drawing, or through `derivedStateOf`, so the
 * demo doesn't recompose on every frame.
 */
@Composable
internal fun demoClock(animate: Boolean, millis: Int, still: Float): State<Float> {
    if (!animate) return remember(still) { mutableFloatStateOf(still) }
    return rememberInfiniteTransition(label = "demo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(millis, easing = LinearEasing), RepeatMode.Restart),
        label = "demo clock",
    )
}

/** How far [t] is through [from]..[to], eased: 0 before it, 1 after. */
internal fun span(t: Float, from: Float, to: Float, easing: Easing = FastOutSlowInEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

/**
 * A small phone screen the demo draws in, clipped to its rounded corners.
 * TalkBack hears [description], one line for the whole demo, rather than its
 * pieces; null leaves it silent, for a demo that describes itself as a whole
 * ([demoDescription]).
 */
@Composable
internal fun MockScreen(
    description: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = MockScreenWidth,
    height: Dp = MockScreenHeight,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val targets = remember { TapTargets() }
    Box(
        modifier
            .width(width)
            .height(height)
            .onPlaced { targets.screen = it }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clearAndSetSemantics { if (description != null) contentDescription = description },
    ) {
        CompositionLocalProvider(LocalTapTargets provides targets) { content() }
    }
}

/**
 * The parts a demo's fingertip taps ([tapTarget]), so the fingertip lands on
 * them whatever the fonts make of the rows above: a position worked out in dp
 * drifts on a phone whose CJK font is taller. Their coordinates are live, so
 * they're measured where they are now: a graphics layer sliding a screen in
 * moves a target without laying it out again.
 */
internal class TapTargets {
    /** The mock screen, once it's placed. */
    var screen by mutableStateOf<LayoutCoordinates?>(null)

    /** Each target, once it's placed. */
    val placed = mutableStateMapOf<String, LayoutCoordinates>()

    /** [id]'s center as seen from [from], the mock screen's top left unless given, in px; null until both are placed. */
    fun center(id: String, from: LayoutCoordinates? = screen): Offset? {
        val target = placed[id]?.takeIf { it.isAttached } ?: return null
        val here = from?.takeIf { it.isAttached } ?: return null
        return here.localPositionOf(target, Offset(target.size.width / 2f, target.size.height / 2f))
    }
}

internal val LocalTapTargets = staticCompositionLocalOf<TapTargets?> { null }

/** Marks what the fingertip tapping [id] lands on. */
@Composable
internal fun Modifier.tapTarget(id: String): Modifier {
    val targets = LocalTapTargets.current ?: return this
    return onPlaced { targets.placed[id] = it }
}

/**
 * One line TalkBack hears for a whole demo, its screens and captions with it:
 * a caption faded out would otherwise still be read.
 */
internal fun Modifier.demoDescription(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }

/**
 * A fingertip at [x], [y] from the screen's top left: shown by [shown] (0 to
 * 1), and pressed by [press] (0 to 1), when it shrinks a little and a ring
 * spreads from it.
 */
@Composable
internal fun Fingertip(x: Dp, y: Dp, shown: () -> Float, press: () -> Float) {
    FingertipAt({ Offset(x.toPx(), y.toPx()) }, shown, press)
}

/** A fingertip on the part marked [target] with [tapTarget], wherever that is now; nowhere until it's placed. */
@Composable
internal fun Fingertip(target: String, shown: () -> Float, press: () -> Float) {
    val targets = LocalTapTargets.current
    FingertipAt({ here -> targets?.center(target, from = here) ?: Offset(-1_000f, -1_000f) }, shown, press)
}

/**
 * The fingertip's ring and dot, centered on what [at] gives, from where the
 * fingertip is placed. Moved by their layers, which follow the demo's clock,
 * so a target that's moving is followed.
 */
@Composable
private fun FingertipAt(at: Density.(here: LayoutCoordinates?) -> Offset, shown: () -> Float, press: () -> Float) {
    val color = MaterialTheme.colorScheme.onSurface
    var here by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier
            .onPlaced { here = it }
            .size(28.dp)
            .graphicsLayer {
                at(here).let { translationX = it.x - 14.dp.toPx(); translationY = it.y - 14.dp.toPx() }
                val p = press()
                alpha = shown() * (1f - p) * 0.9f
                scaleX = 1f + p * 1.2f
                scaleY = scaleX
            }
            .border(2.dp, color.copy(alpha = 0.5f), CircleShape),
    )
    Box(
        Modifier
            .size(28.dp)
            .graphicsLayer {
                at(here).let { translationX = it.x - 14.dp.toPx(); translationY = it.y - 14.dp.toPx() }
                alpha = shown() * 0.9f
                val s = 1f - press() * 0.15f
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
    )
}

/**
 * A grey line standing in for text: [fraction] of the width it's given,
 * [height] tall, [strong] for a title.
 */
@Composable
internal fun TextLine(fraction: Float, height: Dp = 6.dp, strong: Boolean = false, color: Color? = null) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = if (strong) 0.5f else 0.2f)),
    )
}

/**
 * Small text inside a mock screen: one line, cut short rather than wrapped.
 * It keeps its [size] at any font scale, as the screen keeps its own, unless
 * it [scales], as the display size demo's preview does.
 */
@Composable
internal fun MockText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 11.sp,
    weight: FontWeight = FontWeight.Medium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    scales: Boolean = false,
) {
    val fontSize = if (scales) size else with(LocalDensity.current) { size.value.dp.toSp() }
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontSize = fontSize,
        // The style's line height would grow with the font scale; this one keeps to the size.
        lineHeight = fontSize * 1.3f,
        fontWeight = weight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A pill under a demo's screen naming the step it shows, faded by [alpha]. */
@Composable
internal fun DemoCaption(text: String, alpha: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha() }
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        MockText(text, size = 12.sp, weight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * 1 through [from]..[to] of a loop, fading in over its first [fade] and out
 * over its last; 0 outside it.
 */
internal fun window(t: Float, from: Float, to: Float, fade: Float = 0.02f): Float =
    span(t, from, from + fade, LinearEasing) * (1f - span(t, to - fade, to, LinearEasing))

/**
 * Something that looks like a QR code, [size] square: three finder squares and
 * a fixed scatter of modules, another for another [seed]. It encodes nothing.
 */
@Composable
internal fun MockQr(size: Dp, modifier: Modifier = Modifier, seed: Int = 0x2545F491) {
    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 12))
            .background(Color.White)
            .padding(size / 14),
    ) {
        val cell = this.size.width / QR_CELLS
        fun module(x: Int, y: Int) = drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
        fun finder(x: Int, y: Int) {
            for (i in 0 until 7) for (j in 0 until 7) {
                val ring = i == 0 || j == 0 || i == 6 || j == 6
                val core = i in 2..4 && j in 2..4
                if (ring || core) module(x + i, y + j)
            }
        }
        finder(0, 0)
        finder(QR_CELLS - 7, 0)
        finder(0, QR_CELLS - 7)
        // A fixed scatter: the same "code" on every frame and every device.
        var seed = seed
        for (y in 0 until QR_CELLS) for (x in 0 until QR_CELLS) {
            val inFinder = (x < 8 && y < 8) || (x >= QR_CELLS - 8 && y < 8) || (x < 8 && y >= QR_CELLS - 8)
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            if (!inFinder && ((seed and 3) == 0 || (seed and 7) == 1)) module(x, y)
        }
    }
}

private const val QR_CELLS = 21

/** A switch, as far on as [on] says (0 to 1): its thumb slides and its track fills. */
@Composable
internal fun MockSwitch(on: () -> Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val offTrack = colors.surfaceContainerHighest
    val onTrack = colors.primary
    val offThumb = colors.outline
    val onThumb = colors.onPrimary
    Box(
        modifier
            .width(30.dp)
            .height(18.dp)
            .drawBehind {
                drawRoundRect(lerp(offTrack, onTrack, on()), cornerRadius = CornerRadius(size.height / 2))
            }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer { translationX = on() * 12.dp.toPx() }
                .drawBehind { drawCircle(lerp(offThumb, onThumb, on())) },
        )
    }
}
