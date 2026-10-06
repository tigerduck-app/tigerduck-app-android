package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One of three Android system permissions the notification features depend on.
 *
 * - [NOTIFICATIONS]:  POST_NOTIFICATIONS runtime permission (API 33+). Required
 *   for any notification to show at all.
 * - [EXACT_ALARM]:    User-revocable special access (API 31+). Without it, the
 *   assignment and 即將上課 schedulers fall back to inexact alarms that may be
 *   delayed by Doze up to ~15 min.
 * - [BATTERY_OPTIMIZATION]: If the app is still under battery optimization, the
 *   OS may defer alarms and background work — especially on aggressive OEM
 *   skins (Xiaomi/Oppo/Huawei etc.).
 * - [PROMOTED_NOTIFICATIONS]: User-revocable special access (API 36+). Without
 *   it the Live Update still posts as an ordinary ongoing notification, but
 *   the system will not promote it to a status-bar chip. Granted from a
 *   dedicated settings page, never from a runtime prompt. Whether it applies
 *   at all is an OEM question as much as an API-level one — see [DeviceSkin].
 */
enum class AppPermission {
    NOTIFICATIONS,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION,
    PROMOTED_NOTIFICATIONS,
}

data class PermissionState(
    val permission: AppPermission,
    val granted: Boolean,
    val applicable: Boolean,
)

