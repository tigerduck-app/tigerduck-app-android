package org.ntust.app.tigerduck.announcements

import com.google.gson.annotations.SerializedName

/**
 * Bulletin DTOs mirroring server/bulletins/schemas.py. Org/tag values are
 * carried as raw strings rather than Kotlin enums because the server adds
 * tags faster than this app ships; treating unknowns as "unclassified"
 * client-side keeps the list working through taxonomy churn.
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

data class SubscriptionRule(
    val id: Int? = null,
    val name: String? = null,
    val orgs: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val mode: String = "AND",
    val enabled: Boolean = true,
)

data class SubscriptionsResponse(
    @SerializedName("device_id") val deviceId: String,
    val rules: List<SubscriptionRule>,
)

internal data class SubscriptionsPutRequest(
    val rules: List<SubscriptionRule>,
)
