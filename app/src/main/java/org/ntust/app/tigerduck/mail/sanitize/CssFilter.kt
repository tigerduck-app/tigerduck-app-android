package org.ntust.app.tigerduck.mail.sanitize

/** Filters one `style` attribute value to the spec A.2 property allowlist. */
object CssFilter {
    private val ALLOWED = setOf(
        "color", "background-color", "font", "font-family", "font-size", "font-style", "font-weight",
        "text-align", "text-decoration", "text-indent", "text-transform", "line-height",
        "letter-spacing", "word-spacing", "white-space", "direction", "vertical-align",
        "margin", "padding", "border", "border-radius", "border-collapse", "border-spacing",
        "width", "min-width", "max-width", "height", "min-height", "max-height", "display",
        "list-style", "list-style-type", "list-style-position", "table-layout", "float", "clear", "overflow",
    )
    private val ALLOWED_PREFIXES = listOf("margin-", "padding-", "border-")
    private val DISPLAY_VALUES = setOf("block", "inline", "inline-block", "table", "table-row", "table-cell", "list-item", "none")
    private val FORBIDDEN = listOf(
        "url(", "expression", "@import", "behavior", "-moz-binding", "javascript:", "\\", "/*",
        "image-set", "-webkit-image-set", "image(", "cross-fade", "element(",
    )
    private val PROPERTY_NAME = Regex("^[a-z-]+$")

    /** The filtered declarations joined with `; `, or null when nothing survives. */
    fun filter(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val kept = style.split(';').mapNotNull { declaration ->
            val colon = declaration.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val property = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1).trim()
            if (value.isEmpty() || !PROPERTY_NAME.matches(property)) return@mapNotNull null
            val allowed = property in ALLOWED ||
                ALLOWED_PREFIXES.any { property.startsWith(it) && property.length > it.length }
            if (!allowed) return@mapNotNull null
            val lower = value.lowercase()
            if (FORBIDDEN.any { it in lower }) return@mapNotNull null
            if (property == "display" && lower.removeSuffix("!important").trim() !in DISPLAY_VALUES) return@mapNotNull null
            "$property: $value"
        }
        return kept.joinToString("; ").ifEmpty { null }
    }
}
