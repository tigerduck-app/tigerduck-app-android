package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.IoDispatcher
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

object MailDiagnostics {
    private val FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"))

    /** `"<epochMillis>|<source>|<outcome>"` → `"09/15 18:00:42 · ALARM · NewMail"`, or null if malformed. */
    fun format(entry: String): String? {
        val parts = entry.split('|')
        if (parts.size != 3) return null
        val millis = parts[0].toLongOrNull() ?: return null
        return "${FORMAT.format(Instant.ofEpochMilli(millis))} · ${parts[1]} · ${parts[2]}"
    }
}

@HiltViewModel
class SchoolMailSettingsViewModel @Inject constructor(
    private val state: MailStateStore,
    private val scheduler: MailBackgroundScheduler,
    private val exactAlarms: ExactAlarmAccess,
    private val cache: MailCache,
    @IoDispatcher private val io: CoroutineDispatcher,
    account: MailAccount,
) : ViewModel() {
    /**
     * The same flag the mail page and the Settings account row read
     * ([MailAccount.signedIn]) — not a second notion of "signed in". With no
     * mailbox there is nothing for "Name shown to recipients" to name and
     * nothing for the School Mail notifications page to set, so those controls
     * grey out rather than disappear. The demo mailbox signs in like any other
     * account, so it reads true here and needs no case of its own.
     */
    val signedIn: StateFlow<Boolean> = account.signedIn

    data class UiState(
        val notificationsEnabled: Boolean,
        val displayName: String,
        val canExactAlarm: Boolean,
        val diagnostics: List<String>,
    )

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * Bytes under the mail cache root — folder pages, bodies, sources and downloaded
     * attachments. Null while the first walk is still running, so the row can say "…" instead
     * of claiming a size it hasn't measured yet.
     */
    private val _cacheBytes = MutableStateFlow<Long?>(null)
    val cacheBytes: StateFlow<Long?> = _cacheBytes.asStateFlow()

    /** Re-read on resume: the exact-alarm grant and the diagnostics change behind the screen. */
    fun refresh() {
        _ui.value = read().copy(displayName = _ui.value.displayName)
    }

    /** Walking a directory tree is IO, so the measurement never runs on the main thread. */
    fun refreshCacheSize() {
        viewModelScope.launch { _cacheBytes.value = withContext(io) { cache.sizeBytes() } }
    }

    /**
     * Deletes the whole cache and re-measures, so the row shows the result rather than the
     * figure from before. No confirmation on purpose: every byte here is re-downloadable, and
     * signing out already wipes the same directory.
     */
    fun clearCache() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(io) {
                cache.clearAll()
                cache.sizeBytes()
            }
        }
    }

    fun setNotifications(enabled: Boolean) {
        state.notificationsEnabled = enabled
        if (enabled) scheduler.schedule() else scheduler.cancel()
        _ui.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setDisplayName(name: String) {
        val value = name.take(MAX_NAME)
        state.displayName = value
        _ui.update { it.copy(displayName = value) }
    }

    private fun read() = UiState(
        notificationsEnabled = state.notificationsEnabled,
        displayName = state.displayName.orEmpty(),
        canExactAlarm = exactAlarms.canScheduleExactAlarms(),
        diagnostics = state.diagnostics.mapNotNull(MailDiagnostics::format),
    )

    private companion object {
        const val MAX_NAME = 64
    }
}
