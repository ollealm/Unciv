package com.unciv.app.desktop.ruler

/** The small idioms of the chancery's prose, one copy each, so a line reads the same whichever part of the host wrote it */
object Prose {
    /** A year of the running game's calendar, [turnOffset] turns from now: "1250 BC", "AD 400". GameInfo.getYear follows the calendar across the
     *  ages' changes of pace, so a span read off it is right where a turn's length changes inside it (end years, 2026-11-05) */
    fun year(game: com.unciv.logic.GameInfo, turnOffset: Int = 0): String = game.getYear(turnOffset).let { if (it < 0) "${-it} BC" else "AD $it" }
    /** The year a turn of the game fell in: the log's stamps, "since AD 1200" */
    fun yearOfTurn(game: com.unciv.logic.GameInfo, turn: Int): String = year(game, turn - game.turns)
    /** How long from now [turns] on is, in the calendar's years: "640 years from now" */
    fun fromNow(game: com.unciv.logic.GameInfo, turns: Int): String = (game.getYear(turns) - game.getYear(0)).let { years ->
        when { years <= 0 -> "this year"; years == 1 -> "a year from now"; else -> "$years years from now" } }
    /** How long [turns] on takes, in the calendar's years, for a list too long to date every line: "60 years" */
    fun span(game: com.unciv.logic.GameInfo, turns: Int): String = (game.getYear(turns) - game.getYear(0)).let { years -> if (years <= 1) "a year" else "$years years" }
    /** A time to come as the briefing states every end, the year and how long from now: "AD 1320, 640 years from now". Said in the briefing, which is
     *  read when it is written; a log line, read later, names the year alone */
    fun dated(game: com.unciv.logic.GameInfo, turns: Int): String = "${year(game, turns)}, ${fromNow(game, turns)}"
    /** "A", "A and B", "A, B and C" */
    fun andList(parts: List<String>): String = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    /** The plural's "s", unless the count is one */
    fun plural(n: Int): String = if (n == 1) "" else "s"
    /** A unit's name in the plural: "Pikemen", "Infantry", "Cannons" */
    fun pluralOf(name: String): String = when {
        name.endsWith("man") -> name.dropLast(3) + "men"
        name.endsWith("s") || Regex("(Infantry|Artillery|Cavalry)$").containsMatchIn(name) -> name
        name.endsWith("y") && name.getOrNull(name.length - 2)?.let { it !in "aeiou" } == true -> name.dropLast(1) + "ies"
        else -> name + "s"
    }
    /** The engine's own words for a model to read: its brackets struck, nested ones too ("in all [non-[Puppeted]] cities"), its closing "!" dropped,
     *  and its city filters said plainly. The one translation every engine message passes through (a refusal, an officials' line) */
    fun engineWords(text: String): String {
        var words = text.removeSuffix("!")
        while (true) { val struck = words.replace(Regex("\\[([^\\[\\]]*)\\]"), "$1"); if (struck == words) break; words = struck }
        return words.replace("non-Puppeted", "non-puppet").replace(Regex("\\bPuppeted\\b"), "puppet")
    }
    /** How far one figure leads another, in the words every comparison uses: by a wide margin (3 times or more), clearly (1.5), by some way (1.15), narrowly */
    fun lead(ratio: Double): String = when { ratio >= 3 -> "by a wide margin"; ratio >= 1.5 -> "clearly"; ratio >= 1.15 -> "by some way"; else -> "narrowly" }
    fun ordinal(place: Int): String = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth").getOrNull(place - 1) ?: "${place}th"
    /** A part of a whole in words, never a figure: "a third", "half", "nearly all" */
    fun share(part: Int, whole: Int): String {
        val r = if (whole <= 0) 0.0 else part.toDouble() / whole
        return when { r >= 0.9 -> "nearly all"; r >= 0.7 -> "three quarters"; r >= 0.58 -> "two thirds"; r >= 0.42 -> "half"; r >= 0.3 -> "a third"
            r >= 0.22 -> "a quarter"; r >= 0.15 -> "a fifth"; else -> "a few" }
    }
    /** "a Pikeman", "an Archer", "an SS Booster": the article by the sound of the name's first letter - an initialism's by the letter's name */
    fun article(name: String): String {
        val initialism = Regex("^[A-Z]{2,}\\b").containsMatchIn(name)
        val vowelSound = if (initialism) name[0] in "AEFHILMNORSX" else name.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true
        return if (vowelSound) "an $name" else "a $name"
    }
}
