package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
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
 */
enum class AppPermission { NOTIFICATIONS, EXACT_ALARM, BATTERY_OPTIMIZATION }

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
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    /** False if the permission doesn't exist on this API level (treat as granted). */
    fun isApplicable(p: AppPermission): Boolean = when (p) {
        AppPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        AppPermission.EXACT_ALARM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        AppPermission.BATTERY_OPTIMIZATION -> true
    }

    fun state(p: AppPermission): PermissionState =
        PermissionState(p, isGranted(p), isApplicable(p))

    fun states(): List<PermissionState> =
        AppPermission.entries.map { state(it) }

    /** True if user granted this at least once in the past (tracked by us). */
    fun wasPreviouslyGranted(p: AppPermission): Boolean =
        prefs.getBoolean(keyGranted(p), false)

    /** Snapshot of current grant state into the "previously granted" flag. */
    fun recordCurrentGrants() {
        val editor = prefs.edit()
        for (p in AppPermission.entries) {
            if (isGranted(p)) editor.putBoolean(keyGranted(p), true)
        }
        editor.apply()
    }

    /** User-facing opt-out per permission for the revocation popup. */
    fun isMuted(p: AppPermission): Boolean =
        prefs.getBoolean(keyMuted(p), false)

    fun setMuted(p: AppPermission, muted: Boolean) {
        prefs.edit().putBoolean(keyMuted(p), muted).apply()
    }

    /**
     * Permissions the user previously had on but are now off AND have not
     * been muted. Feeds the resume-time warning popup.
     */
    fun revokedSinceGrantUnmuted(): List<AppPermission> =
        AppPermission.entries.filter { p ->
            isApplicable(p) && wasPreviouslyGranted(p) && !isGranted(p) && !isMuted(p)
        }

    /**
     * Intent to take the user to the right system settings page to fix [p].
     * NOTIFICATIONS returns null when the runtime prompt is still possible —
     * caller should use ActivityResultContracts.RequestPermission instead.
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
        AppPermission.BATTERY_OPTIMIZATION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ).apply { data = "package:${context.packageName}".toUri() }
    }

    private fun keyGranted(p: AppPermission) = "granted_${p.name}"
    private fun keyMuted(p: AppPermission) = "muted_${p.name}"

    companion object {
        private const val PREFS_NAME = "tigerduck_permissions"

        fun displayName(p: AppPermission): String = when (p) {
            AppPermission.NOTIFICATIONS -> "通知權限"
            AppPermission.EXACT_ALARM -> "精確鬧鐘"
            AppPermission.BATTERY_OPTIMIZATION -> "關閉省電限制"
        }

        fun description(p: AppPermission): String = when (p) {
            AppPermission.NOTIFICATIONS ->
                "允許 App 顯示通知，否則所有通知與即時動態都不會出現。"
            AppPermission.EXACT_ALARM ->
                "允許 App 在精確的時間送出作業與上課提醒；關閉後提醒可能延後數分鐘至十幾分鐘。"
            AppPermission.BATTERY_OPTIMIZATION ->
                "關閉系統對 App 的省電限制，確保 App 關閉或背景時仍能準時發送提醒。"
        }
    }
}
