package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.util.logSummary

/**
 * The device's requests that carry its secret, as iOS's `PushDeviceRequests`: the anonymous
 * registration, `POST /devices/anonymous`, which the announce and the signed-out push opt-out send,
 * and the account's, `POST /devices/register`, by whose secret the backend links the two.
 */
internal class DeviceRequests(
    private val identity: PushIdentity,
    private val sendAnonymous: suspend (AnonymousDeviceRequest) -> Unit,
) {
    /**
     * Sends the anonymous registration with [token] and [serverPushEnabled]. A refusal of the secret
     * is recorded before it is thrown, so the next launch with no session starts over
     * ([PushIdentity]), unless a request bound the secret while this one was out. One that went
     * through with the secret and a token has the secret bound, which ends a refusal recorded
     * before. Throws, sending nothing, while the secret cannot be read.
     */
    suspend fun send(token: String, serverPushEnabled: Boolean): Unit = send(token, serverPushEnabled) {}

    // [send], telling [refused] whether the disk took the refusal; on the I/O dispatcher, as the
    // secret's store reads and writes the disk.
    private suspend fun send(token: String, serverPushEnabled: Boolean, refused: (recorded: Boolean) -> Unit) {
        withContext(Dispatchers.IO) {
            val secret = identity.secret()
            val sentAt = identity.bindings()
            try {
                sendAnonymous(
                    AnonymousDeviceRequest(
                        deviceId = identity.uuid(),
                        deviceClass = identity.deviceClass(),
                        pushToken = token,
                        serverPushEnabled = serverPushEnabled,
                        deviceSecret = secret,
                    )
                )
            } catch (e: Exception) {
                if (e.backendError()?.refusesDeviceSecret == true) {
                    refused(identity.recordRefusal(unlessBoundSince = sentAt))
                }
                throw e
            }
            if (secret != null) identity.clearRefusal()
        }
    }

    /**
     * Registers the physical device, with no account attached, with the server-push opt-out as
     * [serverPushEnabled] says, on every announce: this row is what operator targeting filters on,
     * and while signed out the preferences PATCH has no session to authenticate with.
     *
     * Returns the failure rather than throwing, and never writes the diagnostic: the caller decides
     * what a failure here means. Signed in it is a background detail — cloud sync still works —
     * while signed out it is the only registration there was, so it is the thing to report.
     */
    suspend fun announce(token: String, serverPushEnabled: Boolean): Throwable? =
        try {
            send(token, serverPushEnabled)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "device announce failed: ${e.logSummary()}")
            e
        }

    /**
     * The signed-out half of the server-push opt-out, as iOS's `sendServerPushOptOut`. A refusal of
     * the secret on a device that stores no session, nor one a sign-out keeps, is no failure once
     * the refusal is on disk: the next launch starts over, and its announce carries the opt-out the
     * caller keeps. Otherwise nothing would send it, so the refusal is thrown.
     */
    suspend fun sendOptOut(token: String, serverPushEnabled: Boolean) {
        var recorded = false
        try {
            send(token, serverPushEnabled) { recorded = it }
        } catch (e: Exception) {
            if (e.backendError()?.refusesDeviceSecret != true || !recorded || !identity.startsOverAtNextLaunch()) throw e
            Log.i(TAG, "server push opt-out kept for the next announce")
        }
    }

    /**
     * The account's registration of this device with [token], in the app's language [locale]; the
     * caller adds the TigerSync switch. Throws while the secret cannot be read.
     */
    fun registerRequest(token: String, locale: String?) = DeviceRegisterRequest(
        clientDeviceId = identity.uuid(),
        deviceClass = identity.deviceClass(),
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
        deviceModel = currentDeviceModel(),
        pushToken = PushTokenIn(tokenValue = token),
        locale = locale,
        deviceSecret = identity.secret(),
    )

    private companion object {
        const val TAG = "Push.Register"
    }
}
