package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) : ViewModel() {
    data class UiState(
        val notificationsEnabled: Boolean,
        val displayName: String,
        val canExactAlarm: Boolean,
        val diagnostics: List<String>,
    )

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Re-read on resume: the exact-alarm grant and the diagnostics change behind the screen. */
    fun refresh() {
        _ui.value = read().copy(displayName = _ui.value.displayName)
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
