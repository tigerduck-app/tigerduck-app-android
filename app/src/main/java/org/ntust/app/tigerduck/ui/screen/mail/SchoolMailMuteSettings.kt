// Quiet hours and mute rules on the School Mail notifications page
// (tigerduck-app-android#354), in the iPhone's order (tigerduck-app#801): Quiet
// hours, Muted mail with the built-in rules, then Custom mute rules. All of it
// greys out while new mail does not notify, or while no mailbox is signed in.
//
// The times are Taipei clock times, as the check reads them. The picker edits
// the hour and minute as stored, never through the phone's time zone, so a
// phone set to another one shows and sets the same Taipei times.

package org.ntust.app.tigerduck.ui.screen.mail

import android.icu.text.DateTimePatternGenerator
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.text.format.DateFormat
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.mail.notify.MailMutePreset
import org.ntust.app.tigerduck.mail.notify.MailMuteRule
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.notify.MailQuietHours
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLinkRow
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLinkRowWithValue
import org.ntust.app.tigerduck.ui.screen.settings.SettingsToggleRow
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/** What the page's mute sections change; each goes to [SchoolMailSettingsViewModel]. */
internal class MailMuteActions(
    val setQuietHoursEnabled: (Boolean) -> Unit,
    val setStart: (minute: Int) -> Unit,
    val setEnd: (minute: Int) -> Unit,
    val toggleDay: (DayOfWeek) -> Unit,
    val setPreset: (MailMutePreset, Boolean) -> Unit,
    val saveRule: (MailMuteRule) -> Unit,
    val deleteRule: (id: String) -> Unit,
)

internal fun SchoolMailSettingsViewModel.muteActions() = MailMuteActions(
    setQuietHoursEnabled = ::setQuietHoursEnabled,
    setStart = ::setQuietHoursStart,
    setEnd = ::setQuietHoursEnd,
    toggleDay = ::toggleQuietDay,
    setPreset = ::setPreset,
    saveRule = ::saveRule,
    deleteRule = ::deleteRule,
)

/** The three mute sections, under one another; [enabled] greys them all out. */
internal fun LazyListScope.mailMuteItems(settings: MailMuteSettings, enabled: Boolean, actions: MailMuteActions) {
    item { SectionHeader(stringResource(R.string.school_mail_quiet_hours), Modifier.padding(top = 12.dp)) }
    item { QuietHoursCard(settings.quietHours, enabled, actions) }
    item { SectionHeader(stringResource(R.string.school_mail_filters), Modifier.padding(top = 12.dp)) }
    item {
        ContentCard {
            MailMutePreset.entries.forEachIndexed { i, preset ->
                if (i > 0) HorizontalDivider()
                SettingsToggleRow(
                    label = stringResource(preset.labelRes),
                    checked = preset in settings.presets,
                    enabled = enabled,
                    onCheckedChange = { actions.setPreset(preset, it) },
                )
            }
        }
    }
    item { SectionHeader(stringResource(R.string.school_mail_filter_custom), Modifier.padding(top = 12.dp)) }
    item { MuteRulesCard(settings.rules, enabled, actions) }
}

