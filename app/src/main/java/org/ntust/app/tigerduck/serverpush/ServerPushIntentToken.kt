package org.ntust.app.tigerduck.serverpush

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-install random token shared between
 * [org.ntust.app.tigerduck.push.FcmService] (which embeds it into the popup
 * PendingIntent's extras) and [org.ntust.app.tigerduck.MainActivity] (which
 * validates it before honouring `tigerduck://server-push/...` deep links).
 *
 * MainActivity is exported as the launcher, so without this gate any
 * installed app could craft an explicit intent against it with
 * attacker-chosen title / body and the user would see an in-app popup
 * under TigerDuck branding. The token only ever lives in the app's
 * private SharedPreferences and as a one-shot Intent extra, neither of
 * which is readable by other apps.
 *
 * Persistent (not per-process) because PendingIntents created by
 * FcmService survive across process death — a notification tapped
 * minutes after FcmService exited still has to validate in a fresh
 * MainActivity process.
 */
@Singleton
class ServerPushIntentToken @Inject constructor(
    @ApplicationContext context: Context,
) {
    val value: String

    init {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        value = prefs.getString(KEY, null) ?: run {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            // commit (not apply) — FcmService stores this value into a
            // PendingIntent right after retrieval; an unflushed apply could
            // be lost if the process is killed before the disk write lands,
            // leaving MainActivity unable to validate a notification tapped
            // later.
            prefs.edit(commit = true) { putString(KEY, hex) }
            hex
        }
    }

    companion object {
        const val EXTRA_NAME = "org.ntust.app.tigerduck.serverpush.TOKEN"
        private const val PREFS = "server_push_intent"
        private const val KEY = "token"
    }
}
