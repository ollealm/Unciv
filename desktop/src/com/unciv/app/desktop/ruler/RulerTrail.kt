package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.GameInfo
import java.util.TreeMap

/**
 *  Where every unit stood at the end of each recent turn, and where units fell and cities changed hands -
 *  so the map can show an age's marches instead of a still picture.
 */
class RulerTrail {
    class Stand(val x: Int, val y: Int, val civ: String, val military: Boolean, val name: String = "")
    /** kind: "fallen" (a military unit lost; civ is whose) or "taken" (a city changed hands; civ is the new owner) */
    class Mark(val turn: Int, val x: Int, val y: Int, val kind: String, val civ: String)

    /** turn -> unit id -> where it stood */
    val stands = TreeMap<Int, HashMap<Int, Stand>>()
    /** Units the battle hook reported killed or captured since the last record: only these are marked fallen - a disbanded or used-up unit is no loss in the field */
    val lostInBattle = HashSet<Int>()
    val marks = ArrayList<Mark>()
    /** "x,y" -> owner, as of the last recorded turn */
    private var cityOwners = HashMap<String, String>()
    /** turn -> civ -> tiles (by index in the tile list) that civ saw for the first time that turn - the growth of each known world */
    val seen = TreeMap<Int, HashMap<String, IntArray>>()
    /** turn -> civ -> peoples met that turn */
    val met = TreeMap<Int, HashMap<String, List<String>>>()
    /** What each civ had explored and whom it knew at the last record: in memory only, so a restart starts the reckoning afresh without a false flood of "new" */
    private val explored = HashMap<String, java.util.BitSet>()
    private val known = HashMap<String, Set<String>>()

    /** Call once a game is loaded and after every turn */
    fun record(game: GameInfo) {
        val now = HashMap<Int, Stand>()
        for (civ in game.civilizations) for (unit in civ.units.getCivUnits()) {
            val p = unit.getTile().position
            now[unit.id] = Stand(p.x, p.y, civ.civName, unit.isMilitary(), unit.name)
        }
        val owners = HashMap<String, String>()
        for (civ in game.civilizations) for (city in civ.cities) city.getCenterTile().position.let { owners["${it.x},${it.y}"] = civ.civName }

        val before = stands.lowerEntry(game.turns)?.takeIf { it.key == game.turns - 1 }?.value
        if (before != null) {
            for ((id, stand) in before) {
                if (id in now || !stand.military) continue
                // An upgrade retires the old unit and raises a new one on the same spot: not a loss
                if (id in lostInBattle) marks += Mark(game.turns, stand.x, stand.y, "fallen", stand.civ)
            }
            for ((where, owner) in owners) {
                val previous = cityOwners[where] ?: continue
                if (previous != owner) where.split(",").let { marks += Mark(game.turns, it[0].toInt(), it[1].toInt(), "taken", owner) }
            }
        }
        cityOwners = owners
        lostInBattle.clear()
        stands[game.turns] = now
        // The known world's growth, for the ruled civs whose ages are told
        val seenNow = HashMap<String, IntArray>(); val metNow = HashMap<String, List<String>>()
        for (civ in game.civilizations.filter { it.civID in com.unciv.logic.automation.RulerOverrides.ruledCivs }) {
            val bits = java.util.BitSet(game.tileMap.tileList.size)
            game.tileMap.tileList.forEachIndexed { index, tile -> if (tile.isExplored(civ)) bits.set(index) }
            explored[civ.civName]?.let { before -> val fresh = bits.clone() as java.util.BitSet; fresh.andNot(before); if (!fresh.isEmpty) seenNow[civ.civName] = fresh.stream().toArray() }
            explored[civ.civName] = bits
            val names = civ.getKnownCivs().filter { it.isMajorCiv() || it.isCityState }.map { it.civName }.toSet()
            known[civ.civName]?.let { before -> (names - before).takeIf { it.isNotEmpty() }?.let { metNow[civ.civName] = it.toList() } }
            known[civ.civName] = names
        }
        if (seenNow.isNotEmpty()) seen[game.turns] = seenNow
        if (metNow.isNotEmpty()) met[game.turns] = metNow
        while (stands.size > KEEP_TURNS) stands.pollFirstEntry()
        marks.removeAll { it.turn < stands.firstKey() }
        seen.headMap(stands.firstKey(), false).clear(); met.headMap(stands.firstKey(), false).clear()
    }

    /** One unit's path from [since] to now: a point per turn, standing still left out */
    fun path(id: Int, since: Int): List<Stand> {
        val path = ArrayList<Stand>()
        for (turn in stands.tailMap(since, true).values) {
            val stand = turn[id] ?: continue
            if (path.lastOrNull()?.let { it.x == stand.x && it.y == stand.y } != true) path += stand
        }
        return path
    }

    fun clear() { stands.clear(); marks.clear(); cityOwners.clear(); seen.clear(); met.clear(); explored.clear(); known.clear() }

    fun toMap() = mapOf(
        "stands" to stands.mapKeys { it.key.toString() }.mapValues { turn ->
            turn.value.mapKeys { it.key.toString() }.mapValues { listOf(it.value.x, it.value.y, it.value.civ, it.value.military) } },
        "marks" to marks.map { listOf(it.turn, it.x, it.y, it.kind, it.civ) },
        "cityOwners" to cityOwners,
        "seen" to seen.mapKeys { it.key.toString() }.mapValues { turn -> turn.value.mapValues { it.value.toList() } },
        "met" to met.mapKeys { it.key.toString() }
    )

    fun load(json: JsonValue?) {
        clear()
        json?.get("stands")?.forEach { turn ->
            stands[turn.name.toInt()] = HashMap(turn.associate { it.name.toInt() to Stand(it.getInt(0), it.getInt(1), it.getString(2), it.getBoolean(3), if (it.size > 4) it.getString(4) else "") })
        }
        json?.get("seen")?.forEach { turn -> seen[turn.name.toInt()] = HashMap(turn.associate { it.name to it.asIntArray() }) }
        json?.get("met")?.forEach { turn -> met[turn.name.toInt()] = HashMap(turn.associate { it.name to it.asStringArray().toList() }) }
        json?.get("marks")?.forEach { marks += Mark(it.getInt(0), it.getInt(1), it.getInt(2), it.getString(3), it.getString(4)) }
        json?.get("cityOwners")?.forEach { cityOwners[it.name] = it.asString() }
    }

    companion object { const val KEEP_TURNS = 40 }
}
