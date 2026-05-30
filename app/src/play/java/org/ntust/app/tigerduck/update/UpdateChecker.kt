package org.ntust.app.tigerduck.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor update checker. Uses Play's `AppUpdateManager` solely to
 * discover an available versionCode, then surfaces a custom in-app prompt
 * (Update now / Later / Skip this version) instead of running Play's
 * FLEXIBLE/IMMEDIATE flow. "Update Now" deep-links into the Play Store
 * product page so the user upgrades through Play's normal install UI —
 * matching the iOS coordinator's three-button sheet pattern.
 *
 * The fdroid flavor ships a no-op stub at the same FQN so `MainActivity` in
 * `main/` can inject and call this regardless of flavor — same pattern as
 * `FcmBootstrap`.
 *
 * Every Play call is wrapped: a failed update check is a silent no-op for
 * background paths and never surfaces UI (issue #89 requirement). Manual
 * "Check for updates" taps from Settings explicitly want the failure
 * feedback, so they route through [checkManually] which records the
 * outcome in [lastManualCheckResult] for the Settings row to render.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
) {
    private val manager = AppUpdateManagerFactory.create(context)

    private val _pendingUpdate = MutableStateFlow<PendingUpdate?>(null)

    /** Set when an eligible update has been discovered and is awaiting the user's choice. */
    val pendingUpdate: StateFlow<PendingUpdate?> = _pendingUpdate.asStateFlow()

    private val _isCheckingForUpdate = MutableStateFlow(false)

    /** True while a manual [checkManually] call is in flight, for the Settings row spinner. */
    val isCheckingForUpdate: StateFlow<Boolean> = _isCheckingForUpdate.asStateFlow()

    private val _lastManualCheckResult = MutableStateFlow<ManualCheckResult?>(null)

    /**
     * Outcome of the most recent [checkManually] call. The Settings row
     * observes this to drive the "You're up to date" / "Couldn't reach the
     * store" alert. `Offered` is *not* a value here — when Play reports an
     * available update the existing [pendingUpdate] flow surfaces the regular
     * three-button dialog and stacking an alert on top of it would double
     * up. Cleared by [acknowledgeManualCheckResult].
     */
    val lastManualCheckResult: StateFlow<ManualCheckResult?> = _lastManualCheckResult.asStateFlow()

    /**
     * Query Play and arm [pendingUpdate] if eligible and not rate-limited.
     * Safe to call on every cold start / foreground; the gate eats dupes.
     */
    fun maybePromptForUpdate() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                runCatching {
                    if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@runCatching
                    val available = info.availableVersionCode()
                    val allowed = UpdatePromptGate.shouldStartFlow(
                        stalenessDays = info.clientVersionStalenessDays(),
                        availableVersionCode = available,
                        lastPromptVersionCode = appPreferences.lastUpdatePromptVersionCode,
                        lastPromptEpoch = appPreferences.lastUpdatePromptEpoch,
                        skippedVersionCode = appPreferences.skippedUpdateVersionCode,
                        now = System.currentTimeMillis(),
                    )
                    if (!allowed) return@runCatching

                    // Don't stamp the cooldown here — only Later does. A
                    // sheet-armed-but-dismissed-by-the-OS state (process
                    // death, etc.) must not be treated as if the user
                    // tapped Later.
                    _pendingUpdate.value = PendingUpdate(availableVersionCode = available)
                }.onFailure { Log.w(TAG, "update prompt evaluation failed", it) }
            }
            .addOnFailureListener { Log.w(TAG, "appUpdateInfo query failed", it) }
    }

    /** Re-check on return to foreground so a freshly-published build can arm. */
    fun resume(activity: Activity) {
        // Already showing — don't double-trigger.
        if (_pendingUpdate.value != null) return
        maybePromptForUpdate()
    }

    /**
     * User-initiated check from Settings → About. Bypasses the
     * [UpdatePromptGate] entirely (a user explicitly re-asking has already
     * waved off the "don't nag" policy) so a previously-skipped version
     * and a same-version cooldown both surface again. Records the outcome
     * in [lastManualCheckResult] for the Settings row to render the
     * "up to date" / "couldn't reach" alert. When Play reports an update,
     * the regular [pendingUpdate] flow drives the three-button dialog and
     * the manual-result alert stays quiet.
     */
    fun checkManually() {
        if (_isCheckingForUpdate.value) return
        _isCheckingForUpdate.value = true
        _lastManualCheckResult.value = null
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                _isCheckingForUpdate.value = false
                runCatching {
                    if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                        val available = info.availableVersionCode()
                        // Force-arm: a manual check explicitly bypasses the
                        // skip + cooldown the background path enforces. The
                        // result alert intentionally stays null in this
                        // branch — the dialog is the right surface.
                        _pendingUpdate.value = PendingUpdate(availableVersionCode = available)
                    } else {
                        // UPDATE_NOT_AVAILABLE, UNKNOWN, or a flexible/
                        // immediate update already in progress — all read
                        // as "nothing for the user to do here" from the
                        // Settings entry point.
                        _lastManualCheckResult.value = ManualCheckResult.UpToDate
                    }
                }.onFailure {
                    Log.w(TAG, "manual update check evaluation failed", it)
                    _lastManualCheckResult.value = ManualCheckResult.Failed
                }
            }
            .addOnFailureListener { error ->
                _isCheckingForUpdate.value = false
                Log.w(TAG, "appUpdateInfo manual query failed", error)
                // ERROR_APP_NOT_OWNED (-10) fires whenever the install
                // didn't come through Play — sideloaded APKs always hit
                // it, even ones signed with the release key. Treat it as
                // UpToDate rather than Failed: we genuinely don't know
                // whether an update is available, but surfacing
                // "Couldn't reach the App Store" would imply a network
                // failure when the real issue is the install source. A
                // sideloaded user gets the gentler "You have the latest
                // version" message; real Play users never see -10.
                val isNotOwned = error is InstallException &&
                    error.errorCode == InstallErrorCode.ERROR_APP_NOT_OWNED
                _lastManualCheckResult.value = if (isNotOwned) {
                    ManualCheckResult.UpToDate
                } else {
                    ManualCheckResult.Failed
                }
            }
    }

    /** Clear [lastManualCheckResult] after the user dismisses the alert. */
    fun acknowledgeManualCheckResult() {
        _lastManualCheckResult.value = null
    }

    /**
     * User tapped "Update Now". Deep-links to Play Store; clears the pending
     * prompt without stamping the Later cooldown (the next cold start will
     * re-evaluate, and once the install completes Play will report
     * `UPDATE_NOT_AVAILABLE` and the prompt stays quiet).
     */
    fun onUpdateNow(activity: Activity) {
        _pendingUpdate.value = null
        // applicationId — not packageName — is the Play store id and the
        // only correct value for the deep link. Strip any debug suffix that
        // the build flavor may have appended (e.g. ".debug") so the link
        // resolves on test installs too.
        val storeId = context.packageName.removeSuffix(".debug")
        val marketUri = Uri.parse("market://details?id=$storeId")
        val intent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            // The Play Store app refuses ACTION_VIEW into its own task
            // unless we hand it a fresh one; this matches Google's own
            // sample. Without it, the deep link silently no-ops on some
            // OEM launchers.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Play Store app missing (sideloaded play APK / stripped image):
            // fall back to the web product page. Same applicationId scheme.
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$storeId"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { activity.startActivity(webIntent) }
                .onFailure { Log.w(TAG, "Play Store deep link failed", it) }
        }
    }

    /**
     * User tapped "Later". Stamps the cooldown so the same versionCode is
     * suppressed for [UpdatePromptGate.COOLDOWN_MS]. A newer versionCode
     * landing later re-arms the prompt immediately.
     */
    fun onLater() {
        val pending = _pendingUpdate.value ?: return
        appPreferences.lastUpdatePromptVersionCode = pending.availableVersionCode
        appPreferences.lastUpdatePromptEpoch = System.currentTimeMillis()
        _pendingUpdate.value = null
    }

    /**
     * User tapped "Skip this version". Suppresses this exact versionCode
     * indefinitely; a newer build always re-arms because the gate's equality
     * check fails for it.
     */
    fun onSkipThisVersion() {
        val pending = _pendingUpdate.value ?: return
        appPreferences.skippedUpdateVersionCode = pending.availableVersionCode
        _pendingUpdate.value = null
    }

    /**
     * Back-press / outside-tap dismissal. Mirrors iOS swipe-to-dismiss:
     * clear without stamping the cooldown so the next foreground is free to
     * re-arm — tapping a button on the prompt is the path that stamps.
     */
    fun dismissPrompt() {
        _pendingUpdate.value = null
    }

    /**
     * Debug-only: arm a synthetic pending prompt so the Triggers screen can
     * retest the dialog without a real Play update available. Uses
     * [Int.MAX_VALUE] as the versionCode so it can never collide with a
     * skipped real version, and a synthetic name so the dialog body reads
     * as "obviously a debug fire".
     */
    fun armForDebug() {
        _pendingUpdate.value = PendingUpdate(
            availableVersionCode = Int.MAX_VALUE,
            availableVersionName = "99.0.0",
        )
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
