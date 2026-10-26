// The parts every Moodle screen of a course shares: its bar, its first-load and error states,
// the timer that keeps its data fresh, avatars, dates and Markdown with Moodle's links.

package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import android.content.res.Resources
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleRefreshSchedule
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.screen.settings.SubSettingsBarHeight
import org.ntust.app.tigerduck.util.replaceIosArg
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A line between rows inside a card: the iOS separator tone. The theme's `outlineVariant` is the
 * dark card's own colour, which would hide the line in dark mode.
 */
@Composable
internal fun cardDividerColor(): Color = MaterialTheme.colorScheme.outline

/** The pin and warning orange of iOS's Moodle screens. */
internal val MoodleOrange = Color(0xFFFF9500)

/** Whether a Moodle page holds the store of an account that signed out, or of one another replaced. */
internal fun moodleScreenShouldClose(signedIn: Boolean, store: MoodleCourseStore?): Boolean =
    !signedIn || store?.isCurrent() == false

/**
 * Closes a pushed Moodle screen once its account has gone: at a sign-out while it shows, and when
 * it comes back into view under another account, as a page left in the back stack would.
 */
@Composable
internal fun MoodleSignedOutEffect(viewModel: MoodleCourseViewModel, onClose: () -> Unit) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel.signedIn, held = store, isCurrent = { it.isCurrent() }, onClose = onClose)
}

/**
 * [MoodleSignedOutEffect] for a page holding [held], the account's data it shows, which
 * [isCurrent] says still belongs to the account signed in; null while the page has none yet.
 */
@Composable
internal fun <T : Any> MoodleSignedOutEffect(
    signedIn: kotlinx.coroutines.flow.StateFlow<Boolean>,
    held: T?,
    isCurrent: (T) -> Boolean,
    onClose: () -> Unit,
) {
    val isSignedIn by signedIn.collectAsStateWithLifecycle()
    val latestClose by rememberUpdatedState(onClose)
    fun shouldClose(signedInNow: Boolean) = !signedInNow || held?.let(isCurrent) == false
    if (shouldClose(isSignedIn)) LaunchedEffect(Unit) { latestClose() }
    LifecycleResumeEffect(held) {
        if (shouldClose(signedIn.value)) latestClose()
        onPauseOrDispose { }
    }
}

/**
 * The bar of a pushed Moodle screen: back, the title, and "Open in Moodle" for [moodlePath] when
 * the screen has a page on the site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoodleTopBar(
    title: String,
    onBack: () -> Unit,
    moodlePath: String?,
    actions: @Composable () -> Unit = {},
    willOpenPage: () -> Unit = {},
) {
    val context = LocalContext.current
    TopAppBar(
        windowInsets = NoTopBarInsets,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            actions()
            if (moodlePath != null) {
                IconButton(onClick = {
                    willOpenPage()
                    openMoodlePage(context, moodlePath)
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.action_open_in_moodle),
                    )
                }
            }
        },
        expandedHeight = SubSettingsBarHeight,
    )
}

/**
 * A Moodle list with nothing to show yet: a spinner while the first fetch runs, else its error
 * in Moodle's own words and a retry. A network failure has its own icon.
 */
