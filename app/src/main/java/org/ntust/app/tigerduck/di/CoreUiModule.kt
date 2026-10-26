package org.ntust.app.tigerduck.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ScreenCaptureProtectionSwitch
import org.ntust.app.tigerduck.ui.haptics.HapticPreferences

/** What :core:ui reads from the app, which it cannot depend on. */
@Module
@InstallIn(SingletonComponent::class)
object CoreUiModule {

    @Provides
    fun provideHapticPreferences(prefs: AppPreferences): HapticPreferences = prefs

    @Provides
    fun provideScreenCaptureProtectionSwitch(appState: AppState): ScreenCaptureProtectionSwitch = appState
}
