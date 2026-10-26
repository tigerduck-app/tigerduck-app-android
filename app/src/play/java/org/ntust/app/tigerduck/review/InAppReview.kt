package org.ntust.app.tigerduck.review

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor launcher for Google Play's in-app review (issue #332). When
 * to ask is [ReviewPrompt]'s decision, in `main/`; this only fetches the
 * flow Play hands out ([request]) and starts it over the activity on screen
 * ([launch]), as two steps so the caller can re-check its gates in between.
 * Whether the dialog actually shows is Play's call — it has quotas of its
 * own — and Play does not say, so a started flow is the one ask the caller
 * gets.
 *
 * The fdroid variant ships a stub at the same FQN that never hands out a
 * flow, so `MainActivity` in `main/` calls this without conditional code —
 * same pattern as `UpdateChecker` and `FcmBootstrap`.
 */
@Singleton
class InAppReview(private val manager: ReviewManager) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(ReviewManagerFactory.create(context))

    /** A flow Play handed out, for [launch] once the caller has decided the moment still holds. */
    class Flow internal constructor(internal val info: ReviewInfo)

    /**
     * Asks Play for a flow: a round trip, so the caller re-checks its gates
     * before [launch]. Null when Play would not hand one out — no Play Store,
     * a sideloaded install, offline — so the caller may try at another
     * moment. Nothing here reaches the user as an error: a rating is never
     * worth one.
     */
    suspend fun request(): Flow? = try {
        Flow(manager.requestReviewFlow().await())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "review flow not handed out: ${e.logSummary()}")
        null
    }

    /**
     * Starts [flow] over [activity]. True once it started; false when the
     * start itself threw — like appUpdateInfo in UpdateChecker, a Play call
     * can throw synchronously against a broken Play install.
     */
    fun launch(activity: Activity, flow: Flow): Boolean = runCatching {
        manager.launchReviewFlow(activity, flow.info)
            .addOnFailureListener { Log.w(TAG, "review flow failed: ${it.logSummary()}") }
    }.onFailure { Log.w(TAG, "review flow not launched: ${it.logSummary()}") }.isSuccess

    private companion object {
        const val TAG = "InAppReview"
    }
}