@Composable
internal fun MoodleFirstLoad(failure: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (failure == null) {
            CircularProgressIndicator()
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (MoodleWebserviceError.isNetwork(failure)) Icons.Filled.WifiOff else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = moodleErrorMessage(failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/**
 * A fetch that failed while a copy shows, or in a section with nothing yet: why, and Retry, in a
 * compact row. The copy, if any, stays below it.
 */
@Composable
internal fun MoodleRefreshFailure(failure: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (MoodleWebserviceError.isNetwork(failure)) Icons.Filled.WifiOff else Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = moodleErrorMessage(failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** [error] as the reader is told it: Moodle's own words where it has them. */
@Composable
internal fun moodleErrorMessage(error: Throwable): String = moodleErrorText(LocalResources.current, error)

/** [moodleErrorMessage] outside composition, as for a toast. */
internal fun moodleErrorText(resources: Resources, error: Throwable): String {
    if (MoodleWebserviceError.isNetwork(error)) return resources.getString(R.string.error_network_unavailable)
    return when (error) {
        is MoodleWebserviceError.SiteMaintenance -> resources.getString(R.string.error_moodle_site_maintenance)
        is MoodleWebserviceError.InvalidToken -> resources.getString(R.string.error_moodle_invalid_token)
        is MoodleWebserviceError.InvalidCredentials -> resources.getString(R.string.error_moodle_invalid_credentials)
        is MoodleWebserviceError.MissingStoredCredentials -> resources.getString(R.string.error_moodle_missing_credentials)
        is MoodleWebserviceError.SsoLoginRejected -> ssoRejectedText(resources, error.reason)
        is MoodleWebserviceError.SsoLoginFormUnavailable -> ssoRejectedText(resources, error.reason)
        is MoodleWebserviceError.AccessDenied,
        is MoodleWebserviceError.WebserviceDisabled -> resources.getString(R.string.error_moodle_webservice_disabled)
        is MoodleWebserviceError.HttpStatus -> resources.getString(R.string.error_moodle_http_status_format, error.code)
        // The part this app wrote: what follows a colon quotes the server -- a sign-in page's
        // address and the start of its HTML, a parser's message -- and stays in the log.
        is MoodleWebserviceError.MalformedResponse ->
            resources.getString(R.string.error_moodle_malformed_response_format)
                .replaceIosArg(1, error.detail.substringBefore(':').trim())
        else -> resources.getString(R.string.error_moodle_malformed_response_format)
            .replaceIosArg(1, error.javaClass.simpleName)
    }
}

private fun ssoRejectedText(resources: Resources, reason: String?): String =
    reason?.takeIf { it.isNotBlank() }
        ?.let { resources.getString(R.string.error_moodle_sso_rejected_with_reason).replaceIosArg(1, it) }
        ?: resources.getString(R.string.error_moodle_sso_rejected)

/**
 * Keeps a screen's data fresh while it shows and the app is in the foreground: a refresh as soon
 * as the data is older than [ttlMs], then another each time it ages past it again. A failed
 * refresh waits a full interval; one from elsewhere, such as a pull, pushes the next one back.
 * [awaitCache] runs first, so a cached copy's age is known before anything is fetched.
 */
@Composable
internal fun MoodleAutoRefresh(
    key: Any?,
    ttlMs: Long,
    awaitCache: suspend () -> Unit,
    fetchedAtMs: () -> Long?,
    refresh: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFetchedAt by rememberUpdatedState(fetchedAtMs)
    val latestRefresh by rememberUpdatedState(refresh)
    val latestAwait by rememberUpdatedState(awaitCache)
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            latestAwait()
            var lastTry: Long? = null
            while (isActive) {
                val now = System.currentTimeMillis()
                val wait = MoodleRefreshSchedule.waitMs(latestFetchedAt(), lastTry, ttlMs, now)
                if (wait > 0) {
                    delay(wait)
                } else {
                    lastTry = now
                    latestRefresh()
                }
            }
        }
    }
}

/** A pull's refresh, run where the pull's indicator can wait for it. */
@Composable
internal fun rememberRefresher(refresh: suspend () -> Unit): (onDone: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(refresh)
    return remember(scope) {
        { onDone -> scope.launch { try { latest() } finally { onDone() } } }
    }
}

/** A user's own picture where there is one, else the first character of their name in a circle. */
@Composable
internal fun MoodleAvatar(name: String, picture: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val shape = CircleShape
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.firstOrNull()?.toString().orEmpty(),
                fontSize = avatarLetterSize(size, LocalDensity.current),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The initial's size in an avatar of [size]: held to the circle, which does not grow with the
 * font size, so a large font does not push the letter out of it.
 */
internal fun avatarLetterSize(size: Dp, density: Density): TextUnit = with(density) { (size * 0.45f).toSp() }

/**
 * Markdown from Moodle, whose links open as [openMoodleContentLink] opens them. Headings are
 * scaled to a post's body rather than to a page's; [compact] sets the body a size smaller, as for
 * a grade's feedback under its row.
 */
@Composable
internal fun MoodleMarkdown(
    markdown: String,
    browserPreference: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val handler = remember(context, browserPreference) {
        object : UriHandler {
            override fun openUri(uri: String) = openMoodleContentLink(context, uri, browserPreference)
        }
    }
    val type = MaterialTheme.typography
    val body = if (compact) type.bodyMedium else type.bodyLarge
    val typography = markdownTypography(
        h1 = type.headlineSmall,
        h2 = type.titleLarge,
        h3 = type.titleMedium,
        h4 = type.titleSmall,
        h5 = type.titleSmall,
        h6 = type.titleSmall,
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(
            style = body.toSpanStyle().copy(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ),
    )
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(content = markdown, typography = typography, modifier = modifier)
    }
}

/** Moodle's [html] as Markdown, converted off the main thread; null until it is ready. */
@Composable
internal fun rememberMoodleMarkdown(html: String): String? =
    produceState<String?>(initialValue = null, html) {
        value = withContext(Dispatchers.Default) { MoodleHtmlText.markdown(html) }
    }.value

/** Dates as Moodle's screens write them, in Taipei time whatever the device's zone. */
internal object MoodleDates {
    private fun format(pattern: String) =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = AppConstants.TAIPEI_TZ }

    private fun calendar(ms: Long) = Calendar.getInstance(AppConstants.TAIPEI_TZ).apply { timeInMillis = ms }

    /** A list's date: the time today, `MM/dd` this year, `yyyy/MM/dd` before. */
    fun listDate(epochSeconds: Long, nowMs: Long): String {
        val ms = epochSeconds * 1000
        val date = calendar(ms)
        val now = calendar(nowMs)
        val sameYear = date.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        return when {
            sameYear && date.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> format("HH:mm").format(Date(ms))
            sameYear -> format("MM/dd").format(Date(ms))
            else -> format("yyyy/MM/dd").format(Date(ms))
        }
    }

    /** `yyyy/MM/dd HH:mm`, for the masthead of a post. */
    fun dateTime(epochSeconds: Long): String = format("yyyy/MM/dd HH:mm").format(Date(epochSeconds * 1000))

    /** `M/d HH:mm`, as the course detail writes an assignment's due time; never seconds. */
    fun minute(epochSeconds: Long): String = format("M/d HH:mm").format(Date(epochSeconds * 1000))

    /** "Thu, Oct 15 at 20:03" in the device's language: the weekday, month, day and time, for the page's times. */
    fun dayTime(epochSeconds: Long, locale: Locale = Locale.getDefault()): String =
        localized(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEEHHmm"), locale).format(Date(epochSeconds * 1000))

    /** "Tue 23:59": the weekday and time, for the due time's headline. */
    fun weekdayTime(epochSeconds: Long, locale: Locale = Locale.getDefault()): String =
        localized(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEHHmm"), locale).format(Date(epochSeconds * 1000))

    /** "Oct", for the due date's tile. */
    fun month(epochSeconds: Long, locale: Locale = Locale.getDefault()): String =
        localized("MMM", locale).format(Date(epochSeconds * 1000))

    fun dayOfMonth(epochSeconds: Long): Int = calendar(epochSeconds * 1000).get(Calendar.DAY_OF_MONTH)

    private fun localized(pattern: String, locale: Locale) =
        SimpleDateFormat(pattern, locale).apply { timeZone = AppConstants.TAIPEI_TZ }
    /** A file's date: `MM/dd` this year, `yyyy/MM/dd` before. */
    fun date(epochSeconds: Long, nowMs: Long): String =
        format(if (sameYear(epochSeconds, nowMs)) "MM/dd" else "yyyy/MM/dd").format(Date(epochSeconds * 1000))

    /** A reply's date: `MM/dd HH:mm` this year, `yyyy/MM/dd HH:mm` before. */
    fun postDate(epochSeconds: Long, nowMs: Long): String =
        format(if (sameYear(epochSeconds, nowMs)) "MM/dd HH:mm" else "yyyy/MM/dd HH:mm").format(Date(epochSeconds * 1000))

    private fun sameYear(epochSeconds: Long, nowMs: Long): Boolean =
        calendar(epochSeconds * 1000).get(Calendar.YEAR) == calendar(nowMs).get(Calendar.YEAR)
}
