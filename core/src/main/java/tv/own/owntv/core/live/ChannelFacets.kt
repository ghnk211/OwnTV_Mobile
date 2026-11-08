package tv.own.owntv.core.live

import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.epg.EpgMatcher

/**
 * What channel-version matching reads off a channel's name: its portable name (provider tags
 * removed, [portableKey] for comparing), country prefix ("DE|"), language and picture quality
 * (ProviderCatalogMetadata ranks: 0 unknown, 1 SD, 2 HD, 3 FHD, 4 UHD, 5 8K). Unknown is null / 0,
 * never a guess.
 */
data class ChannelFacets(val name: String, val country: String?, val language: String?, val qualityRank: Int) {
    val portableKey: String get() = EpgMatcher.normalizeForEpg(name)

    companion object {
        // A provider's country prefix says which language the channel speaks when its name does not.
        // "AR|" is Arabic in IPTV playlists, not Argentina; "UK|" is the United Kingdom, not Ukrainian.
        private val COUNTRY_LANGUAGE = mapOf(
            "DE" to "DE", "AT" to "DE", "CH" to "DE",
            "UK" to "EN", "GB" to "EN", "US" to "EN", "USA" to "EN", "IE" to "EN", "CA" to "EN", "AU" to "EN", "NZ" to "EN", "IN" to "EN",
            "FR" to "FR", "BE" to "FR", "ES" to "ES", "MX" to "ES", "LAT" to "ES", "IT" to "IT", "TR" to "TR",
            "AR" to "AR", "ARA" to "AR", "SA" to "AR", "AE" to "AR", "QA" to "AR", "EG" to "AR", "MA" to "AR",
            "PT" to "PT", "BR" to "PT", "NL" to "NL", "PL" to "PL", "RU" to "RU", "GR" to "EL", "RO" to "RO",
            "SE" to "SV", "NO" to "NO", "DK" to "DA", "FI" to "FI", "HU" to "HU", "CZ" to "CS", "PK" to "UR",
        )

        fun of(channelName: String): ChannelFacets {
            val parsed = ProviderTags.parse(channelName)
            val meta = ProviderCatalogMetadataParser.parse(parsed.name, null)
            val tagRank = if (parsed.tags.isEmpty()) 0
            else ProviderCatalogMetadataParser.parse(parsed.tags.joinToString(" "), null).qualityRank
            val country = parsed.country
            return ChannelFacets(
                name = parsed.name,
                country = country,
                language = country?.let { COUNTRY_LANGUAGE[it] } ?: meta.language?.takeIf { it != "UK" },
                qualityRank = maxOf(meta.qualityRank, tagRank),
            )
        }
    }
}
