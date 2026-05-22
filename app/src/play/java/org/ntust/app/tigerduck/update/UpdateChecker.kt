package org.ntust.app.tigerduck.update

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor in-app update checker (issue #89). Wraps Play's
 * `AppUpdateManager`, runs the FLEXIBLE flow (background download, prompt to
 * restart), and gates re-prompting via [UpdatePromptGate].
 *
 * The fdroid flavor ships a no-op stub at the same FQN so `MainActivity` in
 * `main/` can inject and call this regardless of flavor — same pattern as
 * `FcmBootstrap`.
 *
 * Every Play call is wrapped: a failed update check is a silent no-op and
 * never surfaces UI (issue #89 requirement).
 */
@Singleton
class UpdateChecker @Inject constructor(
    @param:ApplicationContext context: Context,
    private val appPreferences: AppPreferences,
) {
    private val manager = AppUpdateManagerFactory.create(context)

    private val _installReady = MutableStateFlow(false)
    /** True once a FLEXIBLE update has finished downloading and can be installed. */
    val installReady: StateFlow<Boolean> = _installReady.asStateFlow()

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            _installReady.value = true
        }
    }

    /** Query Play and start the FLEXIBLE flow if eligible and not rate-limited. */
    fun maybePromptForUpdate(activity: Activity) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                runCatching {
                    if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@runCatching
                    if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) return@runCatching
                    val allowed = UpdatePromptGate.shouldStartFlow(
                        stalenessDays = info.clientVersionStalenessDays(),
                        availableVersionCode = info.availableVersionCode(),
                        lastPromptVersionCode = appPreferences.lastUpdatePromptVersionCode,
                        lastPromptEpoch = appPreferences.lastUpdatePromptEpoch,
                        now = System.currentTimeMillis(),
                    )
                    if (!allowed) return@runCatching

                    appPreferences.lastUpdatePromptVersionCode = info.availableVersionCode()
                    appPreferences.lastUpdatePromptEpoch = System.currentTimeMillis()
                    manager.registerListener(installListener)
                    manager.startUpdateFlowForResult(
                        info,
                        activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        UPDATE_REQUEST_CODE,
                    )
                }.onFailure { Log.w(TAG, "update prompt failed", it) }
            }
            .addOnFailureListener { Log.w(TAG, "appUpdateInfo query failed", it) }
    }

    /** Re-check on return to foreground: surface an update that finished downloading while away. */
    fun resume(activity: Activity) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    _installReady.value = true
                }
            }
            .addOnFailureListener { Log.w(TAG, "appUpdateInfo resume query failed", it) }
    }

    /** Install a downloaded update (triggered by the snackbar "Restart" action). */
    fun completeUpdate() {
        runCatching {
            manager.completeUpdate()
            manager.unregisterListener(installListener)
        }.onFailure { Log.w(TAG, "completeUpdate failed", it) }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val UPDATE_REQUEST_CODE = 17_390
    }
}
