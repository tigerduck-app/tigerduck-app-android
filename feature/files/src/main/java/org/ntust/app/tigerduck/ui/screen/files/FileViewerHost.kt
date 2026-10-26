package org.ntust.app.tigerduck.ui.screen.files

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What [FileViewerActivity] takes from the app around it, which the viewer's module cannot see:
 * the display and text sizes and the look every screen of the app has, and how its links open.
 * :app's, bound in its Hilt graph (FilesModule).
 */
interface FileViewerHost {
    /** The sizes picked on the Display and text size page, read from [base] for attachBaseContext. */
    fun sizes(base: Context): ActivitySizes

    /** [content] in the app's theme, as every screen of the app draws it. */
    @Composable
    fun Theme(content: @Composable () -> Unit)

    /** Settings > "Open links with". */
    val browserPreference: String
}

/** Display and text sizes, laid over an Activity's configuration. */
interface ActivitySizes {
    /** [base] at these sizes. */
    fun wrap(base: Context): Context

    /**
     * Whether [now], a configuration change the Activity handled itself, leaves the sizes laid
     * over [attached], the configuration it was made from, stale.
     */
    fun isStale(attached: Configuration, now: Configuration): Boolean
}

/** The host before Hilt injects anything: attachBaseContext comes first. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FileViewerHostEntryPoint {
    fun fileViewerHost(): FileViewerHost
}
