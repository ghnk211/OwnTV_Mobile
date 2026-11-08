package tv.own.owntv.core.live

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName

/**
 * The lines one live channel is offered on.
 *
 * A playlist that lists "北京卫视" six times keeps six rows: the importer's stable keys name the
 * family ("北京卫视␁卫视频道", then "…␁#2" … "…␁#6"), because the unique index needs the rows apart
 * and each holds a URL of its own. The list shows the channel once with its lines under it, and the
 * player walks the lines when one of them stops — the channel the user asked for is still the
 * channel they get, only from the next URL the provider gave it.
 *
 * Grouped by the provider's own name for the channel — its tags and country prefix taken off, so
 * "北京卫视", "北京卫视 ᴴᴰ" and the same name filed under two groups are one channel — and by which
 * country's feed it is, so two feeds that only share a name ("ES| DAZN 1", "AR| DAZN 1") stay
 * apart, as in [ChannelVersionRules].
 */
object ChannelLines {

    /** One channel as a list draws it: the first of its lines, and every line it is offered on. */
    data class Group(val channel: ChannelEntity, val lines: List<ChannelEntity>) {
        val lineCount: Int get() = lines.size
    }

    /** The family a row belongs to: the name it is called by, and which country's feed it is. */
    fun keyOf(channel: ChannelEntity): String = keyOf(channel.matchName)

    /** [keyOf] from a name alone — the channel-numbering pass has no entity in hand. */
    fun keyOf(matchName: String): String {
        val parsed = ProviderTags.parse(matchName)
        return parsed.name.trim().lowercase() + "\u0001" + (parsed.country ?: "")
    }

    /** Whether two rows are lines of one channel. */
    fun same(a: ChannelEntity, b: ChannelEntity): Boolean = keyOf(a) == keyOf(b)

    /**
     * [rows] as a list draws them: one entry per channel, the first of its lines standing for the
     * family, in the order the rows arrived — the order the caller already sorted them by.
     */
    fun group(rows: List<ChannelEntity>): List<Group> {
        if (rows.size < 2) return rows.map { Group(it, listOf(it)) }
        val byKey = LinkedHashMap<String, MutableList<ChannelEntity>>()
        rows.forEach { row -> byKey.getOrPut(keyOf(row)) { mutableListOf() }.add(row) }
        return byKey.values.map { lines -> Group(lines.first(), lines) }
    }

    /**
     * The lines of [channel]'s family among [siblings], in the provider's own order — the order the
     * playlist lists them, which is what "the next line" means. [channel] itself is always in the
     * answer, whatever list it came from.
     */
    fun linesOf(channel: ChannelEntity, siblings: List<ChannelEntity>): List<ChannelEntity> {
        val key = keyOf(channel)
        val lines = siblings.filter { keyOf(it) == key }.sortedBy { it.sortOrder }
        return if (lines.isEmpty()) listOf(channel) else lines
    }
}
