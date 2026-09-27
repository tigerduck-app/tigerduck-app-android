package org.ntust.app.tigerduck.network.model

/**
 * The 6 fixed categories NTUST's own student portal (i.ntust.edu.tw/student)
 * groups its services under, keyed by the `id` of the `<div>` block each
 * category's links live in. Mirrors TAT's `sub_system_category.dart` mapping
 * so this app buckets the same live-scraped links the same way.
 */
enum class PortalCategory(val serviceId: String) {
    CURRICULUM("service-1"),
    PERSON_INFO("service-2"),
    CAMPUS_LIFE("service-3"),
    FINANCIAL_SUPPORT("service-4"),
    ACTIVITIES("service-5"),
    RESOURCES("service-6");

    companion object {
        fun fromServiceId(id: String): PortalCategory? = entries.firstOrNull { it.serviceId == id }
    }
}

/** One scraped `<a>` entry from the information-system portal page. */
data class PortalLink(
    val category: PortalCategory,
    val name: String,
    val url: String,
)
