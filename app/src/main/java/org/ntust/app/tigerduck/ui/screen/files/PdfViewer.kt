package org.ntust.app.tigerduck.ui.screen.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.files.PdfPages
import java.io.Closeable

/**
 * A PDF, page after page down the screen, with the page at the top counted in a corner. Two
 * fingers zoom and move it; one scrolls. Pages render on one background thread, as
 * [PdfRenderer] allows, sharper than the screen so a pinch in stays crisp, and only so many are
 * kept at once ([PdfDocument.cache]).
 */
@Composable
internal fun PdfViewer(uri: Uri, onFailed: (ViewerFailure) -> Unit) {
    val context = LocalContext.current
    // Whatever opens is the screen's to close, even if it opens after the screen has gone.
    val owner = remember(uri) { Owner<PdfDocument>() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner) {
        try {
            withContext(Dispatchers.IO) { owner.adopt(PdfDocument.open(context, uri)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A locked or broken file: the screen says it can't show it.
            onFailed(ViewerFailure(e))
        }
    }
    val pdf = owner.value ?: return Reading()
    val zoom = remember(uri) { ZoomState() }
    val list = rememberLazyListState()
    val position by remember(pdf) { derivedStateOf { PdfPages.position(list.firstVisibleItemIndex, pdf.pageSizes.size) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val viewWidth = with(LocalDensity.current) { maxWidth.roundToPx() }
        Box(Modifier.fillMaxSize().zoomable(zoom, oneFingerPans = false)) {
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pdf.pageSizes.size) { index -> PdfPage(pdf, index, viewWidth, onFailed) }
            }
        }
        val pageText = stringResource(R.string.a11y_file_viewer_page, position.first, position.second)
        Text(
            "${position.first} / ${position.second}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = pageText },
        )
    }
}

@Composable
private fun PdfPage(pdf: PdfDocument, index: Int, viewWidth: Int, onFailed: (ViewerFailure) -> Unit) {
    val size = pdf.pageSizes[index]
    var bitmap by remember(index, viewWidth) { mutableStateOf(pdf.cached(index, viewWidth)) }
    LaunchedEffect(index, viewWidth) {
        if (bitmap != null) return@LaunchedEffect
        try {
            bitmap = pdf.render(index, viewWidth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A page that can't be drawn would look blank: the screen says it can't show the file.
            onFailed(ViewerFailure(e, note = "page ${index + 1}"))
        }
    }
    val ratio = size.width.toFloat().coerceAtLeast(1f) / size.height.toFloat().coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
    }
}

/** An open PDF: its pages' sizes in points, rendered on demand one at a time. */
internal class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<Size>,
) : Closeable {
    private val lock = Mutex()
    private var closed = false

    /** Rendered pages, by page and width, up to an eighth of the app's memory or 96 MB. */
    private val cache = object : LruCache<Pair<Int, Int>, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Pair<Int, Int>, value: Bitmap) = value.byteCount
    }

    fun cached(index: Int, viewWidth: Int): Bitmap? = cache.get(index to viewWidth)

    /** Page [index] for a view [viewWidth] px wide, at [PdfPages.renderSize]. */
    suspend fun render(index: Int, viewWidth: Int): Bitmap? = withContext(RENDERING) {
        lock.withLock {
            if (closed) return@withLock null
            cache.get(index to viewWidth)?.let { return@withLock it }
            renderer.openPage(index).use { page ->
                val (width, height) = PdfPages.renderSize(page.width, page.height, viewWidth)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index to viewWidth, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        // After any render in progress, on its thread, as PdfRenderer asks.
        CoroutineScope(RENDERING).launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                cache.evictAll()
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        private val RENDERING = Dispatchers.IO.limitedParallelism(1)

        private fun cacheBytes(): Int =
            minOf(Runtime.getRuntime().maxMemory() / 8, 96L * 1024 * 1024).toInt()

        /** Opens [uri] and reads every page's size; stops, closing what it opened, when cancelled. */
        suspend fun open(context: Context, uri: android.net.Uri): PdfDocument {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.FileNotFoundException("no descriptor")
            try {
                val renderer = PdfRenderer(descriptor)
                try {
                    val sizes = (0 until renderer.pageCount).map { i ->
                        currentCoroutineContext().ensureActive()
                        renderer.openPage(i).use { Size(it.width, it.height) }
                    }
                    return PdfDocument(descriptor, renderer, sizes)
                } catch (e: Exception) {
                    renderer.close()
                    throw e
                }
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}

/**
 * What a screen opened, closed when the screen leaves: also when it opens only after that, which
 * a cancelled open can, as its result is then never handed back.
 */
internal class Owner<T : Closeable> {
    private var closed = false

    var value: T? by mutableStateOf(null)
        private set

    /** Takes [resource]; closes it at once instead when the screen has already left. */
    @Synchronized
    fun adopt(resource: T): Boolean {
        if (closed) {
            resource.close()
            return false
        }
        value = resource
        return true
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        value?.close()
    }
}
