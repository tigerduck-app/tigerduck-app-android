package org.ntust.app.tigerduck.wear

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.ntust.app.tigerduck.wear.complication.ComplicationUpdateWorker
import org.ntust.app.tigerduck.wear.data.ScheduleRepository

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Apply the cached language tag to a config-overridden base context so
        // every resource lookup in this activity (including the first frame's
        // titles) resolves against the right locale. AppCompatDelegate /
        // LocaleManager also get notified for system-side state (per-app
        // locale persistence, settings UI surface).
        val tag =
            runCatching { ScheduleRepository.get(newBase).readLanguageTagBlocking() }.getOrNull()
        if (tag.isNullOrBlank()) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = java.util.Locale.forLanguageTag(tag)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))

        // Persist the per-app locale with the platform too so the system
        // settings ("App languages") reflect it and survive process death.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java)
                ?.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
        ComplicationUpdateWorker.ensureScheduled(this)
    }
}
