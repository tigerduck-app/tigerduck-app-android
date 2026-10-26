package org.ntust.app.tigerduck.files

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import org.ntust.app.tigerduck.ui.screen.files.FileViewerActivity

/**
 * Opens a downloaded file: in the app's own viewer when [FileViewing] says it can show it,
 * otherwise as the caller always has, in another app or the share sheet ([elsewhere]).
 */
object FileViewer {
    const val EXTRA_NAME = "org.ntust.app.tigerduck.files.NAME"
    const val EXTRA_KIND = "org.ntust.app.tigerduck.files.KIND"

    /**
     * Shows [uri], a file of the app's own or a download it saved, named [name] and typed
     * [mimeType], in the viewer if it can; [elsewhere] otherwise. Returns whether something opened.
     */
    fun open(context: Context, uri: Uri, mimeType: String, name: String, elsewhere: () -> Boolean): Boolean {
        val opening = FileViewing.opening(mimeType, name)
        if (opening !is FileOpening.InApp) return elsewhere()
        val viewer = intent(context, uri, mimeType, name, opening.kind)
        // From outside a screen, the viewer needs a task of its own to start in.
        if (context.findActivity() == null) viewer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(viewer) }.isSuccess || elsewhere()
    }

    fun intent(context: Context, uri: Uri, mimeType: String, name: String, kind: ViewerKind): Intent =
        Intent(context, FileViewerActivity::class.java)
            .setDataAndType(uri, mimeType)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_KIND, kind.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** [uri] in another app, through the system's chooser, which says so itself when no app takes it. */
    fun elsewhereIntent(uri: Uri, mimeType: String, name: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndTypeAndNormalize(uri, mimeType).granting(uri, name),
            null,
        )

    /** [uri] in the share sheet, as Share or Save does elsewhere in the app. */
    fun shareIntent(uri: Uri, mimeType: String, name: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setTypeAndNormalize(mimeType).putExtra(Intent.EXTRA_STREAM, uri).granting(uri, name),
            null,
        )

    /** Read access for the app picked, carried by the clip the chooser passes on. */
    private fun Intent.granting(uri: Uri, name: String): Intent = apply {
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
