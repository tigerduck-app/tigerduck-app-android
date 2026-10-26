package org.ntust.app.tigerduck.analytics

import android.content.Context
import org.ntust.app.tigerduck.data.preferences.AppPreferences

/** An [AnalyticsLogger] for a JVM test; its constructor differs by flavor. */
fun testAnalyticsLogger(context: Context): AnalyticsLogger = AnalyticsLogger(context, AppPreferences(context))
