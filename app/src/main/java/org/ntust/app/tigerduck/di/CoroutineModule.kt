package org.ntust.app.tigerduck.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        // The exception handler is load-bearing: without it, an exception
        // escaping any `appScope.launch` is routed to the default uncaught
        // handler and kills the process. The v1.4.0 upgrade crash rode
        // exactly this path (safety-net wearBridge.publish() from
        // Application.onCreate). Best-effort background work should log,
        // not crash the app before the first frame.
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, t ->
                    Log.e("ApplicationScope", "Uncaught exception in application scope", t)
                }
        )
}
