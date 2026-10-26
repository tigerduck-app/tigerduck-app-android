// The fade a timetable cell plays when its courses change, as when the page switches between
// the user's class table and someone else's (#214): only the cells whose courses differ fade.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 0.25 s in all, as on iOS: the old courses fade out, then the new ones in. */
internal const val COURSE_FADE_OUT_MS = 100
internal const val COURSE_FADE_IN_MS = 150

/**
 * Shows [content] for [target], and when [target]'s [key] changes, fades the content of the last
 * target out and then the new one in. The same key keeps the content as it is, a new colour or
 * mark included: those change in place. Nothing fades the first time a slot is drawn.
 *
 * The old target is kept, not the old content: Compose updates a composable lambda in place, so a
 * lambda kept from the last composition would draw the new courses as the leaving ones.
 */
@Composable
internal fun <T> CourseSlotFade(
    target: T,
    key: (T) -> Any?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val targetKey = key(target)
    // The key and target of the last composition that went through, read when the key changes.
    val last = remember { LastTarget(targetKey, target) }
    val leaving: LastTarget<T>? = remember(targetKey) {
        if (last.key == targetKey) null else LastTarget(last.key, last.target)
    }
    val enter = remember(targetKey) { Animatable(if (leaving == null) 1f else 0f) }
    val exit = remember(targetKey) { Animatable(1f) }
    var left by remember(targetKey) { mutableStateOf(leaving == null) }
    LaunchedEffect(targetKey) {
        if (leaving == null) return@LaunchedEffect
        exit.animateTo(0f, tween(COURSE_FADE_OUT_MS, easing = EaseIn))
        left = true
        enter.animateTo(1f, tween(COURSE_FADE_IN_MS, easing = EaseOut))
    }
    SideEffect {
        last.key = targetKey
        last.target = target
    }
    // Keyed in one list, so the content on screen moves into the leaving place as it is, text
    // layout and all, rather than being drawn anew.
    val shown = buildList {
        if (!left && leaving != null) add(leaving.key to leaving.target)
        add(targetKey to target)
    }
    Box(modifier) {
        shown.forEach { (shownKey, shownTarget) ->
            key(shownKey) {
                val alpha = if (shownKey == targetKey) enter else exit
                Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content(shownTarget) }
            }
        }
    }
}

private class LastTarget<T>(var key: Any?, var target: T)
