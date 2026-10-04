package com.unciv.app.desktop.ruler

/** The small idioms of the chancery's prose, one copy each, so a line reads the same whichever part of the host wrote it */
object Prose {
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
    /** "a Pikeman", "an Archer": the article by the sound of the name's first letter */
    fun article(name: String): String = if (name.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "an $name" else "a $name"
}