@Singleton
class SystemPermissions @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Launch-prompt refusals the warning popup has yet to be closed on. In
     * memory on purpose: a refusal is shown once, not on every ON_RESUME re-read
     * of the list, and the next cold start asks — and so records — afresh.
     */
    private val unacknowledgedRefusals = mutableSetOf<AppPermission>()

    /**
     * Read once: none of the inputs — API level, manufacturer, skin version —
     * can change without the process being restarted, and the lookup reaches
     * for `SystemProperties` by reflection, which is not worth repeating on
     * every ON_RESUME re-read of the permission rows.
     */
    private val chipSupport: StatusBarChipSupport = DeviceSkin.current().chipSupport

    init {
        forgetInferredChipGrant(
            prefs,
            chipWasAssumed = DeviceSkin.current().isOplus &&
                chipSupport != StatusBarChipSupport.UNSUPPORTED,
        )
    }

    /** True if the permission is granted right now. Returns true when not applicable. */
    fun isGranted(p: AppPermission): Boolean = when (p) {
        AppPermission.NOTIFICATIONS -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        AppPermission.EXACT_ALARM -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
            else {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.canScheduleExactAlarms()
            }
        }

        AppPermission.BATTERY_OPTIMIZATION -> {
            // The user-facing "Restricted/Optimized/Unrestricted" toggle
            // maps more closely to background restriction than Doze allowlisting.
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.isBackgroundRestricted.not()
        }

        AppPermission.PROMOTED_NOTIFICATIONS -> when (chipSupport) {
            // No chip surface on this device. isApplicable reports false and
            // the row is painted grey; "granted" is this class's convention
            // for a permission that does not apply, and keeps the permission
            // out of revokedOrDeclinedUnmuted().
            StatusBarChipSupport.UNSUPPORTED -> true

            StatusBarChipSupport.PLATFORM_DECIDES ->
                NotificationManagerCompat.from(context).canPostPromotedNotifications()
        }
    }

    /** False if this device has no such permission to grant (treat as granted). */
    fun isApplicable(p: AppPermission): Boolean = when (p) {
        AppPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        AppPermission.EXACT_ALARM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        AppPermission.BATTERY_OPTIMIZATION -> true
        // Not just the API level: a Galaxy on One UI 8.0 is API 36 and still
        // has no chip to grant, so it gets the grey "not on this system
        // version" row rather than a red one pointing at a settings page that
        // cannot help.
        AppPermission.PROMOTED_NOTIFICATIONS ->
            chipSupport != StatusBarChipSupport.UNSUPPORTED
    }

    fun state(p: AppPermission): PermissionState =
        PermissionState(p, isGranted(p), isApplicable(p))

    fun states(): List<PermissionState> =
        AppPermission.entries.map { state(it) }

    /** True if user granted this at least once in the past (tracked by us). */
    fun wasPreviouslyGranted(p: AppPermission): Boolean =
        prefs.getBoolean(keyGranted(p), false)

    /** True if the user turned down the app's own prompt for this (tracked by us). */
    fun wasDeclined(p: AppPermission): Boolean =
        prefs.getBoolean(keyDeclined(p), false)

    /**
     * Any of the app's own prompts for [p] came back refused; from now on an
     * "allow" tap opens the settings page — see [canPromptForNotifications].
     * RequestPermission reports a dismissed prompt (back, tap outside) the
     * same as a refusal, so that counts too. The cost of a wrong guess is a
     * tap that opens the settings page where the prompt could still have
     * shown; the cost of the other guess is a tap that does nothing.
     */
    fun recordDeclined(p: AppPermission) {
        prefs.edit().putBoolean(keyDeclined(p), true).apply()
    }

    /**
     * MainActivity's launch prompt answered. A refusal is [recordDeclined],
     * and the warning popup shows notifications until it is closed — see
     * [dismissRefusalWarnings] — even if they were never on, so the user can
     * reach its 以後不再提醒. The result is delivered before onResume, whose
     * re-check then shows the popup. Only the launch prompt does this; the
     * in-app surfaces that ask (onboarding, 通知權限設定, 公告訂閱) already
     * show the permission's state, so a popup on top would only repeat it.
     */
    fun recordLaunchPromptResult(granted: Boolean) {
        if (granted) return
        recordDeclined(AppPermission.NOTIFICATIONS)
        unacknowledgedRefusals += AppPermission.NOTIFICATIONS
    }

    /** The warning popup was closed: stop listing the refusals it showed. */
    fun dismissRefusalWarnings() {
        unacknowledgedRefusals.clear()
    }

    /**
     * Snapshot of current grant state into the "previously granted" flag, and
     * lift the notifications mute once they are granted: the mute answered
     * "they're off — keep reminding me?", and once the user turns them back
     * on, a later revoke is a new question. Without this, nothing could ever
     * un-mute — the popup row that holds the checkbox disappears the moment it
     * is ticked — and the mute also silences the launch prompt. Notifications
     * only: background restriction and the rest can be flipped by the OS or
     * an OEM battery manager without the user doing anything, and that must
     * not undo a mute the user chose.
     */
    fun recordCurrentGrants() {
        val editor = prefs.edit()
        for (p in AppPermission.entries) {
            // isGranted reports true for a permission this OS version does not
            // have. Banking that would make a later OS upgrade — where the
            // permission appears, ungranted — look like the user revoked
            // something they were never asked about, and fire the warning popup.
            if (isApplicable(p) && isGranted(p)) {
                editor.putBoolean(keyGranted(p), true)
                if (p == AppPermission.NOTIFICATIONS) editor.remove(keyMuted(p))
            }
        }
        editor.apply()
    }

    /**
     * User-facing opt-out per permission for the warning popup. For
     * [AppPermission.NOTIFICATIONS] it also stops the launch prompt — see
     * [shouldRequestNotificationsAtLaunch] — and [recordCurrentGrants] lifts
     * it once they are granted again.
     */
    fun isMuted(p: AppPermission): Boolean =
        prefs.getBoolean(keyMuted(p), false)

    fun setMuted(p: AppPermission, muted: Boolean) {
        prefs.edit().putBoolean(keyMuted(p), muted).apply()
    }

    /**
     * Whether a cold start should raise the system notification prompt on its
     * own. A muted permission is not asked for: stock Android stops showing
     * the prompt after two denials anyway, but HyperOS shows it on every
     * launch, so the user's "以後不再提醒" is the only thing that ends it.
     *
     * True implies API 33+ (via [isApplicable]); the annotation tells lint so,
     * which keeps the POST_NOTIFICATIONS reference at the call site clean.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun shouldRequestNotificationsAtLaunch(): Boolean =
        isApplicable(AppPermission.NOTIFICATIONS) &&
            !isGranted(AppPermission.NOTIFICATIONS) &&
            !isMuted(AppPermission.NOTIFICATIONS)

    /**
     * Whether an "allow notifications" tap should raise the runtime prompt
     * rather than open the settings page: only while the app has never had
     * the permission and the user has never turned one of its prompts down.
     * Past either, the OS may answer the prompt with a silent denial — stock
     * Android after two refusals, HyperOS with notifications switched off —
     * and a tap that only asked would do nothing at all.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun canPromptForNotifications(): Boolean =
        isApplicable(AppPermission.NOTIFICATIONS) &&
            !isGranted(AppPermission.NOTIFICATIONS) &&
            !wasPreviouslyGranted(AppPermission.NOTIFICATIONS) &&
            !wasDeclined(AppPermission.NOTIFICATIONS)

    /**
     * Permissions that are off after the user had them on or just turned the
     * launch prompt down, AND have not been muted. Feeds the resume-time
     * warning popup. The "turned down" half matters for someone who never
     * granted notifications: without it they never see the popup, never get
     * its 以後不再提醒, and HyperOS prompts them on every launch for good.
     */
    fun revokedOrDeclinedUnmuted(): List<AppPermission> =
        AppPermission.entries.filter { p ->
            isApplicable(p) && (wasPreviouslyGranted(p) || p in unacknowledgedRefusals) &&
                !isGranted(p) && !isMuted(p)
        }

    /**
     * Intent to take the user to the right system settings page to fix [p].
     * For NOTIFICATIONS that is the app's notification page, which works
     * whether or not the runtime prompt can still be shown; callers that want
     * the prompt while it is possible launch RequestPermission themselves.
     */
    fun settingsIntent(p: AppPermission): Intent? = when (p) {
        AppPermission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

        AppPermission.EXACT_ALARM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = "package:${context.packageName}".toUri()
                }
            } else null
        }

        AppPermission.BATTERY_OPTIMIZATION -> batterySettingsIntents().firstOrNull()

        AppPermission.PROMOTED_NOTIFICATIONS -> {
            // isApplicable, not the API level: on a skin with no chip surface
            // the page may well exist and open, and toggling it would change
            // nothing. Offering no destination is more honest than a dead end.
            if (isApplicable(AppPermission.PROMOTED_NOTIFICATIONS)) {
                // On ColorOS 16.0.5 this opens the switch itself. The platform
                // warns it may not exist on every build, and on MagicOS 10 it
                // does not; openSettings falls back for that.
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else null
        }
    }

    /**
     * Opens the best available system screen to let the user fix [p].
     * Returns true if we managed to launch an activity.
     */
    fun openSettings(p: AppPermission): Boolean {
        // Battery optimization / background restriction screens vary a lot by Android version/OEM.
        // We only link to non-restricted settings pages.
        if (p == AppPermission.BATTERY_OPTIMIZATION) {
            for (intent in batterySettingsIntents()) {
                if (tryStartActivity(intent)) return true
            }
            return false
        }

        val intent = settingsIntent(p) ?: return false
        if (tryStartActivity(intent)) return true

        // MagicOS 10 has no promotion page, and there the chip follows the
        // app's notifications, so their page is the honest next best. Without
        // this the green row is a tap that does nothing.
        return p == AppPermission.PROMOTED_NOTIFICATIONS &&
            settingsIntent(AppPermission.NOTIFICATIONS)?.let(::tryStartActivity) == true
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun batterySettingsIntents(): List<Intent> {
        val pkgUri = "package:${context.packageName}".toUri()
        val candidates = mutableListOf<Intent>()
        // Prefer the global battery-optimization app list first (user can find TigerDuck there).
        candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        candidates += Intent("android.settings.APP_BATTERY_SETTINGS").apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            data = pkgUri
        }
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = pkgUri }
        return candidates
    }

    private fun keyMuted(p: AppPermission) = "muted_${p.name}"
    private fun keyDeclined(p: AppPermission) = "declined_${p.name}"

    companion object {
        private const val PREFS_NAME = "tigerduck_permissions"

        /** Set once [forgetInferredChipGrant] has run on this install. */
        private const val KEY_CHIP_GRANT_REREAD = "chip_grant_reread"

        private fun keyGranted(p: AppPermission) = "granted_${p.name}"

        /**
         * v2.2 reported the chip granted on every ColorOS 16 phone without
         * asking the platform, and [recordCurrentGrants] banked that. Now that
         * the platform is asked, a phone whose switch is still at its default,
         * off, would have the warning popup say the chip "was previously
         * enabled", which it never was. [chipWasAssumed] is true on every Oplus
         * phone with a chip, which covers every phone v2.2 answered for, and
         * there the flag is dropped once; the next [recordCurrentGrants] banks
         * it again wherever the chip really is on, so a later turn-off is
         * still warned about.
         *
         * A flag v2.0 or v2.1 banked, which did ask the platform, is dropped
         * with them: nothing tells the two apart. That costs the popup only
         * to someone who skipped v2.2, had the switch on and turned it off
         * before this upgrade, and the Live Updates screen still shows them
         * the gap. Keeping every flag would put the false claim in front of
         * every phone still at the default.
         */
        internal fun forgetInferredChipGrant(prefs: SharedPreferences, chipWasAssumed: Boolean) {
            if (prefs.getBoolean(KEY_CHIP_GRANT_REREAD, false)) return
            val editor = prefs.edit()
            if (chipWasAssumed) editor.remove(keyGranted(AppPermission.PROMOTED_NOTIFICATIONS))
            editor.putBoolean(KEY_CHIP_GRANT_REREAD, true).apply()
        }

        @StringRes
        fun displayNameResId(p: AppPermission): Int = when (p) {
            AppPermission.NOTIFICATIONS -> R.string.permission_notifications_name
            AppPermission.EXACT_ALARM -> R.string.permission_exact_alarm_name
            AppPermission.BATTERY_OPTIMIZATION -> R.string.permission_battery_optimization_name
            AppPermission.PROMOTED_NOTIFICATIONS ->
                R.string.permission_promoted_notifications_name
        }

        @StringRes
        fun descriptionResId(p: AppPermission): Int = when (p) {
            AppPermission.NOTIFICATIONS ->
                R.string.permission_notifications_description

            AppPermission.EXACT_ALARM ->
                R.string.permission_exact_alarm_description

            AppPermission.BATTERY_OPTIMIZATION ->
                R.string.permission_battery_optimization_description

            AppPermission.PROMOTED_NOTIFICATIONS ->
                R.string.permission_promoted_notifications_description
        }
    }
}
