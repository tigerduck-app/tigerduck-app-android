package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.notify.MailMutePreset
import org.ntust.app.tigerduck.mail.notify.MailMuteRule
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import org.ntust.app.tigerduck.notification.NotificationChannels
import org.ntust.app.tigerduck.notification.SystemPermissions
import java.time.DayOfWeek
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
    account: MailAccount,
    val systemPermissions: SystemPermissions,
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

    /**
     * Whether the new-mail switch can do anything. Signed out there is nothing to check, and
     * after the server rejected the password no check runs either (spec §7.4: it is never sent
     * again), so the switch would look on and do nothing. The stored value still shows.
     */
    val notificationsToggleEnabled: StateFlow<Boolean> =
        combine(account.signedIn, account.authFailed) { signedIn, authFailed -> signedIn && !authFailed }
            .stateIn(viewModelScope, SharingStarted.Eagerly, account.signedIn.value && !account.authFailed.value)

    data class UiState(
        val notificationsEnabled: Boolean,
        val displayName: String,
        val canExactAlarm: Boolean,
        val diagnostics: List<String>,
        /** The system blocks this app's notifications, or School Mail's channel, so new mail could not be announced. */
        val notificationsDenied: Boolean,
        /** Quiet hours and mute rules: which new mail is not notified. */
        val muteSettings: MailMuteSettings,
    )

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Re-read on resume: the exact-alarm and notification grants and the diagnostics change behind the screen. */
    fun refresh() {
        _ui.value = read().copy(displayName = _ui.value.displayName)
    }

    fun setNotifications(enabled: Boolean) {
        state.notificationsEnabled = enabled
        if (enabled) scheduler.schedule() else scheduler.cancel()
        _ui.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setQuietHoursEnabled(enabled: Boolean) = editMute { it.copy(quietHours = it.quietHours.copy(enabled = enabled)) }

    /** [minute] after midnight on the Taipei clock, as the picker shows it. */
    fun setQuietHoursStart(minute: Int) = editMute { it.copy(quietHours = it.quietHours.copy(startMinute = minute)) }

    fun setQuietHoursEnd(minute: Int) = editMute { it.copy(quietHours = it.quietHours.copy(endMinute = minute)) }

    /** The last selected day stays: see [org.ntust.app.tigerduck.mail.notify.MailQuietHours.toggling]. */
    fun toggleQuietDay(day: DayOfWeek) = editMute { it.copy(quietHours = it.quietHours.toggling(day)) }

    fun setPreset(preset: MailMutePreset, on: Boolean) =
        editMute { it.copy(presets = if (on) it.presets + preset else it.presets - preset) }

    /** [rule] in place of the one with its id, or added last; its value trimmed, and none with nothing to match. */
    fun saveRule(rule: MailMuteRule) {
        val saved = rule.copy(value = rule.value.trim())
        if (saved.value.isEmpty()) return
        editMute { settings ->
            val at = settings.rules.indexOfFirst { it.id == saved.id }
            settings.copy(rules = if (at < 0) settings.rules + saved else settings.rules.toMutableList().apply { set(at, saved) })
        }
    }

    fun deleteRule(id: String) = editMute { settings -> settings.copy(rules = settings.rules.filterNot { it.id == id }) }

    /**
     * Only while a mailbox is signed in: the settings are that student's, and the page greys them
     * out signed out, as it does the new-mail switch.
     */
    private fun editMute(change: (MailMuteSettings) -> MailMuteSettings) {
        if (!signedIn.value) return
        val updated = change(state.muteSettings)
        state.muteSettings = updated
        _ui.update { it.copy(muteSettings = updated) }
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
        notificationsDenied = systemPermissions.notificationsBlocked(NotificationChannels.SCHOOL_MAIL),
        muteSettings = state.muteSettings,
    )

    private companion object {
        const val MAX_NAME = 64
    }
}
