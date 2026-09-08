package org.ntust.app.tigerduck.liveactivity

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Guards the Android 16 "Live Update" status-bar chip.
 *
 * Promotion is decided by the system, silently: a notification that fails any
 * one of `Notification.hasPromotableCharacteristics()` simply posts as an
 * ordinary ongoing notification with no error anywhere. That is how a
 * `setColorized(true)` call sat in [LiveActivityNotifier] disqualifying the
 * chip for an entire release cycle. This asserts the outcome rather than the
 * call, so the next well-meaning builder change fails here instead of in a
 * user's status bar.
 */
@RunWith(AndroidJUnit4::class)
class LiveActivityPromotionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantPostNotifications() {
        // The Gradle connectedAndroidTest task installs the app under test
        // without -g, so on API 33+ POST_NOTIFICATIONS starts denied and the
        // notifier returns before posting anything — which reads as "the chip
        // is broken" rather than "the test has no permission".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, POST_NOTIFICATIONS)
        }
    }

    @After
    fun tearDown() {
        manager.cancel(LiveActivityNotifier.NOTIFICATION_ID)
    }

    @Test
    fun inClassNotificationIsPromotedToAStatusBarChip() {
        assumeTrue(
            "Promoted notifications need API 36+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA,
        )
        assumeTrue(
            "The user must allow promoted notifications for this app",
            NotificationManagerCompat.from(context).canPostPromotedNotifications(),
        )

        postInClass()

        // Promotion is decided during ranking, a step or two after the post
        // itself lands, so read until the flag settles rather than once.
        val promoted = awaitPosted { it.flags and FLAG_PROMOTED_ONGOING != 0 } != null

        assertTrue(
            "Live Update was posted but the system declined to promote it; " +
                "check hasPromotableCharacteristics() — most likely a new " +
                "setColorized(true), a custom RemoteViews, or a style the " +
                "platform will not promote.",
            promoted,
        )
    }

    /**
     * The progress bar is what the shade shows; the chip only carries the
     * countdown. Posting through the real notifier keeps this honest about
     * the fraction actually reaching the notification.
     */
    @Test
    fun inClassNotificationCarriesAProgressBar() {
        val extras = postInClass().extras
        assertEquals(100, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(32, extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    private fun postInClass(): Notification {
        val notifier = LiveActivityNotifier(context, LiveActivityPreferences(context))
        notifier.apply(
            LiveActivitySnapshot(
                scenario = LiveActivityScenario.IN_CLASS,
                title = "Promotion Test",
                subtitle = "09:10–10:00",
                locationText = "TR-412",
                instructor = "Instrumentation",
                countdownTarget = Date(System.currentTimeMillis() + 34 * 60_000L),
                progress = 0.32,
                accentHex = 0xF5A623,
                sourceId = "promotion-test",
            )
        )
        return requireNotNull(awaitPosted { true }) { "Live Update was not posted at all" }
    }

    /**
     * `NotificationManager.notify` hands off to a system-server handler and
     * returns, so the notification is not in `activeNotifications` the instant
     * we ask. Poll instead of sleeping a magic number.
     */
    private fun awaitPosted(predicate: (Notification) -> Boolean): Notification? {
        val deadline = System.currentTimeMillis() + POST_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications
                .firstOrNull { it.id == LiveActivityNotifier.NOTIFICATION_ID }
                ?.notification
                ?.takeIf(predicate)
                ?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        /** `Notification.FLAG_PROMOTED_ONGOING`, which is @FlaggedApi and not always resolvable. */
        const val FLAG_PROMOTED_ONGOING = 0x00040000
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val POST_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
