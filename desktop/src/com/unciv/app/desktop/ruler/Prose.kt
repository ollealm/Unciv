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
    /** "a Pikeman", "an Archer": the article by the sound of the name's first letter */
    fun article(name: String): String = if (name.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "an $name" else "a $name"
}
