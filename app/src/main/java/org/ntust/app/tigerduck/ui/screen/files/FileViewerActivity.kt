package org.ntust.app.tigerduck.ui.screen.files

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.files.FileViewer
import org.ntust.app.tigerduck.files.ViewerKind
import javax.inject.Inject

/**
 * The app's own viewer for a downloaded file ([FileViewer]): a PDF, a picture, a video or sound,
 * text, a Word or PowerPoint document, or an archive, with Share or Save and Open in another app
 * above it.
 * Kept through rotation and resizing rather than recreated, so a video plays on and a PDF keeps
 * its place.
 */
@AndroidEntryPoint
class FileViewerActivity : AppCompatActivity() {

    @Inject lateinit var host: FileViewerHost

    private var sizes: ActivitySizes? = null
    private var attachedConfiguration: Configuration? = null

    // The sizes picked on the Display and text size page, as every screen of the app has them.
    override fun attachBaseContext(newBase: Context) {
        val sizes = EntryPointAccessors.fromApplication(newBase.applicationContext, FileViewerHostEntryPoint::class.java)
            .fileViewerHost()
            .sizes(newBase)
        this.sizes = sizes
        attachedConfiguration = Configuration(newBase.resources.configuration)
        super.attachBaseContext(sizes.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data
        val type = intent.type
        val name = intent.getStringExtra(FileViewer.EXTRA_NAME)
        val kind = intent.getStringExtra(FileViewer.EXTRA_KIND)?.let { k -> ViewerKind.entries.firstOrNull { it.name == k } }
        if (uri == null || type == null || name == null || kind == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            host.Theme {
                FileViewerScreen(
                    file = ViewedFile(uri, type, name, kind),
                    browserPreference = host.browserPreference,
                    onBack = ::finish,
                    onShare = { start(FileViewer.shareIntent(uri, type, name)) },
                    onOpenElsewhere = { start(FileViewer.elsewhereIntent(uri, type, name)) },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A new font scale or density: the stored sizes apply over it again.
        if (attachedConfiguration?.let { sizes?.isStale(it, newConfig) } == true) recreate()
    }

    private fun start(intent: android.content.Intent) {
        if (runCatching { startActivity(intent) }.isFailure) {
            Toast.makeText(this, R.string.browser_download_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/** The file a [FileViewerActivity] shows. */
data class ViewedFile(val uri: Uri, val mimeType: String, val name: String, val kind: ViewerKind)
