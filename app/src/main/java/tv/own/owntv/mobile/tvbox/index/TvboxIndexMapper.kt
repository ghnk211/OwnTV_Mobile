package tv.own.owntv.mobile.tvbox.index

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.tvbox.category.siteMarkedAdult
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.util.TextUtil

/**
 * Pure TVbox → Core catalogue mapping. No coroutines, no DAO, no Android: every rule here is
 * exercised by a JVM test.
 *
 * Identity is `siteKey/vodId` in [MovieEntity.remoteId] — the site is part of the key because
 * `vodId` is only unique inside one site. A borrowed row (home fallback put another site's item in
 * this site's list) must use the donor's key; otherwise detail/playback address the wrong site and
 * every request 404s.
 */
object TvboxIndexMapper {

    /** The handle an indexed row's `streamUrl` carries — never a playable address on its own. */
    const val STREAM_PREFIX = "tvbox://"

    /**
     * The `remoteId` of a category row: `siteKey/typeId`.
     *
     * One function for the writer and the indexer's lookups — a second spelling of this key would
     * silently stop resolving the category a film is filed under.
     */
    fun categoryRemoteId(siteKey: String, typeId: String): String = "$siteKey/$typeId"

    /** Null when the site cannot encode a stable identity: a site key containing `/` would split wrong. */
    fun category(
        sourceId: Long,
        site: SiteConfig,
        info: ClassInfo,
        sortOrder: Int,
    ): CategoryEntity? {
        if (!validSiteKey(site.key)) return null
        val typeId = info.typeId.takeIf { it.isNotBlank() } ?: return null
        val extJson = buildJsonObject {
            put("siteKey", site.key)
            put("typeName", info.typeName)
        }
        return CategoryEntity(
            sourceId = sourceId,
            mediaType = MediaType.MOVIE,
            // The site prefix the rail needs is added by the P1 UI, not stored here: a stored
            // "{site} · {category}" would be user-visible text assembled in a pure function.
            name = info.typeName,
            remoteId = categoryRemoteId(site.key, typeId),
            sortOrder = sortOrder,
            extJson = extJson.toString(),
        )
    }

    /**
     * Null when the row cannot be indexed: no `vodId`, or a borrowed row whose donor site is gone.
     * Falling back to the current site would index it under an identity that cannot be played.
     */
    fun movie(
        movie: Movie,
        sourceId: Long,
        categoryId: Long?,
        site: SiteConfig,
        donor: SiteConfig?,
        now: Long,
        categoryTypeName: String? = null,
        lineCount: Int? = null,
        episodeCount: Int? = null,
    ): MovieEntity? {
        if (movie.donorSiteKey != null && donor == null) return null
        val origin = donor ?: site
        if (!validSiteKey(origin.key)) return null
        val vodId = movie.id.takeIf { it.isNotBlank() } ?: return null
        val key = "${origin.key}/$vodId"
        val seenType = movie.typeName?.takeIf { it.isNotBlank() }
            ?: categoryTypeName?.takeIf { it.isNotBlank() }
        val extJson = buildJsonObject {
            put(TYPE_NAMES, JsonArray(listOfNotNull(seenType).map(::JsonPrimitive)))
            put("remark", movie.remark.orEmpty())
            movie.donorSiteKey?.takeIf { it.isNotBlank() }?.let { put("donorSiteKey", it) }
            if (siteMarkedAdult(origin)) put("adultSite", true)
            lineCount?.let { put("lineCount", it) }
            episodeCount?.let { put("episodeCount", it) }
        }
        return MovieEntity(
            sourceId = sourceId,
            categoryId = categoryId,
            name = movie.name,
            posterUrl = TextUtil.absoluteUrl(movie.pic, origin.api),
            year = movie.year?.toIntOrNull(),
            plot = movie.content,
            streamUrl = "$STREAM_PREFIX$key",
            remoteId = key,
            extGroup = origin.key,
            extJson = extJson.toString(),
            lastSeenAt = now,
        )
    }

    /**
     * Union of the type names the row has been seen under, never a replacement.
     *
     * Replacement would let a later benign category ("电影") wash out the adult one the row was
     * first indexed from ("伦理片"), defeating the kids item-level check. A union that grows changes
     * the content hash, which is why the writer runs this before comparing.
     */
    fun mergeExtJson(old: String?, new: String?): String? {
        val oldObject = parseObject(old) ?: return new
        val newObject = parseObject(new) ?: return old
        val names = LinkedHashSet<String>()
        oldObject.typeNames().forEach(names::add)
        newObject.typeNames().forEach(names::add)
        val merged = buildJsonObject {
            oldObject.forEach { (key, value) -> if (key != TYPE_NAMES) put(key, value) }
            newObject.forEach { (key, value) -> if (key != TYPE_NAMES) put(key, value) }
            if (names.isNotEmpty()) {
                put(TYPE_NAMES, JsonArray(names.map(::JsonPrimitive)))
            }
        }
        return merged.toString()
    }

    /** `siteKey/vodId`, split on the FIRST slash only — a `vodId` may contain one. */
    fun splitRemoteId(remoteId: String?): Pair<String, String>? {
        val value = remoteId ?: return null
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.lastIndex) return null
        return value.substring(0, slash) to value.substring(slash + 1)
    }

    /** The site a Core row must be addressed to: the donor when the item was borrowed. */
    fun originSiteKey(remoteId: String?, extJson: String?): String? {
        val encoded = splitRemoteId(remoteId)?.first ?: return null
        val donor = extJson?.let(::parseObject)?.get("donorSiteKey").asString()
        return donor?.takeIf { it.isNotBlank() } ?: encoded
    }

    private const val TYPE_NAMES = "typeNames"

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseObject(value: String?): JsonObject? =
        if (value.isNullOrBlank()) null else runCatching { json.parseToJsonElement(value) as? JsonObject }.getOrNull()

    private fun JsonObject.typeNames(): List<String> = (this[TYPE_NAMES] as? JsonArray).orEmpty()
        .mapNotNull { it.asString()?.takeIf(String::isNotBlank) }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun validSiteKey(key: String): Boolean = key.isNotBlank() && !key.contains('/')
}