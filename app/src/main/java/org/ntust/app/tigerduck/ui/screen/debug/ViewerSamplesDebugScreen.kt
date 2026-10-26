package org.ntust.app.tigerduck.ui.screen.debug

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.debug.ViewerSample
import org.ntust.app.tigerduck.debug.ViewerSamples
import org.ntust.app.tigerduck.files.FileOpening
import org.ntust.app.tigerduck.files.FileViewing
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.screen.coursemoodle.launchMoodleFile
import java.io.File

/**
 * Opens the debug build's file viewer samples, and any file picked on the phone, as a downloaded
 * Moodle file opens ([launchMoodleFile]): in the app's own viewer when it takes the type, in
 * another app or the share sheet when not. A phone that fails on a real file fails here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerSamplesDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val samples = remember { context.viewerSamples() }
    var copying by remember { mutableStateOf(false) }

    fun open(copy: suspend () -> Pair<File, String>) {
        if (copying) return
        copying = true
        scope.launch {
            val opened = runCatching {
                val (file, mimeType) = withContext(Dispatchers.IO) { copy() }
                launchMoodleFile(context, file, mimeType)
            }.getOrElse { e ->
                Toast.makeText(context, "Not copied: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                true
            }
            copying = false
            if (!opened) Toast.makeText(context, "Nothing opened it", Toast.LENGTH_SHORT).show()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open { context.copyPicked(uri) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text("File viewer samples") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Each opens as a downloaded Moodle file does: by its name and type, in the viewer, " +
                            "another app or the share sheet. Made by tools/viewer-samples/make_samples.sh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !copying) {
                        Text("Open a file on this phone")
                    }
                    if (copying) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (samples.isEmpty()) {
                item {
                    Text(
                        "No samples in this build.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            samples.groupBy { it.group }.forEach { (group, inGroup) ->
                item(key = "group:$group") {
                    Text(
                        group,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(inGroup, key = { it.name }) { sample ->
                    SampleRow(sample) { open { context.copySample(sample) } }
                }
            }
        }
    }
}

@Composable
private fun SampleRow(sample: ViewerSample, onClick: () -> Unit) {
    val context = LocalContext.current
    val goesTo = when (val opening = FileViewing.opening(sample.mimeType, sample.name)) {
        is FileOpening.InApp -> "viewer, ${opening.kind.name.lowercase()}"
        FileOpening.OtherApp -> "another app"
        FileOpening.ShareOnly -> "share sheet"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(sample.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${sample.mimeType} · ${Formatter.formatShortFileSize(context, sample.bytes)} · $goesTo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The samples this build carries: none outside a debug build. */
private fun Context.viewerSamples(): List<ViewerSample> = runCatching {
    assets.open("${ViewerSamples.ASSET_DIR}/${ViewerSamples.INDEX}").bufferedReader().use { ViewerSamples.parseIndex(it.readText()) }
}.getOrDefault(emptyList())

/** [sample] copied under the cache, under its own name, and the type it opens as. */
private fun Context.copySample(sample: ViewerSample): Pair<File, String> {
    val file = File(viewerSamplesDir("bundled"), sample.name)
    ViewerSamples.copyInto(file, cleanUp = file) {
        assets.open("${ViewerSamples.ASSET_DIR}/${ViewerSamples.assetName(sample.name)}")
    }
    return file to sample.mimeType
}

/**
 * The file picked at [uri] copied under the cache, in a folder of its own, under the name its
 * provider gives it, and the type it opens as. Copies of earlier picks stay, for an app one went
 * to that may open it again, but only the newest few ([KEPT_PICKS]).
 */
private fun Context.copyPicked(uri: Uri): Pair<File, String> {
    val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    val providerType = contentResolver.getType(uri)
    val types = MimeTypeMap.getSingleton()
    val name = ViewerSamples.pickedName(displayName, providerType) { types.getExtensionFromMimeType(it) }
    val mimeType = ViewerSamples.pickedType(providerType, name) { types.getMimeTypeFromExtension(it) }
    val picked = viewerSamplesDir("picked")
    val folder = File(picked, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}")
    val file = File(folder, name)
    ViewerSamples.copyInto(file, cleanUp = folder) {
        contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(name)
    }
    picked.list()?.let { ViewerSamples.picksToDrop(it.toList(), KEPT_PICKS) }?.forEach { File(picked, it).deleteRecursively() }
    return file to mimeType
}

/** How many picked files' copies stay. */
private const val KEPT_PICKS = 5

private fun Context.viewerSamplesDir(kind: String): File =
    File(File(cacheDir, ViewerSamples.CACHE_DIR), kind).apply { mkdirs() }
