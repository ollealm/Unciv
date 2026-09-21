package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.GameInfo
import java.util.TreeMap

/**
 *  Where every unit stood at the end of each recent turn, and where units fell and cities changed hands -
 *  so the map can show an age's marches instead of a still picture.
 */
class RulerTrail {
    class Stand(val x: Int, val y: Int, val civ: String, val military: Boolean)
    /** kind: "fallen" (a military unit lost; civ is whose) or "taken" (a city changed hands; civ is the new owner) */
    class Mark(val turn: Int, val x: Int, val y: Int, val kind: String, val civ: String)

    /** turn -> unit id -> where it stood */
    val stands = TreeMap<Int, HashMap<Int, Stand>>()
    val marks = ArrayList<Mark>()
    /** "x,y" -> owner, as of the last recorded turn */
    private var cityOwners = HashMap<String, String>()

    /** Call once a game is loaded and after every turn */
    fun record(game: GameInfo) {
        val now = HashMap<Int, Stand>()
        for (civ in game.civilizations) for (unit in civ.units.getCivUnits()) {
            val p = unit.getTile().position
            now[unit.id] = Stand(p.x, p.y, civ.civName, unit.isMilitary())
        }
        val owners = HashMap<String, String>()
        for (civ in game.civilizations) for (city in civ.cities) city.getCenterTile().position.let { owners["${it.x},${it.y}"] = civ.civName }

        val before = stands.lowerEntry(game.turns)?.takeIf { it.key == game.turns - 1 }?.value
        if (before != null) {
            for ((id, stand) in before) {
                if (id in now || !stand.military) continue
                // An upgrade retires the old unit and raises a new one on the same spot: not a loss
                val replaced = now.any { (otherId, other) -> otherId !in before && other.civ == stand.civ && other.x == stand.x && other.y == stand.y }
                if (!replaced) marks += Mark(game.turns, stand.x, stand.y, "fallen", stand.civ)
            }
            for ((where, owner) in owners) {
                val previous = cityOwners[where] ?: continue
                if (previous != owner) where.split(",").let { marks += Mark(game.turns, it[0].toInt(), it[1].toInt(), "taken", owner) }
            }
        }
        cityOwners = owners
        stands[game.turns] = now
        while (stands.size > KEEP_TURNS) stands.pollFirstEntry()
        marks.removeAll { it.turn < stands.firstKey() }
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

    fun clear() { stands.clear(); marks.clear(); cityOwners.clear() }

    fun toMap() = mapOf(
        "stands" to stands.mapKeys { it.key.toString() }.mapValues { turn ->
            turn.value.mapKeys { it.key.toString() }.mapValues { listOf(it.value.x, it.value.y, it.value.civ, it.value.military) } },
        "marks" to marks.map { listOf(it.turn, it.x, it.y, it.kind, it.civ) },
        "cityOwners" to cityOwners
    )

    fun load(json: JsonValue?) {
        clear()
        json?.get("stands")?.forEach { turn ->
            stands[turn.name.toInt()] = HashMap(turn.associate { it.name.toInt() to Stand(it.getInt(0), it.getInt(1), it.getString(2), it.getBoolean(3)) })
        }
        json?.get("marks")?.forEach { marks += Mark(it.getInt(0), it.getInt(1), it.getInt(2), it.getString(3), it.getString(4)) }
        json?.get("cityOwners")?.forEach { cityOwners[it.name] = it.asString() }
    }

    companion object { const val KEEP_TURNS = 40 }
}
