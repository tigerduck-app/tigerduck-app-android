package org.ntust.app.tigerduck.di

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.ui.AppScale
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.screen.files.ActivitySizes
import org.ntust.app.tigerduck.ui.screen.files.FileViewerHost
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme

/** What :feature:files reads from the app, which it cannot depend on. */
@Module
@InstallIn(SingletonComponent::class)
object FilesModule {

    @Provides
    fun provideFileViewerHost(appState: AppState): FileViewerHost = AppFileViewerHost(appState)
}

/** The file viewer's look, sizes and links, from [appState] and the stored sizes, as MainActivity has them. */
private class AppFileViewerHost(private val appState: AppState) : FileViewerHost {

    override fun sizes(base: Context): ActivitySizes {
        val scale = AppScale.stored(base)
        return object : ActivitySizes {
            override fun wrap(base: Context): Context = scale.wrap(base)
            override fun isStale(attached: Configuration, now: Configuration): Boolean = scale.isStale(attached, now)
        }
    }

    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        val dark = when (appState.themeMode) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
        TigerDuckAppTheme(
            darkTheme = dark,
            accentColor = appState.accentColor(dark),
            onAccentColor = appState.accentOnColor(dark),
            accentGradient = appState.accentGradient(dark),
            useDynamicColor = appState.usesWallpaperColors,
            visualPreset = appState.visualPreset,
            content = content,
        )
    }

    override val browserPreference: String get() = appState.browserPreference
}
