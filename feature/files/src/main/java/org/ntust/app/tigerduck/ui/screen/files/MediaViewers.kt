package org.ntust.app.tigerduck.ui.screen.files

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.files.TextPreview
import org.ntust.app.tigerduck.files.TextPreviews
import org.ntust.app.tigerduck.ui.openLink

/**
 * A picture: decoded no larger than [MAX_IMAGE_SIDE] on its longer side, so a camera's photo
 * fits in memory; an animated GIF or WebP plays. Pinch or double-tap to zoom, one finger moves.
 */
@Composable
internal fun ImageViewer(uri: Uri, onFailed: (ViewerFailure) -> Unit) {
    val context = LocalContext.current
    var drawable by remember(uri) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(uri) {
        drawable = runCatching {
            withContext(Dispatchers.IO) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_IMAGE_SIDE) {
                        val scale = MAX_IMAGE_SIDE.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }
            }
        }.getOrElse { onFailed(ViewerFailure(it)); null }
    }
    val picture = drawable ?: return Reading()
    val zoom = remember(uri) { ZoomState() }
    DisposableEffect(picture) {
        (picture as? AnimatedImageDrawable)?.start()
        onDispose { (picture as? AnimatedImageDrawable)?.stop() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { it.setImageDrawable(picture) },
            modifier = Modifier.fillMaxSize().zoomable(zoom, oneFingerPans = true),
        )
    }
}

private const val MAX_IMAGE_SIDE = 4096

/**
 * A video or a sound, in Media3's player with its own controls; a sound, which has no picture,
 * keeps them up under a note. Paused when the screen goes to the background, and released when it
 * closes.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MediaViewer(uri: Uri, audio: Boolean, onFailed: (ViewerFailure) -> Unit) {
    val context = LocalContext.current
    // Where it was and whether it played, kept when the screen is made again (a new display size).
    var resumeAt by rememberSaveable(uri) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(uri) { mutableStateOf(true) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri), resumeAt)
            prepare()
            playWhenReady = resumePlaying
        }
    }
    DisposableEffect(player) {
        // The track last chosen, for an error from the source: the player lets go of its own
        // formats before it says so.
        var lastTrack: Format? = null
        val failure = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                lastTrack = chosenTrack(tracks) ?: lastTrack
            }

            override fun onPlayerError(error: PlaybackException) =
                onFailed(ViewerFailure(error, track = player.videoFormat ?: player.audioFormat ?: lastTrack))
        }
        player.addListener(failure)
        onDispose {
            player.removeListener(failure)
            player.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        // Stopped before the screen's state is saved, so the place is in it.
        val pause = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumeAt = player.currentPosition
                resumePlaying = player.playWhenReady
                player.pause()
            }
        }
        lifecycle.addObserver(pause)
        onDispose { lifecycle.removeObserver(pause) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (audio) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(96.dp),
            )
        }
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    if (audio) {
                        controllerShowTimeoutMs = 0
                        controllerHideOnTouch = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        showController()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The video track [tracks] chose, else the sound's. */
@OptIn(UnstableApi::class)
private fun chosenTrack(tracks: Tracks): Format? {
    fun chosen(type: Int) = tracks.groups.filter { it.type == type }
        .firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
    return chosen(C.TRACK_TYPE_VIDEO) ?: chosen(C.TRACK_TYPE_AUDIO)
}

/**
 * Text, line after line in a fixed-width face, or Markdown rendered; up to
 * [TextPreviews.LIMIT_BYTES], with a line saying so when the file goes on. A Markdown link opens
 * as the Browser setting says, as an announcement's does.
 */
@Composable
internal fun TextViewer(uri: Uri, markdown: Boolean, browserPreference: String, onFailed: (ViewerFailure) -> Unit) {
    val context = LocalContext.current
    var preview by remember(uri) { mutableStateOf<TextPreview?>(null) }
    LaunchedEffect(uri) {
        preview = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { TextPreviews.read(it) }
                    ?: throw java.io.FileNotFoundException("no stream")
            }
        }.getOrElse { onFailed(ViewerFailure(it)); null }
    }
    val text = preview ?: return Reading()
    val limit = android.text.format.Formatter.formatShortFileSize(context, TextPreviews.LIMIT_BYTES.toLong())
    val truncated = if (text.truncated) stringResource(R.string.file_viewer_text_truncated, limit) else null
    if (markdown) {
        val handler = remember(context, browserPreference) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    openLink(context, uri, browserPreference)
                }
            }
        }
        CompositionLocalProvider(LocalUriHandler provides handler) {
            Column(Modifier.fillMaxSize()) {
                truncated?.let { TruncatedNote(it) }
                // Block by block in a lazy list, so a long file lays out only what shows.
                Markdown(
                    content = text.text,
                    modifier = Modifier.weight(1f),
                    success = { state, components, modifier ->
                        LazyMarkdownSuccess(state, components, modifier, contentPadding = PaddingValues(16.dp))
                    },
                )
            }
        }
    } else {
        val lines = remember(text) { TextPreviews.lines(text.text) }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                truncated?.let { item { TruncatedNote(it) } }
            }
        }
    }
}

@Composable
private fun TruncatedNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
