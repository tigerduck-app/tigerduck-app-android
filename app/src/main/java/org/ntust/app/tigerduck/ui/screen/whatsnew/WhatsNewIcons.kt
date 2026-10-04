package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon names `whatsnew.json` summary rows may use. Kept as an explicit
 * map rather than a reflective lookup on [Icons] so R8 can't strip an icon a
 * JSON string refers to, and so a typo lands on [fallback] instead of a
 * crash. Add names here when a release needs one.
 */
object WhatsNewIcons {
    private val byName: Map<String, ImageVector> = mapOf(
        "announcement" to Icons.Filled.Campaign,
        "calendar" to Icons.Filled.CalendarMonth,
        "classtable" to Icons.Filled.TableChart,
        "grades" to Icons.Filled.School,
        "language" to Icons.Filled.Language,
        "layout" to Icons.Filled.Dashboard,
        "library" to Icons.Filled.Book,
        "mail" to Icons.Filled.Email,
        "notification" to Icons.Filled.Notifications,
        "privacy" to Icons.Filled.Lock,
        "qr" to Icons.Filled.QrCode2,
        "search" to Icons.Filled.Search,
        "settings" to Icons.Filled.Settings,
        "speed" to Icons.Filled.Speed,
        "sync" to Icons.Filled.Sync,
        "cloud" to Icons.Filled.CloudSync,
        "theme" to Icons.Filled.Palette,
        "widget" to Icons.Filled.Widgets,
    )

    /** Neutral glyph for a missing or unknown name. */
    val fallback: ImageVector = Icons.Filled.AutoAwesome

    fun named(name: String?): ImageVector = name?.let { byName[it.lowercase()] } ?: fallback
}
