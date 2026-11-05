package org.ntust.app.tigerduck.clubs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Club
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamp
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument.Stamped

/**
 * The document's JSON, read and written through Gson's tree, never by reflection, so R8 renames
 * nothing it reads: `{"clubs": {"93": {"subscribed": true, "subscribed_at": "…", "color":
 * "#D35400", "color_at": "…"}}, "order": {"ids": [93, 33], "at": "…"}}`. Keys of `clubs` are
 * decimal ids. A value of the wrong type, or without its time, reads as absent, as the Apple
 * apps read it, so another writer's mistake cannot erase a choice.
 */
object ClubSubscriptionsWire {

    fun read(json: String?): ClubSubscriptionsDocument {
        val root = json?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }?.objectOrNull()
            ?: return ClubSubscriptionsDocument()
        return read(root)
    }

    fun read(root: JsonObject): ClubSubscriptionsDocument {
        val clubs = LinkedHashMap<Int, Club>()
        root.get("clubs")?.objectOrNull()?.entrySet()?.forEach { (key, value) ->
            val id = key.toIntOrNull()?.takeIf { it.toString() == key } ?: return@forEach
            val club = value.objectOrNull()?.let(::club) ?: return@forEach
            if (club != Club()) clubs[id] = club
        }
        val order = root.get("order")?.objectOrNull()?.let { order ->
            val ids = order.get("ids")?.takeIf { it.isJsonArray }?.asJsonArray?.let(::wholeNumbers)
            val at = order.stamp("at")
            if (ids != null && at != null) Stamped(ids, at) else null
        }
        return ClubSubscriptionsDocument(clubs, order)
    }

    /** The document as written, keys sorted. */
    fun json(document: ClubSubscriptionsDocument): JsonObject = JsonObject().apply {
        add("clubs", JsonObject().apply {
            document.clubs.entries.sortedBy { it.key.toString() }.forEach { (id, club) ->
                add(id.toString(), JsonObject().apply {
                    club.color?.let { addProperty("color", ClubColors.hex(it.value)); addProperty("color_at", it.at.text) }
                    club.subscribed?.let { addProperty("subscribed", it.value); addProperty("subscribed_at", it.at.text) }
                })
            }
        })
        document.order?.let { order ->
            add("order", JsonObject().apply {
                addProperty("at", order.at.text)
                add("ids", JsonArray().apply { order.value.forEach { add(it) } })
            })
        }
    }

    private fun club(json: JsonObject): Club {
        val subscribed = json.get("subscribed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        val color = json.get("color")?.stringOrNull()?.let(ClubColors::parse)
        val subscribedAt = json.stamp("subscribed_at")
        val colorAt = json.stamp("color_at")
        return Club(
            subscribed = if (subscribed != null && subscribedAt != null) Stamped(subscribed, subscribedAt) else null,
            color = if (color != null && colorAt != null) Stamped(color, colorAt) else null,
        )
    }

    /**
     * A list of whole numbers. A bad element is dropped, not the list, as the backend's `_numbers`
     * and the notification document's `asValidatedIntListOrNull` read theirs.
     */
    private fun wholeNumbers(array: JsonArray): List<Int> = array.mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
    }

    private fun JsonObject.stamp(key: String): Stamp? = get(key)?.stringOrNull()?.let(Stamp::read)

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
