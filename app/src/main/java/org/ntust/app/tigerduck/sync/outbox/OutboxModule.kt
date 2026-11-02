package org.ntust.app.tigerduck.sync.outbox

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * The outbox's channels, a set each channel joins from its own file
 * (`@Provides @IntoSet fun channel(q: Queue): OutboxChannel = q`), so no file lists them. Declared
 * here so the set exists while it is empty.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OutboxModule {
    @Multibinds
    abstract fun channels(): Set<OutboxChannel>
}
