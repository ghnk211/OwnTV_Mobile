package tv.own.owntv.core.settings

/** The two parts of the TV's top-right clock that can be coloured. */
enum class ClockPart { TIME, DATE }

/**
 * The clock's colours as stored: each blank (the shipped look: white time, grey date),
 * [ACCENT] (follows the accent colour) or a "#RRGGBB" hex code.
 */
data class ClockColors(val time: String = "", val date: String = "") {
    fun of(part: ClockPart): String = when (part) {
        ClockPart.TIME -> time
        ClockPart.DATE -> date
    }

    companion object {
        const val ACCENT = "accent"
    }
}
