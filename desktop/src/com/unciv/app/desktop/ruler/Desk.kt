package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.app.desktop.ruler.Prose.plural
import com.unciv.logic.civilization.Civilization

/** The turns anything laid before a court waits for the ruler's answer before it lapses: an offer of a deal or of peace, a friendship, a demand */
const val DESK_WAITS = 8

/**
 *  One kind of thing laid before a court for the ruler's answer, with the one clock every kind keeps. Only the turn's bookkeeping (announce) enters an
 *  item in the log and starts its clock - a briefing shows what waits and changes nothing - and after [DESK_WAITS] turns unanswered it lapses. What a
 *  lapse does is the kind's own and is said in its own words: an offer refused, a peace withdrawn, a friendship refused, a demand agreed (a lapse must
 *  never start a war). Keys are "us<them...", the clocks kept in host.json.
 */
class Desk {
    private val since = HashMap<String, Int>()

    operator fun contains(key: String) = key in since
    fun forget(key: String) { since.remove(key) }
    /** At the bookkeeping: the clocks of what has left [civ]'s desk stop */
    fun keepOnly(civ: Civilization, waiting: Collection<String>) { since.keys.removeAll { it.startsWith("${civ.civName}<") && it !in waiting } }

    /**
     *  One waiting item: what the briefing says of it, or null once it has lapsed. Before the bookkeeping has entered it, [fresh] (just arrived); at the
     *  bookkeeping, [arrive] enters it in the log; once its turns are up, [lapse] does what the kind does and says so. [told] says it with the turns left
     */
    fun item(key: String, turn: Int, announce: Boolean, fresh: () -> String, arrive: () -> Unit, lapse: () -> Unit, told: (left: Int) -> String): String? {
        val start = since[key] ?: if (!announce) return fresh() else { arrive(); since[key] = turn; turn }
        if (announce && turn - start >= DESK_WAITS) { since.remove(key); lapse(); return null }
        return told((DESK_WAITS - (turn - start)).coerceAtLeast(0))
    }

    fun clear() = since.clear()
    fun toMap(): Map<String, Int> = since
    /** [key] renames a key saved in an older wording */
    fun load(json: JsonValue?, key: (String) -> String = { it }) { json?.forEach { since[key(it.name)] = it.asInt() } }

    companion object {
        /** The clock in words: "waits 3 more turns[after]", or [atLast] on the last turn before it lapses */
        fun waits(left: Int, atLast: String, after: String = "") = if (left == 0) atLast else "waits $left more turn${plural(left)}$after"
    }
}
