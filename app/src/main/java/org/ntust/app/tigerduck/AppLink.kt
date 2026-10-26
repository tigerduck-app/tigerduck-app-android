package org.ntust.app.tigerduck

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A `tigerduck://` link: what a widget, a notification or another app asks
 * the app to open. Mirrors iOS's `WidgetURLRouter`: `tigerduck://library`,
 * `tigerduck://classtable` and `tigerduck://assignment/<id>` open from
 * anywhere, and `tigerduck://announcement/<id>` is what a bulletin push
 * opens.
 *
 * [MainActivity] is exported, so a link can come from any app. [parse] is the
 * one gate: it accepts these four shapes exactly and nothing else. An id is
 * digits only, a link carries no other path, and no link names a URL or a
 * route of its own, so none can send the app anywhere but these screens.
 */
sealed interface AppLink {
    /** The Library QR page, or Settings when the library feature is off. */
    data object Library : AppLink

    /** The user's own class table. */
    data object ClassTable : AppLink

    /** A Moodle assignment, whose page opens in the app. [id] is digits only. */
    data class Assignment(val id: String) : AppLink

    /** A bulletin's detail page. */
    data class Announcement(val id: Int) : AppLink

    /** This link as a URI. */
    val uri: Uri
        get() = Uri.parse(uriString)

    /** This link as [uri] spells it. */
    val uriString: String
        get() = when (this) {
            Library -> "$SCHEME://$HOST_LIBRARY"
            ClassTable -> "$SCHEME://$HOST_CLASS_TABLE"
            is Assignment -> "$SCHEME://$HOST_ASSIGNMENT/$id"
            is Announcement -> "$SCHEME://$HOST_ANNOUNCEMENT/$id"
        }

    companion object {
        const val SCHEME = "tigerduck"
        private const val HOST_LIBRARY = "library"
        private const val HOST_CLASS_TABLE = "classtable"
        private const val HOST_ASSIGNMENT = "assignment"
        private const val HOST_ANNOUNCEMENT = "announcement"

        /** Moodle ids are 32-bit; this leaves room without letting a link carry an essay. */
        private const val MAX_ID_DIGITS = 18

        fun parse(uri: Uri?): AppLink? = uri?.let { parse(it.scheme, it.host, it.pathSegments) }

        /**
         * The link [scheme], [host] and the decoded [pathSegments] spell, or
         * null for anything else. Matched exactly, as the manifest's intent
         * filter matches them: lower case, nothing extra.
         */
        fun parse(scheme: String?, host: String?, pathSegments: List<String>): AppLink? {
            if (scheme != SCHEME) return null
            return when (host) {
                HOST_LIBRARY -> Library.takeIf { pathSegments.isEmpty() }
                HOST_CLASS_TABLE -> ClassTable.takeIf { pathSegments.isEmpty() }
                HOST_ASSIGNMENT -> pathSegments.singleOrNull()
                    ?.takeIf(::isId)
                    ?.let(::Assignment)
                HOST_ANNOUNCEMENT -> pathSegments.singleOrNull()
                    ?.takeIf(::isId)
                    ?.toIntOrNull()
                    ?.let(::Announcement)
                else -> null
            }
        }

        /** The link to [assignmentId], or null when it is not an id a link can carry. */
        fun assignment(assignmentId: String): Assignment? =
            assignmentId.takeIf(::isId)?.let(::Assignment)

        /** ASCII digits only: `Char.isDigit` would also take other scripts' digits. */
        private fun isId(value: String): Boolean =
            value.length in 1..MAX_ID_DIGITS && value.all { it in '0'..'9' }
    }
}

/**
 * The [MainActivity] intent that opens [link], for a notification or a widget
 * of this app's own. Explicit, so nothing else can answer it.
 */
fun AppLink.activityIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/**
 * A tap that opens [link]. Immutable. The link is the intent's data, so two
 * links never share a PendingIntent, and each link keeps its own request code
 * as well.
 */
fun AppLink.pendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        uriString.hashCode(),
        activityIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** A tap that only opens the app, on whichever screen it was left on. */
fun openAppPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
