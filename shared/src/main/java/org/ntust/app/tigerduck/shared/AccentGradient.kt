package org.ntust.app.tigerduck.shared

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal

/** How an accent gradient lays its stops over a shape, by the name the iPhone stores and sends. */
enum class AccentGradientStyle(val wireName: String) {
    Linear("linear"),
    Radial("radial"),
    Angular("angular"),
    Mesh("mesh");

    companion object {
        fun fromWireName(name: String): AccentGradientStyle? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * The accent picked: one to five colors, its [stops], as RGB hexes without alpha, and how a fill
 * lays them out: its [style], and for a linear one its [direction]. The iPhone's `AccentGradient`
 * (tigerduck-app#1004), stored and sent in its JSON ([encode]).
 *
 * The phone stores it beside `accentColorHex`, which still holds the first stop, so a build without
 * gradients, a widget, the watch and anything else that draws one color reads that. A gradient
 * counts only while its first stop is that color ([saved]): a single color picked anywhere,
 * an older build included, replaces it.
 *
 * Here in `:shared` because the watch draws it too.
 */
@ConsistentCopyVisibility
data class AccentGradient private constructor(
    val stops: List<Int>,
    val style: AccentGradientStyle,
    val direction: AccentGradientDirection,
) {
    val first: Int get() = stops[0]

    /** More than one color: a single color is drawn as itself, in any style. */
    val isGradient: Boolean get() = stops.size > 1

    val canAddStop: Boolean get() = stops.size < STOP_COUNTS.last

    /** These [stops] in place of its own, each kept to its RGB, or this accent unchanged for none or over five. */
    fun withStops(stops: List<Int>): AccentGradient = of(stops, style, direction) ?: this

    fun withStyle(style: AccentGradientStyle): AccentGradient = copy(style = style)

    fun withDirection(direction: AccentGradientDirection): AccentGradient = copy(direction = direction)

    fun adding(stop: Int): AccentGradient = withStops(stops + stop)

    fun removing(index: Int): AccentGradient =
        if (index in stops.indices) withStops(stops.filterIndexed { i, _ -> i != index }) else this

    fun replacing(index: Int, stop: Int): AccentGradient =
        if (index in stops.indices) withStops(stops.toMutableList().apply { set(index, stop) }) else this

    /** The stop at [from] moved to [to], the others closing up behind it. */
    fun moving(from: Int, to: Int): AccentGradient {
        if (from !in stops.indices || to !in stops.indices || from == to) return this
        return withStops(stops.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * The colors of a 3 by 3 mesh, row by row from the top left, as the iPhone computes them: each
     * stop on a spot of its own, and every other point the stops' blend, each weighed by the inverse
     * of its squared distance.
     */
    val meshColors: List<Int>
        get() {
            val spots = MESH_SPOTS[stops.size - 1]
            return List(MESH_POINTS) { index ->
                val x = (index % 3) / 2.0
                val y = (index / 3) / 2.0
                val distances = spots.map { (sx, sy) -> (sx - x) * (sx - x) + (sy - y) * (sy - y) }
                val on = distances.indexOf(0.0)
                if (on >= 0) return@List stops[on]
                val weights = distances.map { 1 / it }
                val total = weights.sum()
                listOf(16, 8, 0).fold(0) { rgb, shift ->
                    val channel = stops.indices.sumOf { ((stops[it] shr shift) and 0xFF) * weights[it] }
                    rgb or (Math.round(channel / total).toInt() shl shift)
                }
            }
        }

    /** The iPhone's JSON for it: `{"stops":[…],"style":"linear","direction":"leftToRight"}`. */
    fun encode(): String = JsonObject().apply {
        add("stops", JsonArray().apply { stops.forEach { add(it) } })
        addProperty("style", style.wireName)
        addProperty("direction", direction.wireName)
    }.toString()

    companion object {
        val STOP_COUNTS = 1..5

        private const val MESH_POINTS = 9

        /** Where each stop sits on the mesh, for one stop to five, as the iPhone places them. */
        private val MESH_SPOTS: List<List<Pair<Double, Double>>> = listOf(
            listOf(0.5 to 0.5),
            listOf(0.0 to 0.0, 1.0 to 1.0),
            listOf(0.0 to 0.0, 1.0 to 0.0, 0.5 to 1.0),
            listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0),
            listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.5 to 0.5),
        )

        /** [hex] alone, an accent of one color, which any style draws as itself. */
        fun color(hex: Int): AccentGradient =
            AccentGradient(listOf(hex and 0xFFFFFF), AccentGradientStyle.Linear, AccentGradientDirection.DEFAULT)

        /** These stops, kept to their RGB, or null for none or over five. */
        fun of(
            stops: List<Int>,
            style: AccentGradientStyle = AccentGradientStyle.Linear,
            direction: AccentGradientDirection = AccentGradientDirection.DEFAULT,
        ): AccentGradient? =
            if (stops.size in STOP_COUNTS) AccentGradient(stops.map { it and 0xFFFFFF }, style, direction) else null

        /**
         * The accent as saved: [gradient] while it is one and still starts with [accentHex], else
         * [accentHex] alone, so a color a build without gradients picks replaces it.
         */
        fun saved(gradient: AccentGradient?, accentHex: Int): AccentGradient =
            gradient?.takeIf { it.isGradient && it.first == accentHex and 0xFFFFFF } ?: color(accentHex)

        /**
         * The gradient the release before this one stored, or sent the watch: from [startHex] to
         * [endHex], running [directionCode]'s way, as two stops of a linear gradient. Null when
         * either end is no color, as -1, its "no gradient", is not.
         */
        fun legacy(startHex: Int, endHex: Int, directionCode: Int): AccentGradient? {
            if (startHex !in 0..0xFFFFFF || endHex !in 0..0xFFFFFF) return null
            return of(listOf(startHex, endHex), AccentGradientStyle.Linear, AccentGradientDirection.fromCode(directionCode))
        }

        /**
         * The accent [json] holds, as [encode] writes it, or null for anything else: a damaged
         * value, and one a newer build wrote with a style, a direction or a number of stops this
         * build does not know, reads as no gradient, so whatever carries it still reads the rest.
         */
        fun decode(json: String?): AccentGradient? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null
            val stops = (root.get("stops") as? JsonArray)?.map { wholeNumber(it) ?: return null } ?: return null
            val style = (root.get("style") as? JsonPrimitive)?.takeIf { it.isString }?.asString
                ?.let(AccentGradientStyle::fromWireName) ?: return null
            val direction = (root.get("direction") as? JsonPrimitive)?.takeIf { it.isString }?.asString
                ?.let(AccentGradientDirection::fromWireName) ?: return null
            return of(stops, style, direction)
        }

        /** A JSON number with no fraction, kept to its low 32 bits, as the iPhone's `Int` masked to RGB keeps it. */
        private fun wholeNumber(element: com.google.gson.JsonElement): Int? {
            val number = (element as? JsonPrimitive)?.takeIf { it.isNumber } ?: return null
            val value = runCatching { BigDecimal(number.asString) }.getOrNull() ?: return null
            return runCatching { value.toBigIntegerExact().toLong().toInt() }.getOrNull()
        }
    }
}