private enum class QuietBoundary { START, END }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietHoursCard(quiet: MailQuietHours, enabled: Boolean, actions: MailMuteActions) {
    var editing by rememberSaveable { mutableStateOf<QuietBoundary?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    ContentCard {
        SettingsToggleRow(
            label = stringResource(R.string.school_mail_quiet_hours),
            checked = quiet.enabled,
            enabled = enabled,
            onCheckedChange = actions.setQuietHoursEnabled,
        )
        if (quiet.enabled) {
            HorizontalDivider()
            SettingsLinkRowWithValue(
                label = stringResource(R.string.school_mail_quiet_hours_start),
                value = timeText(quiet.startMinute, locale),
                enabled = enabled,
            ) { editing = QuietBoundary.START }
            HorizontalDivider()
            SettingsLinkRowWithValue(
                label = stringResource(R.string.school_mail_quiet_hours_end),
                value = timeText(quiet.endMinute, locale),
                enabled = enabled,
            ) { editing = QuietBoundary.END }
            HorizontalDivider()
            val week = weekOf(locale)
            val captions = week.associateWith { stringResource(it.captionRes) }
            val textAlpha = if (enabled) 1f else ContentAlpha.DISABLED
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.school_mail_quiet_hours_days),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (quiet.days.size == DayOfWeek.entries.size) {
                            stringResource(R.string.school_mail_quiet_hours_every_day)
                        } else {
                            week.filter { it in quiet.days }.joinToString(" ") { captions.getValue(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (enabled) ContentAlpha.SECONDARY else ContentAlpha.DISABLED,
                        ),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    week.forEach { day ->
                        FilterChip(
                            selected = day in quiet.days,
                            onClick = { actions.toggleDay(day) },
                            label = { Text(captions.getValue(day)) },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
    editing?.let { boundary ->
        val start = boundary == QuietBoundary.START
        QuietTimeDialog(
            title = stringResource(if (start) R.string.school_mail_quiet_hours_start else R.string.school_mail_quiet_hours_end),
            initialMinute = if (start) quiet.startMinute else quiet.endMinute,
            onDismiss = { editing = null },
            onConfirm = { minute ->
                if (start) actions.setStart(minute) else actions.setEnd(minute)
                editing = null
            },
        )
    }
}

/**
 * The dial to pick [initialMinute]'s replacement, or, with TalkBack on, the hour and minute to
 * type: the dial's own controls step its minutes by five there, which leaves 23:01 out of reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietTimeDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (minute: Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    val typed = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { if (typed) TimeInput(state = state) else TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MuteRulesCard(rules: List<MailMuteRule>, enabled: Boolean, actions: MailMuteActions) {
    var editing by rememberSaveable(stateSaver = RuleSaver) { mutableStateOf<MailMuteRule?>(null) }
    ContentCard {
        rules.forEach { rule ->
            SettingsLinkRowWithValue(
                label = "${stringResource(rule.field.labelRes)} ${stringResource(rule.op.labelRes)}",
                value = rule.value,
                enabled = enabled,
            ) { editing = rule }
            HorizontalDivider()
        }
        SettingsLinkRow(stringResource(R.string.school_mail_filter_add), enabled = enabled) {
            editing = MailMuteRule.matching(MailMuteRule.Field.FROM, MailMuteRule.Operator.CONTAINS, "")
        }
    }
    editing?.let { rule ->
        MuteRuleDialog(
            rule = rule,
            isNew = rules.none { it.id == rule.id },
            onDismiss = { editing = null },
            onSave = {
                actions.saveRule(it)
                editing = null
            },
            onDelete = {
                actions.deleteRule(rule.id)
                editing = null
            },
        )
    }
}

/** The add or edit sheet of one rule: the field and the operator to pick, and the value to type. */
@Composable
internal fun MuteRuleDialog(
    rule: MailMuteRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (MailMuteRule) -> Unit,
    onDelete: () -> Unit,
) {
    var field by rememberSaveable(rule.id) { mutableStateOf(rule.field) }
    var op by rememberSaveable(rule.id) { mutableStateOf(rule.op) }
    var value by rememberSaveable(rule.id) { mutableStateOf(rule.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.school_mail_filter_add else R.string.school_mail_filter_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuteChoice(MailMuteRule.Field.entries, field, { it.labelRes }, { field = it }, Modifier.weight(1f))
                    MuteChoice(MailMuteRule.Operator.entries, op, { it.labelRes }, { op = it }, Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(field.labelRes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(rule.copy(field = field, op = op, value = value.trim())) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(if (isNew) R.string.action_add else R.string.action_done)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.school_mail_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> MuteChoice(
    options: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = stringResource(labelRes(selected)),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes(option))) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The rule open in the editor, kept across an activity recreation as its four parts. */
private val RuleSaver = Saver<MailMuteRule?, ArrayList<String>>(
    save = { rule -> rule?.let { arrayListOf(it.id, it.field.key, it.op.key, it.value) } },
    restore = { saved ->
        val field = MailMuteRule.Field.fromKey(saved.getOrNull(1))
        val op = MailMuteRule.Operator.fromKey(saved.getOrNull(2))
        if (saved.size == 4 && field != null && op != null) MailMuteRule(saved[0], field, op, saved[3]) else null
    },
)

/** [minute] after midnight as the phone writes a time: 24-hour or not, as its setting says. */
@Composable
private fun timeText(minute: Int, locale: Locale): String =
    quietTimeText(minute, locale, DateFormat.is24HourFormat(LocalContext.current))

/**
 * [minute] after midnight on the clock [is24Hour] asks for, in [locale]'s own pattern for that
 * clock, as the picker shows it: the locale's default can be the other clock (English (UK) writes
 * 23:00 where the phone is set to 12-hour).
 */
internal fun quietTimeText(minute: Int, locale: Locale, is24Hour: Boolean): String {
    val pattern = DateTimePatternGenerator.getInstance(locale).getBestPattern(if (is24Hour) "HHmm" else "hmm")
    // ICU's own formatter for ICU's pattern, which can carry letters java.time's does not know
    // on every Android release (B, the day period zh-TW writes).
    val format = SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return format.format(Date(minute * 60_000L))
}

/** The days of a week from the one [locale] starts it on. */
private fun weekOf(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

private val DayOfWeek.captionRes: Int
    get() = when (this) {
        DayOfWeek.MONDAY -> R.string.weekday_mon_caption
        DayOfWeek.TUESDAY -> R.string.weekday_tue_caption
        DayOfWeek.WEDNESDAY -> R.string.weekday_wed_caption
        DayOfWeek.THURSDAY -> R.string.weekday_thu_caption
        DayOfWeek.FRIDAY -> R.string.weekday_fri_caption
        DayOfWeek.SATURDAY -> R.string.weekday_sat_caption
        DayOfWeek.SUNDAY -> R.string.weekday_sun_caption
    }

private val MailMutePreset.labelRes: Int
    get() = when (this) {
        MailMutePreset.BULLETIN -> R.string.school_mail_filter_preset_bulletin
        MailMutePreset.MOODLE_SUBMISSION -> R.string.school_mail_filter_preset_moodle_submission
    }

private val MailMuteRule.Field.labelRes: Int
    get() = when (this) {
        MailMuteRule.Field.FROM -> R.string.school_mail_filter_field_from
        MailMuteRule.Field.TO_OR_CC -> R.string.school_mail_filter_field_to_cc
        MailMuteRule.Field.SUBJECT -> R.string.school_mail_filter_field_subject
    }

private val MailMuteRule.Operator.labelRes: Int
    get() = when (this) {
        MailMuteRule.Operator.CONTAINS -> R.string.school_mail_filter_op_contains
        MailMuteRule.Operator.IS -> R.string.school_mail_filter_op_is
    }
