package org.ntust.app.tigerduck.network.model

import com.google.gson.annotations.SerializedName

/**
 * Bulletin DTOs mirroring server/bulletins/schemas.py. Org/tag values are
 * carried as raw strings rather than Kotlin enums because the server adds
 * tags faster than this app ships; treating unknowns as "unclassified"
 * client-side keeps the list working through taxonomy churn.
 *
 * UPGRADE-SAFETY (see CLAUDE.md / upgrade-safe-persistence skill):
 * [BulletinSummary] and [BulletinDetail] are persisted to disk by
 * [BulletinCache] and re-read after app upgrades. Gson instantiates them via
 * Unsafe.allocateInstance, which BYPASSES the constructor — a Kotlin default
 * like `= emptyList()` is NOT applied when the key is missing from an
 * old cache file; the field is silently null. The existing non-null fields
 * (id, externalId, title, contentTags) predate the cache, so every cached
 * file contains them — but any NEW field added here must be nullable, a
 * primitive, or accompanied by a cache migration. Do not add a non-null
 * reference-type field with a default value.
 */
data class BulletinSummary(
    val id: Int,
    @SerializedName("external_id") val externalId: String,
    val title: String,
    @SerializedName("title_clean") val titleClean: String?,
    @SerializedName("canonical_org") val canonicalOrg: String?,
    @SerializedName("content_tags") val contentTags: List<String> = emptyList(),
    val importance: String?,
    val summary: String?,
    @SerializedName("source_url") val sourceUrl: String?,
    @SerializedName("posted_at") val postedAt: String?,
    @SerializedName("is_deleted") val isDeleted: Boolean = false,
) {
    val displayTitle: String
        get() = titleClean?.takeIf { it.isNotBlank() } ?: title
}

data class BulletinDetail(
    val id: Int,
    @SerializedName("external_id") val externalId: String,
    val title: String,
    @SerializedName("title_clean") val titleClean: String?,
    @SerializedName("canonical_org") val canonicalOrg: String?,
    @SerializedName("content_tags") val contentTags: List<String> = emptyList(),
    val importance: String?,
    val summary: String?,
    @SerializedName("source_url") val sourceUrl: String?,
    @SerializedName("posted_at") val postedAt: String?,
    @SerializedName("is_deleted") val isDeleted: Boolean = false,
    @SerializedName("body_clean") val bodyClean: String?,
    @SerializedName("body_md") val bodyMd: String?,
    @SerializedName("raw_publisher") val rawPublisher: String?,
) {
    val displayTitle: String
        get() = titleClean?.takeIf { it.isNotBlank() } ?: title

    fun toSummary(): BulletinSummary = BulletinSummary(
        id = id,
        externalId = externalId,
        title = title,
        titleClean = titleClean,
        canonicalOrg = canonicalOrg,
        contentTags = contentTags,
        importance = importance,
        summary = summary,
        sourceUrl = sourceUrl,
        postedAt = postedAt,
        isDeleted = isDeleted,
    )
}

data class BulletinListResponse(
    val items: List<BulletinSummary>,
    @SerializedName("next_cursor") val nextCursor: Int?,
)

data class OrgLabel(val id: String, val label: String)
data class TagLabel(val id: String, val label: String)

data class TaxonomyResponse(
    val orgs: List<OrgLabel>,
    val tags: List<TagLabel>,
    @SerializedName("default_tags") val defaultTags: List<String>,
)

internal fun TaxonomyResponse.orgLabel(id: String): String =
    orgs.firstOrNull { it.id == id }?.label ?: id

internal fun TaxonomyResponse.tagLabel(id: String): String =
    tags.firstOrNull { it.id == id }?.label ?: id

/// Same as [tagLabel] but overrides the special `server_notification` tag
/// with the locale-aware string instead of whatever the server shipped
/// (every other tag is zh-only per the backend taxonomy contract).
internal fun TaxonomyResponse?.localizedTagLabel(
    id: String,
    context: android.content.Context,
): String {
    if (id == "server_notification") {
        return context.getString(org.ntust.app.tigerduck.R.string.tag_server_notification)
    }
    return this?.tagLabel(id) ?: id
}

/**
 * One bulletin subscription rule of this device, as the backend answers it. Read from the network
 * only: no file or preference keeps one, so no older build has stored one.
 */
data class SubscriptionRule(
    val id: Int? = null,
    val name: String? = null,
    val orgs: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val mode: String = "AND",
    val enabled: Boolean = true,
    /**
     * `/v4`: the id this device made for the rule, a UUID, by which the list's PUT keeps it
     * (`docs/api-v4.md`, "Subscriptions"). Null on `/v3`, which answers none, and on a rule the
     * page has not given one yet. Nullable, as Gson leaves a missing key null whatever its type.
     */
    @SerializedName("client_id") val clientId: String? = null,
)

// GET /bulletin-subscriptions returns a flat list of rules; on v4, the PUT answers one too.
data class SubscriptionsListResponse(
    val items: List<SubscriptionRule>,
)

// v4: PUT /bulletin-subscriptions, this device's whole list. A rule whose client_id the backend
// holds is updated in place, a new one inserted, and the rest deleted; a duplicate is 422.
internal data class SubscriptionListRequest(
    val rules: List<SubscriptionListRule>,
)

internal data class SubscriptionListRule(
    @SerializedName("client_id") val clientId: String,
    val name: String?,
    val orgs: List<String>,
    val tags: List<String>,
    val mode: String,
    val enabled: Boolean,
)

// v3: POST/PATCH /bulletin-subscriptions — request body for a single rule.
internal data class SubscriptionRuleRequest(
    val name: String? = null,
    val orgs: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val mode: String = "AND",
    val enabled: Boolean = true,
)
