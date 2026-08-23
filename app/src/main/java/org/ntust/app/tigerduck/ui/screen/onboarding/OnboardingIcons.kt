// Hand-drawn animated icons for the onboarding pages, and the palette
// they are tinted from. Separated because this is all drawing code —
// Canvas paths and infinite transitions — with no onboarding logic.

package org.ntust.app.tigerduck.ui.screen.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
internal fun PulsingIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "onboarding-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "onboarding-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    )
}
// iOS system color palette, dark-mode adapted. Used for the per-page accent
// tints on the onboarding pages so the icons match the iOS app exactly
// (privacy = blue, watch = red, login = green, notifications = orange).
@Composable
internal fun onboardingBlue(): Color =
    if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF)
@Composable
internal fun onboardingRed(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFF453A) else Color(0xFFFF3B30)
@Composable
internal fun onboardingGreen(): Color =
    if (isSystemInDarkTheme()) Color(0xFF32D74B) else Color(0xFF34C759)
@Composable
internal fun onboardingOrange(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFFF9500)
// Layered shield + inner lock: matches the iOS `OnboardingPageView.layerFlash`
// path. The shield holds steady at the accent color while the inner lock
// pulses its alpha — reads as a slow "flash" on the lock without disturbing
// the surrounding shield silhouette.
@Composable
internal fun FlashingShieldLockIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "shield-lock-flash")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shield-lock-flash-fraction",
    )
    val lockAlpha = 0.3f + 0.7f * pulse
    Box(
        modifier = modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = Color.White.copy(alpha = lockAlpha),
            modifier = Modifier
                .size(42.dp)
                .offset(y = (-2).dp),
        )
    }
}
// Person silhouette with a key badge in the lower-right — Material's closest
// approximation of iOS `person.badge.key.fill`. Whole composition pulses
// together (matching the iOS .symbolEffect(.pulse) on the single SF symbol).
@Composable
internal fun PersonKeyBadgeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "person-key-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "person-key-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(30.dp)
                .clip(CircleShape)
                .background(background)
                .padding(2.dp)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
// Bell with a small notification dot in the upper-right — Material's closest
// approximation of iOS `bell.badge.fill`. Pulses as one composition.
@Composable
internal fun BellBadgeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "bell-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bell-pulse-fraction",
    )
    val alpha = 0.45f + 0.55f * pulse
    val scale = 0.94f + 0.08f * pulse
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 10.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(background)
                .padding(2.dp)
                .clip(CircleShape)
                .background(tint),
        )
    }
}
