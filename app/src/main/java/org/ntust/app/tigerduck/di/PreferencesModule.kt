package org.ntust.app.tigerduck.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.FirstTriggerSeenStore

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    fun provideFirstTriggerSeenStore(prefs: AppPreferences): FirstTriggerSeenStore = prefs
}
