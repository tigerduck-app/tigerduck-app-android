package org.ntust.app.tigerduck.push

import android.os.Build

/** Longest model the backend accepts (`device_model`, VARCHAR(64)); longer fails the whole request. */
private const val MAX_DEVICE_MODEL_LENGTH = 64

/**
 * The hardware model reported at login and on every register call, for
 * support work in the portal — "Google Pixel 8", "Samsung SM-S918B".
 *
 * Some makers already lead [model] with their own name and some don't, so
 * the maker is added only when it is missing. Blank parts are dropped, and
 * nothing at all gives null rather than an empty string.
 */
internal fun deviceModelName(manufacturer: String?, model: String?): String? {
    val maker = manufacturer?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    val full = when {
        name.isEmpty() -> maker
        maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
        else -> "${maker.replaceFirstChar { it.titlecase() }} $name"
    }
    return full.take(MAX_DEVICE_MODEL_LENGTH).ifEmpty { null }
}

internal fun currentDeviceModel(): String? = deviceModelName(Build.MANUFACTURER, Build.MODEL)
