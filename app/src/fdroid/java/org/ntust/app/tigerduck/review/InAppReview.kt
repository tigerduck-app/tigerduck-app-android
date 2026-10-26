package org.ntust.app.tigerduck.review

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. That build has no Play Store to rate the app on, so there is
 * no review flow to hand out; [request] is always null, so [ReviewPrompt]
 * records nothing as asked. Same FQN and shape as the play-flavor
 * implementation so `MainActivity` in `main/` can inject and call it
 * regardless of which flavor is being built — same pattern as
 * `UpdateChecker`. No GMS dependencies, so this compiles on fdroid.
 */
@Singleton
class InAppReview @Inject constructor() {

    /** Never handed out here; declared so `main/` compiles against play's shape. */
    class Flow private constructor()

    suspend fun request(): Flow? = null

    fun launch(activity: Activity, flow: Flow): Boolean = false
}
