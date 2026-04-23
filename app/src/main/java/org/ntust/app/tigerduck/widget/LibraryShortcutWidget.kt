package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * 1x1 home-screen widget that deep-links straight to the library QR page.
 * The tap target encodes the sentinel `start_route = "libraryShortcut"`;
 * MainNavigation resolves it at launch time so an intervening feature-disable
 * toggle always routes the user to Settings with the enable-prompt instead
 * of a dead Library screen.
 */
class LibraryShortcutWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetThemeEntryPoint::class.java)
            .appPreferences()
        val isDark = resolveIsDark(prefs, context)
        val accent = resolveAccentColor(prefs, isDark)
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", ROUTE_SENTINEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            LibraryShortcutContent(isDark = isDark, accent = accent, onTap = tapAction)
        }
    }

    private fun resolveIsDark(prefs: AppPreferences, context: Context): Boolean =
        when (prefs.themeMode) {
            "dark" -> true
            "light" -> false
            else -> {
                val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }

    private fun resolveAccentColor(prefs: AppPreferences, isDark: Boolean): Color {
        val lightHex = prefs.accentColorHex
        val hex = if (isDark) AppPreferences.accentDarkVariant(lightHex) else lightHex
        return Color(0xFF000000L or (hex.toLong() and 0xFFFFFFL))
    }

    companion object {
        const val ROUTE_SENTINEL = "libraryShortcut"
    }
}

class LibraryShortcutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LibraryShortcutWidget()
}

@Composable
private fun LibraryShortcutContent(
    isDark: Boolean,
    accent: Color,
    onTap: Action,
) {
    val background = if (isDark) Color(0xFF1C1C1E) else Color.White
    val onSurface = if (isDark) Color.White else Color(0xFF1C1C1E)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .clickable(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(background))
                .cornerRadius(18.dp)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(32.dp)
                        .background(ColorProvider(accent))
                        .cornerRadius(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.widget_library_icon),
                        contentDescription = null,
                        modifier = GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                    )
                }
                Box(modifier = GlanceModifier.height(4.dp)) {}
                Text(
                    text = "圖書館",
                    style = TextStyle(
                        color = ColorProvider(onSurface),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}
