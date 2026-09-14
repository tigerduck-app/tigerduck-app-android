package org.ntust.app.tigerduck.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [preferencesPatchBlockedOnFdroid] — the guard
 * [PushApiClient.updateDevicePreferences] applies before it ever builds the
 * network request. It is the one check, at the shared client boundary, that
 * stands in for an early return in both
 * [PushRegistrationService.updateCloudSyncEnabled] and
 * [PushRegistrationService.updateSyncPreferences], neither of which has a
 * flavor guard of its own. [PushRegistrationService.updateServerPushOptOut]
 * already refuses itself earlier and never reaches this call on fdroid, so
 * it needs nothing here either way.
 *
 * `locale` is excluded on purpose: [PushRegistrationService.syncLocalePreference]
 * calls [PushApiClient.updateDevicePreferences] with only that field set, on
 * every flavor including fdroid, and must keep working.
 *
 * [PushApiClient] can't be constructed here — real OkHttp /
 * [org.ntust.app.tigerduck.data.preferences.AppPreferences] dependencies,
 * and this module has neither Robolectric nor a mocking library, the same
 * constraint [ServerPushFdroidTest] documents for [PushRegistrationService].
 * So this exercises the pure rule the client delegates to, with the flavor
 * passed explicitly.
 */
class PreferencesUpdateFdroidTest {

    @Test
    fun `fdroid refuses a cloud-sync preference change`() {
        assertTrue(
            "fdroid can never change a sync or push preference on the backend",
            preferencesPatchBlockedOnFdroid(
                UpdateDevicePreferencesRequest(cloudSyncEnabled = true),
                flavor = "fdroid",
            ),
        )
    }

    @Test
    fun `fdroid refuses a server-push preference change`() {
        assertTrue(
            "fdroid can never change a sync or push preference on the backend",
            preferencesPatchBlockedOnFdroid(
                UpdateDevicePreferencesRequest(serverPushEnabled = false),
                flavor = "fdroid",
            ),
        )
    }

    @Test
    fun `fdroid refuses a sync-content preference change`() {
        assertTrue(
            "fdroid can never change a sync or push preference on the backend",
            preferencesPatchBlockedOnFdroid(
                UpdateDevicePreferencesRequest(syncCourses = false),
                flavor = "fdroid",
            ),
        )
    }

    @Test
    fun `fdroid still allows a locale-only update`() {
        assertFalse(
            "syncLocalePreference must keep working on fdroid",
            preferencesPatchBlockedOnFdroid(
                UpdateDevicePreferencesRequest(locale = "zh-Hant-TW"),
                flavor = "fdroid",
            ),
        )
    }

    @Test
    fun `play never refuses a preference change`() {
        assertFalse(
            preferencesPatchBlockedOnFdroid(
                UpdateDevicePreferencesRequest(cloudSyncEnabled = true),
                flavor = "play",
            ),
        )
    }
}
