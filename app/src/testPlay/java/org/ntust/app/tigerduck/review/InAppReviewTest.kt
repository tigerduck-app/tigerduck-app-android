package org.ntust.app.tigerduck.review

import android.app.Activity
import android.app.Application
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [InAppReview] over Play's own fake (tigerduck-app-android#332). Play only:
 * fdroid ships a stub at the same FQN that never hands out a flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InAppReviewTest {

    // Play's fake ignores the activity; one that was never attached is enough.
    private val activity = Activity()

    @Test
    fun `hands out the flow Play offers, and launches it`() = runTest {
        val review = InAppReview(FakeReviewManager(RuntimeEnvironment.getApplication()))
        val flow = review.request()
        assertNotNull(flow)
        assertTrue(review.launch(activity, flow!!))
    }

    @Test
    fun `hands out nothing when Play refuses, so another moment can try`() = runTest {
        val refusing = object : ReviewManager {
            override fun requestReviewFlow(): Task<ReviewInfo> =
                Tasks.forException(IllegalStateException("no Play Store"))

            override fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Task<Void> =
                throw AssertionError("launched without a flow")
        }
        assertNull(InAppReview(refusing).request())
    }
}
