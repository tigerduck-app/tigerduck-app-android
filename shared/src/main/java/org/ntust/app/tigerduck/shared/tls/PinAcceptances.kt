package org.ntust.app.tigerduck.shared.tls

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The JSON form [PinAcceptance]s take on disk and on the way to the watch:
 * `[{"set":…,"spkis":[…],"app_version":…,"accepted_at":…}]`. Written by hand rather than through
 * Gson's reflection, so R8 renaming nothing and an older reader skipping a newer field both hold.
 */
object PinAcceptances {

    fun encode(acceptances: Collection<PinAcceptance>): String = JsonArray().apply {
        acceptances.forEach { acceptance ->
            add(
                JsonObject().apply {
                    addProperty("set", acceptance.setId)
                    add("spkis", JsonArray().apply { acceptance.spkis.sorted().forEach(::add) })
                    addProperty("app_version", acceptance.appVersion)
                    addProperty("accepted_at", acceptance.acceptedAt)
                },
            )
        }
    }.toString()

    /** What [encode] wrote; an entry that doesn't read is left out, and unreadable text is none. */
    fun decode(text: String?): List<PinAcceptance> {
        val array = text?.let { runCatching { JsonParser.parseString(it) }.getOrNull() } as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val entry = element.asJsonObject
                PinAcceptance(
                    setId = entry.get("set").asString,
                    spkis = entry.getAsJsonArray("spkis").map { it.asString }.toSet(),
                    appVersion = entry.get("app_version").asLong,
                    acceptedAt = entry.get("accepted_at").asLong,
                )
            }.getOrNull()?.takeIf { it.setId.isNotBlank() && it.spkis.isNotEmpty() }
        }
    }
}
