package org.ntust.app.tigerduck.util

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long the app was away, each time it comes back to the foreground: the whole app, whichever
 * screen it left from, which a single screen's own lifecycle cannot tell once it has been left for
 * another. A configuration change is not a trip away ([ProcessLifecycleOwner] sees through it).
 *
 * Watches from the moment something first asks for it, on the main thread as a view model is built.
 */
@Singleton
class AppReturns internal constructor(
    private val now: () -> Long,
    register: (LifecycleObserver) -> Unit,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, { ProcessLifecycleOwner.get().lifecycle.addObserver(it) })

    private var backgroundedAt: Long? = null

    private val _returns = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Milliseconds away, once per return; nothing for the start the observer catches up on. */
    val returns: SharedFlow<Long> = _returns.asSharedFlow()

    init {
        register(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = now()
            }

            override fun onStart(owner: LifecycleOwner) {
                backgroundedAt?.let { _returns.tryEmit(now() - it) }
                backgroundedAt = null
            }
        })
    }
}
