package org.ntust.app.tigerduck.mail.sanitize

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 1:1 parity with iOS #679 `MailReadableHTML.swift`.
 *
 * A sanitized mail document, ready to draw on either page with its unreadable text fixed.
 *
 * On a page, a text color under 7:1 (WCAG 2) against the backgrounds behind it moves to the
 * nearest OKLCH lightness that reaches it, or as close as any color gets, keeping its hue and
 * as much chroma as sRGB holds. That covers colors the sender set, and the color an element
 * with a background, or a table caption, but no color of its own inherits, which it is then
 * given. Text on a background this cannot read keeps its color. Only `color` values change,
 * as `#rrggbb`.
 */
class MailReadableHtml(private val html: String) {

    /** Whether the mail sets a text or background color of its own anywhere. */
    val setsColors: Boolean
    private val document: Document?
    private val nodes: List<InternalNode>
    /** `html`'s link and image targets, which no page may change. */
    private val targets: List<String>

    init {
        var hasColors = false
        val nodeList = mutableListOf<InternalNode>()
        var parsedDoc: Document? = null
        var parsedTargets = emptyList<String>()

        try {
            val parsed = Jsoup.parseBodyFragment(html)
            parsed.outputSettings().prettyPrint(false)
            parsedDoc = parsed
            parsedTargets = targets(parsed)

            val bodyChildren = parsed.body().children()
            val stack = ArrayDeque<Visit>()
            for (i in bodyChildren.indices.reversed()) {
                stack.addLast(Visit(element = bodyChildren[i], depth = 1, backdrop = Backdrop(), parent = null))
            }

            while (stack.isNotEmpty()) {
                val visit = stack.removeLast()
                val style = Style(visit.element.attr("style"))
                if (setsColor(visit.element, style)) {
                    hasColors = true
                }
                var backdrop = visit.backdrop
                var parent = visit.parent

                val layer = if (visit.depth <= MAXIMUM_DEPTH) {
                    val lyr = layer(visit.element, style)
                    backdrop = visit.backdrop.adding(lyr)
                    val n = node(visit.element, style, lyr, backdrop, parent)
                    if (n != null) {
                        nodeList.add(n)
                        parent = nodeList.size - 1
                    }
                    lyr
                } else {
                    Layer.None
                }

                // WebKit paints a table's background and border over its grid only, not its caption,
                // while both keep their table display.
                val captionBackdrop = if (visit.element.normalName() == "table" && keepsDisplay(style, setOf("table", "inline-table"))) {
                    visit.backdrop
                } else {
                    backdrop
                }

                val children = visit.element.children()
                for (i in children.indices.reversed()) {
                    val child = children[i]
                    val outside = child.normalName() == "caption" &&
                        keepsDisplay(Style(child.attr("style")), setOf("table-caption"))
                    stack.addLast(
                        Visit(
                            element = child,
                            depth = visit.depth + 1,
                            backdrop = if (outside) captionBackdrop else backdrop,
                            parent = parent,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            nodeList.clear()
        }

        setsColors = hasColors
        document = parsedDoc
        nodes = nodeList
        targets = parsedTargets
    }

    /**
     * The document on a page of [pageBackgroundHex], whose text is [pageForegroundHex] and whose
     * links are [linkForegroundHex]. With nothing to fix, or with a link or image target unlike
     * [html]'s, it is [html].
     *
     * Each call rewrites the one parsed document, setting every color it judges either to its
     * fix or back to what was sent, so pages come out the same in any order, but never two at once.
     */
    fun adjust(
        pageBackgroundHex: String,
        pageForegroundHex: String,
        linkForegroundHex: String,
    ): String {
        val body = document?.body() ?: return html
        if (nodes.isEmpty()) return html

        val page = parse(pageBackgroundHex, legacy = false) ?: RGBA(1.0, 1.0, 1.0)
        val pageText = parse(pageForegroundHex, legacy = false) ?: RGBA(0.0, 0.0, 0.0)
        val linkText = parse(linkForegroundHex, legacy = false) ?: RGBA(0.0, 0.48, 1.0)

        val shown = ArrayList<Shown>(nodes.size)
        val fixes = Fixes()
        var changed = false

        try {
            for (node in nodes) {
                val inherited = node.parent?.let { shown.getOrNull(it) }
                    ?: Shown(intended = pageText, displayed = pageText)
                val behind = node.backdrop.over(page)
                var now = inherited
                var fix: RGBA? = null

                when (val kind = node.kind) {
                    is NodeKind.Own -> {
                        if (kind.sent.inherits) {
                            fix = fix(inherited, behind, fixes)
                        } else {
                            now = Shown(intended = kind.sent.color, displayed = kind.sent.color)
                            val text = kind.sent.color
                            if (text != null && text.alpha > 0.0 && behind != null) {
                                fix = fixes.readable(text.over(behind), behind)
                            }
                        }
                        val finalValue = if (fix != null) {
                            val range = kind.sent.range
                            val sb = StringBuilder(kind.sent.value)
                            sb.replace(range.first, range.last + 1, fix.css)
                            sb.toString()
                        } else {
                            kind.sent.value
                        }
                        set(kind.sent.attribute, node.element, finalValue)
                    }
                    NodeKind.Link -> {
                        now = Shown(intended = linkText, displayed = linkText)
                        if (node.backdrop.alpha > 0.0 && behind != null) {
                            fix = fixes.readable(linkText, behind)
                        }
                        node.give(fix)
                    }
                    NodeKind.Background -> {
                        fix = fix(inherited, behind, fixes)
                        node.give(fix)
                    }
                }

                if (fix != null) {
                    now = now.copy(displayed = fix)
                    changed = true
                }
                shown.add(now)
            }

            if (!changed) return html
            val output = body.html()
            return if (targets(output) == targets) output else html
        } catch (_: Exception) {
            return html
        }
    }

    /**
     * What text in an inherited color needs on [behind]: nothing while the color shown reads
     * there, else the color the sender meant, made readable.
     */
    private fun fix(inherited: Shown, behind: RGBA?, fixes: Fixes): RGBA? {
        val displayed = inherited.displayed
        val intended = inherited.intended
        if (behind == null || displayed == null || displayed.alpha <= 0.0 || reads(displayed.over(behind), behind) ||
            intended == null || intended.alpha <= 0.0
        ) {
            return null
        }
        return fixes.readable(intended.over(behind), behind) ?: intended.over(behind)
    }

    // --- Internal Document Data Types ----------------------------------------

    private class Visit(
        val element: Element,
        val depth: Int,
        val backdrop: Backdrop,
        val parent: Int?,
    )

    private sealed interface NodeKind {
        class Own(val sent: Sent) : NodeKind
        object Link : NodeKind
        object Background : NodeKind
    }

    private class InternalNode(
        val element: Element,
        val kind: NodeKind,
        val backdrop: Backdrop,
        val parent: Int?,
        val style: String?,
    ) {
        fun give(color: RGBA?) {
            val given = if (color != null) {
                if (style != null) "$style; color: ${color.css}" else "color: ${color.css}"
            } else {
                style
            }
            set("style", element, given)
        }
    }

    private class Sent(
        val color: RGBA?,
        /** `inherit`, `currentcolor` or `unset`: the text is in the color its parent shows. */
        val inherits: Boolean = false,
        val attribute: String,
        val value: String,
        val range: IntRange,
    )

    /**
     * The color a node's text was meant in and the one it is drawn in. Never changed in place: a
     * node with no color of its own starts from its parent's, and a fix to it is its own, not the
     * parent's, or every sibling after it would start from that fix.
     */
    private data class Shown(
        val intended: RGBA?,
        val displayed: RGBA?,
    )

    private sealed interface Layer {
        object None : Layer
        object Unknown : Layer
        class ColorLayer(val color: RGBA) : Layer
    }

    private class Backdrop(
        val red: Double = 0.0,
        val green: Double = 0.0,
        val blue: Double = 0.0,
        val alpha: Double = 0.0,
        val isUnknown: Boolean = false,
    ) {
        fun adding(layer: Layer): Backdrop = when (layer) {
            Layer.None -> this
            Layer.Unknown -> Backdrop(red, green, blue, alpha, isUnknown = true)
            is Layer.ColorLayer -> {
                val top = layer.color
                if (top.alpha >= 1.0) {
                    Backdrop(top.red, top.green, top.blue, 1.0, isUnknown = false)
                } else {
                    val kept = 1.0 - top.alpha
                    Backdrop(
                        red = top.red * top.alpha + red * kept,
                        green = top.green * top.alpha + green * kept,
                        blue = top.blue * top.alpha + blue * kept,
                        alpha = top.alpha + alpha * kept,
                        isUnknown = isUnknown,
                    )
                }
            }
        }

        fun over(page: RGBA): RGBA? {
            if (isUnknown) return null
            val kept = 1.0 - alpha
            return RGBA(
                red = red + page.red * kept,
                green = green + page.green * kept,
                blue = blue + page.blue * kept,
            )
        }
    }

    private class Style(val text: String) {
        class Declaration(
            val property: String,
            val value: String,
            val valueRange: IntRange,
            val isImportant: Boolean,
        )

        val declarations: List<Declaration>

        init {
            val decls = mutableListOf<Declaration>()
            var searchStart = 0
            while (searchStart < text.length) {
                var semi = text.indexOf(';', searchStart)
                if (semi == -1) semi = text.length
                val piece = text.substring(searchStart, semi)
                val colonInPiece = piece.indexOf(':')
                if (colonInPiece != -1) {
                    val prop = piece.substring(0, colonInPiece).trim().lowercase(Locale.ROOT)
                    val rawVal = piece.substring(colonInPiece + 1)
                    val colonGlobal = searchStart + colonInPiece

                    // Compute indices of value
                    var valStartInPiece = colonInPiece + 1
                    while (valStartInPiece < piece.length && piece[valStartInPiece].isWhitespace()) {
                        valStartInPiece++
                    }
                    var valEndInPiece = piece.length
                    while (valEndInPiece > valStartInPiece && piece[valEndInPiece - 1].isWhitespace()) {
                        valEndInPiece--
                    }

                    if (valStartInPiece < valEndInPiece) {
                        var trimmedVal = piece.substring(valStartInPiece, valEndInPiece)
                        var isImportant = false
                        val bang = trimmedVal.lastIndexOf('!')
                        if (bang != -1 && trimmedVal.substring(bang + 1).trim().lowercase(Locale.ROOT) == "important") {
                            valEndInPiece = valStartInPiece + bang
                            while (valEndInPiece > valStartInPiece && piece[valEndInPiece - 1].isWhitespace()) {
                                valEndInPiece--
                            }
                            trimmedVal = piece.substring(valStartInPiece, valEndInPiece)
                            isImportant = true
                        }
                        val globalStart = searchStart + valStartInPiece
                        val globalEndExclusive = searchStart + valEndInPiece
                        decls.add(Declaration(prop, trimmedVal, globalStart until globalEndExclusive, isImportant))
                    }
                }
                searchStart = semi + 1
            }
            declarations = decls
        }

        fun effective(property: String): Declaration? {
            val matching = declarations.filter { it.property == property }
            return matching.lastOrNull { it.isImportant } ?: matching.lastOrNull()
        }

        fun declares(properties: Set<String>): Boolean {
            return declarations.any { it.property in properties && it.value.lowercase(Locale.ROOT) != "none" }
        }
    }

    // --- Color, OKLab & WCAG Math --------------------------------------------

    data class RGBA(
        val red: Double,
        val green: Double,
        val blue: Double,
        val alpha: Double = 1.0,
    ) {
        val css: String
            get() {
                val r = (red * 255.0).roundToInt().coerceIn(0, 255)
                val g = (green * 255.0).roundToInt().coerceIn(0, 255)
                val b = (blue * 255.0).roundToInt().coerceIn(0, 255)
                return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
            }

        fun over(below: RGBA): RGBA {
            fun mix(top: Double, bottom: Double): Double = top * alpha + bottom * (1.0 - alpha)
            return RGBA(
                red = mix(red, below.red),
                green = mix(green, below.green),
                blue = mix(blue, below.blue),
            )
        }
    }

    private class Fixes {
        private val known = mutableMapOf<Pair<RGBA, RGBA>, RGBA?>()

        fun readable(text: RGBA, background: RGBA): RGBA? {
            val pair = Pair(text, background)
            if (known.containsKey(pair)) return known[pair]
            val fix = MailReadableHtml.readable(
                text,
                background,
                searching = known.size < MAXIMUM_SEARCHES,
            )
            known[pair] = fix
            return fix
        }
    }

    private data class OKLab(
        val lightness: Double,
        val a: Double,
        val b: Double,
    ) {
        companion object {
            fun from(color: RGBA): OKLab {
                val red = linear(color.red)
                val green = linear(color.green)
                val blue = linear(color.blue)
                val l = cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
                val m = cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
                val s = cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
                val lStar = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
                val aStar = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
                val bStar = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
                return OKLab(lStar, aStar, bStar)
            }
        }

        val linearRGB: Triple<Double, Double, Double>
            get() {
                val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
                val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
                val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
                return Triple(
                    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
                )
            }

        val isInGamut: Boolean
            get() {
                val (r, g, b) = linearRGB
                return r >= -1e-9 && r <= 1.0 + 1e-9 &&
                    g >= -1e-9 && g <= 1.0 + 1e-9 &&
                    b >= -1e-9 && b <= 1.0 + 1e-9
            }
    }

    companion object {
        const val MINIMUM_CONTRAST = 7.0
        const val MAXIMUM_DEPTH = 256
        const val MAXIMUM_SEARCHES = 2_000

        private val COLORLESS_KEYWORDS = setOf("currentcolor", "inherit", "unset", "revert", "revert-layer")

        /** On `color`, keywords that draw the text in its parent's color. */
        private val INHERITING_KEYWORDS = setOf("inherit", "currentcolor", "unset")

        /**
         * On `color`, keywords that fall back to the browser's own color: the parent's, as the
         * browser sets none on text, but its link color on a link.
         */
        private val REVERTING_KEYWORDS = setOf("revert", "revert-layer")

        /** On `background-color`, keywords that leave no background: its initial value is transparent. */
        private val INITIAL_KEYWORDS = setOf("initial", "unset")

        private val CSS_NUMBER = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)(?:e[+-]?[0-9]+)?""")

        /**
         * The link and image targets the HTML parser reads off [html], in document order, as
         * `href=`/`src=` and the value. Read off the parsed tree rather than the markup, so text
         * that reads like an attribute is no target, and a page whose output the parser reads
         * back with every target as it was keeps its color fixes.
         */
        fun targets(html: String): List<String> = targets(Jsoup.parseBodyFragment(html))

        private fun targets(document: Document): List<String> = buildList {
            for (element in document.body().select("[href], [src]")) {
                if (element.hasAttr("href")) add("href=" + element.attr("href"))
                if (element.hasAttr("src")) add("src=" + element.attr("src"))
            }
        }

        private fun set(name: String, element: Element, value: String?) {
            val current = if (element.hasAttr(name)) element.attr(name) else null
            if (current == value) return
            if (value != null) element.attr(name, value) else element.removeAttr(name)
        }

        private fun keepsDisplay(style: Style, defaults: Set<String>): Boolean {
            val decl = style.effective("display") ?: return true
            return decl.value.lowercase(Locale.ROOT) in defaults
        }

        private fun legacyValue(name: String, element: Element): String? {
            val v = element.attr(name).trim()
            return if (v.isEmpty() || v.lowercase(Locale.ROOT) == "transparent") null else v
        }

        private fun setsColor(element: Element, style: Style): Boolean {
            val text = style.effective("color")?.let { shows(it.value) }
                ?: (element.normalName() == "font" && legacyValue("color", element) != null)
            val bg = style.effective("background-color")?.let { shows(it.value, onBackground = true) }
                ?: (legacyValue("bgcolor", element) != null)
            return text || bg
        }

        private fun shows(value: String, onBackground: Boolean = false): Boolean {
            val color = parse(value, legacy = false)
            if (color == null) {
                val kw = value.lowercase(Locale.ROOT)
                if (onBackground) {
                    return kw == "currentcolor" || (kw !in COLORLESS_KEYWORDS && kw != "initial")
                }
                return kw !in COLORLESS_KEYWORDS
            }
            return color.alpha > 0.0
        }

        private fun layer(element: Element, style: Style): Layer {
            if (style.declares(setOf("border-image", "border-image-source"))) return Layer.Unknown
            if (element.normalName() == "table" && paintsColumns(element)) return Layer.Unknown
            val decl = style.effective("background-color")
            if (decl != null) {
                if (decl.value.lowercase(Locale.ROOT) in INITIAL_KEYWORDS) return Layer.None
                return parse(decl.value, legacy = false)?.let { Layer.ColorLayer(it) } ?: Layer.Unknown
            }
            val bgAttr = legacyValue("bgcolor", element) ?: return Layer.None
            return parse(bgAttr, legacy = true)?.let { Layer.ColorLayer(it) } ?: Layer.Unknown
        }

        private fun paintsColumns(table: Element): Boolean {
            for (group in table.children()) {
                if (group.normalName() == "colgroup") {
                    val cols = listOf(group) + group.children()
                    for (col in cols) {
                        if (Style(col.attr("style")).effective("background-color") != null) return true
                    }
                }
            }
            return false
        }

        private fun node(
            element: Element,
            style: Style,
            layer: Layer,
            backdrop: Backdrop,
            parent: Int?,
        ): InternalNode? {
            val kind: NodeKind
            val sent = sentColor(element, style)
            if (sent != null) {
                kind = NodeKind.Own(sent)
            } else if (element.normalName() == "a" && element.hasAttr("href")) {
                kind = NodeKind.Link
            } else if (layer is Layer.ColorLayer && layer.color.alpha > 0.0 && !backdrop.isUnknown) {
                kind = NodeKind.Background
            } else if (element.normalName() == "caption" && !backdrop.isUnknown) {
                kind = NodeKind.Background
            } else {
                return null
            }
            return InternalNode(
                element = element,
                kind = kind,
                backdrop = backdrop,
                parent = parent,
                style = if (element.hasAttr("style")) style.text else null,
            )
        }

        private fun sentColor(element: Element, style: Style): Sent? {
            val decl = style.effective("color")
            if (decl != null) {
                val keyword = decl.value.lowercase(Locale.ROOT)
                val link = element.normalName() == "a" && element.hasAttr("href")
                return Sent(
                    color = parse(decl.value, legacy = false),
                    inherits = keyword in INHERITING_KEYWORDS || (keyword in REVERTING_KEYWORDS && !link),
                    attribute = "style",
                    value = style.text,
                    range = decl.valueRange,
                )
            }
            if (element.normalName() == "font") {
                val value = legacyValue("color", element)
                if (value != null) {
                    val rawAttr = element.attr("color")
                    val start = rawAttr.indexOf(value)
                    val range = if (start != -1) start until (start + value.length) else rawAttr.indices
                    return Sent(
                        color = parse(value, legacy = true),
                        attribute = "color",
                        value = rawAttr,
                        range = range,
                    )
                }
            }
            return null
        }

        // --- Color Parsing ---------------------------------------------------

        fun parse(value: String, legacy: Boolean): RGBA? {
            val v = value.trim().lowercase(Locale.ROOT)
            if (v == "transparent") return RGBA(0.0, 0.0, 0.0, 0.0)
            val named = NAMED_COLORS[v]
            if (named != null) return RGBA(named)
            if (v.startsWith("#")) return hex(v.substring(1), legacy)
            return if (legacy) null else function(v)
        }

        private fun hex(digits: String, legacy: Boolean): RGBA? {
            val validLens = if (legacy) setOf(3, 6) else setOf(3, 4, 6, 8)
            if (digits.length !in validLens) return null
            val values = digits.map { ch ->
                // ASCII only: Character.digit also reads fullwidth and other Unicode digits.
                val v = if (ch.code < 0x80) Character.digit(ch, 16) else -1
                if (v == -1) return null
                v.toDouble()
            }
            val channels = if (values.size <= 4) {
                values.map { it / 15.0 }
            } else {
                (0 until values.size step 2).map { i ->
                    (values[i] * 16.0 + values[i + 1]) / 255.0
                }
            }
            val alpha = if (channels.size > 3) channels[3] else 1.0
            return RGBA(channels[0], channels[1], channels[2], alpha)
        }

        private fun function(value: String): RGBA? {
            val open = value.indexOf('(')
            if (open == -1 || !value.endsWith(')')) return null
            // Not trimmed: `rgb (51, 51, 51)` is no color to CSS.
            val name = value.substring(0, open)
            if (name != "rgb" && name != "rgba") return null
            val args = value.substring(open + 1, value.length - 1).trim()
            val channels: MutableList<String>
            var alphaStr: String? = null

            if (args.contains(',')) {
                val pieces = args.split(',').map { it.trim() }
                if (pieces.size != 3 && pieces.size != 4) return null
                channels = pieces.toMutableList()
                if (channels.size == 4) alphaStr = channels.removeAt(3)
                val allPercent = channels.all { it.endsWith('%') }
                val nonePercent = channels.none { it.endsWith('%') }
                if (!allPercent && !nonePercent) return null
            } else {
                val halves = args.split('/')
                if (halves.size > 2) return null
                channels = halves[0].trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
                alphaStr = if (halves.size == 2) halves[1].trim() else null
            }

            if (channels.size != 3) return null
            val r = number(channels[0], 255.0) ?: return null
            val g = number(channels[1], 255.0) ?: return null
            val b = number(channels[2], 255.0) ?: return null
            var opacity = 1.0
            if (alphaStr != null) {
                opacity = number(alphaStr, 1.0) ?: return null
            }
            return RGBA(r, g, b, opacity)
        }

        private fun number(text: String, scale: Double): Double? {
            val isPercent = text.endsWith('%')
            val digits = if (isPercent) text.substring(0, text.length - 1) else text
            // CSS's own number syntax, not toDouble's, which also takes `0.` and `1.`: a value
            // a browser drops would be read here as a color the page never shows.
            if (!CSS_NUMBER.matches(digits)) return null
            val num = digits.toDoubleOrNull() ?: return null
            val normalized = num / (if (isPercent) 100.0 else scale)
            return normalized.coerceIn(0.0, 1.0)
        }

        // --- WCAG Contrast ----------------------------------------------------

        fun contrast(first: RGBA, second: RGBA): Double {
            val one = luminance(first)
            val other = luminance(second)
            return (max(one, other) + 0.05) / (min(one, other) + 0.05)
        }

        fun reads(text: RGBA, background: RGBA): Boolean {
            val white = contrast(RGBA(1.0, 1.0, 1.0), background)
            val black = contrast(RGBA(0.0, 0.0, 0.0), background)
            return contrast(text, background) >= min(MINIMUM_CONTRAST, max(white, black))
        }

        fun readable(text: RGBA, background: RGBA, searching: Boolean = true): RGBA? {
            if (reads(text, background)) return null
            val white = RGBA(1.0, 1.0, 1.0)
            val black = RGBA(0.0, 0.0, 0.0)
            val best = if (contrast(white, background) >= contrast(black, background)) white else black
            if (!searching || contrast(best, background) < MINIMUM_CONTRAST) return best
            return nearest(OKLab.from(text), if (best == white) 1.0 else 0.0, background) ?: best
        }

        private fun nearest(lab: OKLab, end: Double, background: RGBA): RGBA? {
            fun reaches(lightness: Double): Boolean =
                contrast(displayable(lab, lightness), background) >= MINIMUM_CONTRAST

            if (!reaches(end)) return null
            var fails = lab.lightness
            var passes = end
            repeat(12) {
                val middle = (fails + passes) / 2.0
                if (reaches(middle)) passes = middle else fails = middle
            }
            val roundUp = end > lab.lightness
            val color = displayable(lab, passes)
            fun byte(ch: Double): Double {
                val scaled = ch * 255.0
                val rounded = if (roundUp) kotlin.math.ceil(scaled) else kotlin.math.floor(scaled)
                return (rounded / 255.0).coerceIn(0.0, 1.0)
            }
            return RGBA(byte(color.red), byte(color.green), byte(color.blue))
        }

        private fun displayable(lab: OKLab, lightness: Double): RGBA {
            fun scaled(chroma: Double) = OKLab(lightness, lab.a * chroma, lab.b * chroma)
            var inside = 0.0
            var outside = 1.0
            if (scaled(1.0).isInGamut) {
                inside = 1.0
            } else {
                repeat(12) {
                    val middle = (inside + outside) / 2.0
                    if (scaled(middle).isInGamut) inside = middle else outside = middle
                }
            }
            val (r, g, b) = scaled(inside).linearRGB
            return RGBA(encoded(r.coerceIn(0.0, 1.0)), encoded(g.coerceIn(0.0, 1.0)), encoded(b.coerceIn(0.0, 1.0)))
        }

        private fun luminance(color: RGBA): Double =
            0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

        private fun linear(channel: Double): Double =
            if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

        private fun encoded(linear: Double): Double =
            if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1.0 / 2.4) - 0.055

        private fun RGBA(rgb: Long): RGBA {
            val r = ((rgb shr 16) and 0xFF).toDouble() / 255.0
            val g = ((rgb shr 8) and 0xFF).toDouble() / 255.0
            val b = (rgb and 0xFF).toDouble() / 255.0
            return RGBA(r, g, b, 1.0)
        }

        // Named CSS colors from W3C spec
        private val NAMED_COLORS: Map<String, Long> = mapOf(
            "aliceblue" to 0xF0F8FFL, "antiquewhite" to 0xFAEBD7L, "aqua" to 0x00FFFFL, "aquamarine" to 0x7FFFD4L, "azure" to 0xF0FFFFL,
            "beige" to 0xF5F5DCL, "bisque" to 0xFFE4C4L, "black" to 0x000000L, "blanchedalmond" to 0xFFEBCDL, "blue" to 0x0000FFL,
            "blueviolet" to 0x8A2BE2L, "brown" to 0xA52A2AL, "burlywood" to 0xDEB887L, "cadetblue" to 0x5F9EA0L, "chartreuse" to 0x7FFF00L,
            "chocolate" to 0xD2691EL, "coral" to 0xFF7F50L, "cornflowerblue" to 0x6495EDL, "cornsilk" to 0xFFF8DCL, "crimson" to 0xDC143CL,
            "cyan" to 0x00FFFFL, "darkblue" to 0x00008BL, "darkcyan" to 0x008B8BL, "darkgoldenrod" to 0xB8860BL, "darkgray" to 0xA9A9A9L,
            "darkgreen" to 0x006400L, "darkgrey" to 0xA9A9A9L, "darkkhaki" to 0xBDB76BL, "darkmagenta" to 0x8B008BL,
            "darkolivegreen" to 0x556B2FL, "darkorange" to 0xFF8C00L, "darkorchid" to 0x9932CCL, "darkred" to 0x8B0000L,
            "darksalmon" to 0xE9967AL, "darkseagreen" to 0x8FBC8FL, "darkslateblue" to 0x483D8BL, "darkslategray" to 0x2F4F4FL,
            "darkslategrey" to 0x2F4F4FL, "darkturquoise" to 0x00CED1L, "darkviolet" to 0x9400D3L, "deeppink" to 0xFF1493L,
            "deepskyblue" to 0x00BFFFL, "dimgray" to 0x696969L, "dimgrey" to 0x696969L, "dodgerblue" to 0x1E90FFL,
            "firebrick" to 0xB22222L, "floralwhite" to 0xFFFAF0L, "forestgreen" to 0x228B22L, "fuchsia" to 0xFF00FFL,
            "gainsboro" to 0xDCDCDCL, "ghostwhite" to 0xF8F8FFL, "gold" to 0xFFD700L, "goldenrod" to 0xDAA520L, "gray" to 0x808080L,
            "green" to 0x008000L, "greenyellow" to 0xADFF2FL, "grey" to 0x808080L, "honeydew" to 0xF0FFF0L, "hotpink" to 0xFF69B4L,
            "indianred" to 0xCD5C5CL, "indigo" to 0x4B0082L, "ivory" to 0xFFFFF0L, "khaki" to 0xF0E68CL, "lavender" to 0xE6E6FAL,
            "lavenderblush" to 0xFFF0F5L, "lawngreen" to 0x7CFC00L, "lemonchiffon" to 0xFFFACDL, "lightblue" to 0xADD8E6L,
            "lightcoral" to 0xF08080L, "lightcyan" to 0xE0FFFFL, "lightgoldenrodyellow" to 0xFAFAD2L, "lightgray" to 0xD3D3D3L,
            "lightgreen" to 0x90EE90L, "lightgrey" to 0xD3D3D3L, "lightpink" to 0xFFB6C1L, "lightsalmon" to 0xFFA07AL,
            "lightseagreen" to 0x20B2AAL, "lightskyblue" to 0x87CEFAL, "lightslategray" to 0x778899L, "lightslategrey" to 0x778899L,
            "lightsteelblue" to 0xB0C4DEL, "lightyellow" to 0xFFFFE0L, "lime" to 0x00FF00L, "limegreen" to 0x32CD32L,
            "linen" to 0xFAF0E6L, "magenta" to 0xFF00FFL, "maroon" to 0x800000L, "mediumaquamarine" to 0x66CDAAL,
            "mediumblue" to 0x0000CDL, "mediumorchid" to 0xBA55D3L, "mediumpurple" to 0x9370DBL, "mediumseagreen" to 0x3CB371L,
            "mediumslateblue" to 0x7B68EEL, "mediumspringgreen" to 0x00FA9AL, "mediumturquoise" to 0x48D1CCL,
            "mediumvioletred" to 0xC71585L, "midnightblue" to 0x191970L, "mintcream" to 0xF5FFFAL, "mistyrose" to 0xFFE4E1L,
            "moccasin" to 0xFFE4B5L, "navajowhite" to 0xFFDEADL, "navy" to 0x000080L, "oldlace" to 0xFDF5E6L, "olive" to 0x808000L,
            "olivedrab" to 0x6B8E23L, "orange" to 0xFFA500L, "orangered" to 0xFF4500L, "orchid" to 0xDA70D6L,
            "palegoldenrod" to 0xEEE8AAL, "palegreen" to 0x98FB98L, "paleturquoise" to 0xAFEEEEL, "palevioletred" to 0xDB7093L,
            "papayawhip" to 0xFFEFD5L, "peachpuff" to 0xFFDAB9L, "peru" to 0xCD853FL, "pink" to 0xFFC0CBL, "plum" to 0xDDA0DDL,
            "powderblue" to 0xB0E0E6L, "purple" to 0x800080L, "rebeccapurple" to 0x663399L, "red" to 0xFF0000L,
            "rosybrown" to 0xBC8F8FL, "royalblue" to 0x4169E1L, "saddlebrown" to 0x8B4513L, "salmon" to 0xFA8072L,
            "sandybrown" to 0xF4A460L, "seagreen" to 0x2E8B57L, "seashell" to 0xFFF5EEL, "sienna" to 0xA0522DL, "silver" to 0xC0C0C0L,
            "skyblue" to 0x87CEEBL, "slateblue" to 0x6A5ACDL, "slategray" to 0x708090L, "slategrey" to 0x708090L, "snow" to 0xFFFAFAL,
            "springgreen" to 0x00FF7FL, "steelblue" to 0x4682B4L, "tan" to 0xD2B48CL, "teal" to 0x008080L, "thistle" to 0xD8BFD8L,
            "tomato" to 0xFF6347L, "turquoise" to 0x40E0D0L, "violet" to 0xEE82EEL, "wheat" to 0xF5DEB3L, "white" to 0xFFFFFFL,
            "whitesmoke" to 0xF5F5F5L, "yellow" to 0xFFFF00L, "yellowgreen" to 0x9ACD32L,
        )
    }
}
