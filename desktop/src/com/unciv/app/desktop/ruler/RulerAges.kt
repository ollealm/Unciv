package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.RulerOverrides
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.atan2

/**
 *  What a realm's people did in an age, reckoned from the trail: where each company marched and fell, what the scouts and ships found,
 *  whom the realm met. In the world's words, for the advisor's letter and the campaign map - the chancery's ledgers say what the
 *  officials decided; this says what happened out in the world.
 */
class RulerAges(private val trail: RulerTrail, private val yearText: (Int) -> String, private val stillFor: (MapUnit) -> Int) {

    private class Move(val name: String, val from: Tile, val to: Tile, val fell: Boolean)

    /** A company destroyed by arms three or more ages ahead of its own: one of the world's oddities, kept until the trail forgets its turn */
    private class Outclassed(val turn: Int, val loser: String, val unit: String, val era: String, val victor: String, val by: String, val byEra: String, val place: String)
    private val outclassed = ArrayList<Outclassed>()

    /** Called by the battle hook for every unit lost anywhere; [turn] is the turn it belongs to */
    fun noteLoss(loss: RulerOverrides.UnitLoss, turn: Int) {
        val enemy = loss.enemy ?: return
        if (loss.civ.isBarbarian || enemy.isBarbarian) return // modern arms against a barbarian band is routine, not a story
        // Soldiers destroyed by soldiers: a worker or a general taken is no contest of arms, and a civilian has no age of its own
        if (loss.captured || !loss.unit.isMilitary()) return
        val ruleset = loss.civ.gameInfo.ruleset
        fun eraOf(unitName: String) = ruleset.units[unitName]?.let { unit -> unit.requiredTech?.let { ruleset.technologies[it]?.era() } ?: ruleset.eras.keys.firstOrNull() }
        val era = eraOf(loss.unit.name) ?: return
        val by = loss.enemyUnit?.takeIf { ruleset.units[it]?.isMilitary == true } ?: return
        val byEra = eraOf(by) ?: return // a city's guns are not arms of an age
        if ((ruleset.eras[byEra]?.eraNumber ?: 0) - (ruleset.eras[era]?.eraNumber ?: 0) < 3) return
        val city = loss.where.getCity()?.takeIf { loss.where.isCityCenter() }
        val near = city ?: loss.civ.gameInfo.getCities().minByOrNull { it.getCenterTile().aerialDistanceTo(loss.where) }
        val place = when { city != null -> "at ${city.name}"; near != null -> "near ${near.name}"; else -> "far from any city" }
        outclassed += Outclassed(turn, loss.civ.civName, loss.unit.name, era, enemy.civName, by, byEra, place)
        trail.stands.firstEntry()?.key?.let { oldest -> outclassed.removeAll { it.turn < oldest } }
    }

    fun clear() = outclassed.clear()
    fun toMap() = mapOf("outclassed" to outclassed.map { listOf(it.turn, it.loser, it.unit, it.era, it.victor, it.by, it.byEra, it.place) })
    fun load(json: JsonValue?) {
        outclassed.clear()
        json?.get("outclassed")?.forEach { outclassed += Outclassed(it.getInt(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)) }
    }

    /** Candidates for the Chronicler's oddity of the age, in the world's words: the world's own (arms of another age, a city passed from hand to hand) and ours ("Our ...").
     *  Empty most ages; each kind is capped, the strangest first */
    private fun curiosities(civ: Civilization, from: Int, to: Int, first: Map<Int, RulerTrail.Stand>, last: Map<Int, RulerTrail.Stand>, enemies: Set<String>): List<String> {
        val game = civ.gameInfo
        val lines = ArrayList<String>()
        fun count(n: Int, name: String) = if (n == 1) (if (name.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "An $name" else "A $name") else "$n ${name}s"
        // (a) arms of an older age destroyed by a far newer one - the world's
        outclassed.filter { it.turn in from..to }.groupBy { listOf(it.loser, it.unit, it.victor, it.by, it.place) }.entries.take(3).forEach { (_, group) ->
            val o = group[0]
            lines += "${count(group.size, o.unit)} of ${o.loser} (${o.era}) fell to ${o.victor}'s ${o.by} (${o.byEra}) ${o.place}."
        }
        // (c) a city that changed hands twice or more within the age - the world's
        trail.marks.filter { it.turn in from..to && it.kind == "taken" }.groupBy { it.x to it.y }.filter { it.value.size >= 2 }.forEach { (xy, marks) ->
            val city = game.tileMap.getIfTileExistsOrNull(xy.first, xy.second)?.getCity() ?: return@forEach
            lines += "${city.name} changed hands ${marks.size} times in these years; ${city.civ.civName} holds it now."
        }
        // Ours are told in the age they come about, not in every age after: a unit that stays where it is makes no new story
        val towns = civ.cities.map { it.getCenterTile() }
        val tileOf = { stand: RulerTrail.Stand? -> stand?.let { game.tileMap.getIfTileExistsOrNull(it.x, it.y) } }
        // (b) one of ours that went far from every town of ours, or a civilian of ours that went into a foreign city
        if (towns.isNotEmpty()) {
            val here = civ.units.getCivUnits().filter { it.id in last && !it.isGreatPerson() }
            val farOf = { tile: Tile -> towns.minOf { town -> town.aerialDistanceTo(tile) } }
            here.filter { it.baseUnit.isLandUnit }.map { it to farOf(it.getTile()) }
                .filter { (unit, distance) -> distance >= 12 && tileOf(first[unit.id])?.let { farOf(it) < 12 } != false }
                .sortedByDescending { it.second }.take(2)
                .forEach { (unit, distance) -> lines += "Our ${unit.name} reached ${placeOf(civ, unit.getTile(), enemies)}, $distance tiles from any town of ours." }
            here.filter { it.isCivilian() && it.getTile().isCityCenter() && it.getTile().getOwner() != civ && tileOf(first[it.id]) != it.getTile() }.take(2)
                .forEach { unit -> lines += "Our ${unit.name} went into ${unit.getTile().getCity()!!.name}, a city of ${unit.getTile().getOwner()!!.civName}." }
        }
        // (d) a Great Person that has now stood still for forty turns, (e) a settler that has now waited thirty - told once, in the age the count was reached
        val ageLength = to - from
        for (unit in civ.units.getCivUnits()) {
            val still = stillFor(unit)
            if (unit.isGreatPerson() && still >= 40 && still - ageLength < 40) lines += "Our ${unit.name} has stood at ${placeOf(civ, unit.getTile(), enemies)} for $still turns."
            else if (unit.hasUnique(UniqueType.FoundCity) && still >= 30 && still - ageLength < 30) lines += "Our ${unit.name} has waited at ${placeOf(civ, unit.getTile(), enemies)} for $still turns."
        }
        return lines
    }

    fun summary(civ: Civilization, from: Int): Map<String, Any?> {
        val game = civ.gameInfo
        val to = game.turns
        val turns = trail.stands.subMap(from, true, to, true)
        val first = turns.firstEntry()?.value ?: emptyMap<Int, RulerTrail.Stand>()
        val last = turns.lastEntry()?.value ?: emptyMap<Int, RulerTrail.Stand>()
        val tile = { stand: RulerTrail.Stand -> game.tileMap.getIfTileExistsOrNull(stand.x, stand.y) }
        // At war now, or lately: a peace treaty still in force means the war was within the age
        val enemies = game.civilizations.filter { other -> other.isMajorCiv() && other != civ && (civ.isAtWarWith(other) ||
            civ.getDiplomacyManager(other)?.trades?.any { trade -> (trade.ourOffers + trade.theirOffers).any { it.name == com.unciv.Constants.peaceTreaty } } == true) }.map { it.civName }.toSet()

        // Every unit's first and last stand in the age; a fallen one ends where the mark says
        fun moves(owner: String, military: Boolean): List<Move> {
            val ids = turns.values.flatMap { stands -> stands.filter { it.value.civ == owner && it.value.military == military }.keys }.toSet()
            return ids.mapNotNull { id ->
                val start = turns.values.firstNotNullOfOrNull { it[id] } ?: return@mapNotNull null
                val end = turns.values.lastOrNull { id in it }?.get(id) ?: return@mapNotNull null
                val fell = id !in last && trail.marks.any { it.turn in from..to && it.civ == owner && it.x == end.x && it.y == end.y }
                val a = tile(start) ?: return@mapNotNull null; val b = tile(end) ?: return@mapNotNull null
                Move(end.name.ifEmpty { start.name }, a, b, fell)
            }
        }
        fun plural(n: Int, name: String) = if (n == 1) (if (name.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "an $name" else "a $name") else "$n ${name}s"
        fun company(moves: List<Move>) = moves.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { plural(it.value, it.key) }

        // Our host: companies that marched (three tiles or more), grouped by where they set out and where they ended
        val ours = moves(civ.civName, true)
        // A march is a company that went somewhere: two or more together, or one that went far or towards an enemy city
        val marched = ours.filter { it.from.aerialDistanceTo(it.to) >= 3 }
        val marches = marched.groupBy { placeOf(civ, it.from, enemies) to placeOf(civ, it.to, enemies) }.entries
            .filter { (route, group) -> group.size >= 2 || group[0].from.aerialDistanceTo(group[0].to) >= 8 || route.second.startsWith("the walls of") }
            .sortedByDescending { it.value.size }.take(6)
            .map { (route, group) -> mapOf("what" to company(group), "from" to route.first, "to" to route.second,
                "fromXY" to listOf(group[0].from.position.x, group[0].from.position.y), "toXY" to listOf(group[0].to.position.x, group[0].to.position.y)) }
        // Where ours fell
        val lost = ours.filter { it.fell }.groupBy { placeOf(civ, it.to, enemies) }.entries.sortedByDescending { it.value.size }
            .map { (place, group) -> mapOf("what" to company(group), "where" to place, "xy" to listOf(group[0].to.position.x, group[0].to.position.y)) }
        // Enemy companies we saw move, on ground we know
        val theirs = enemies.flatMap { enemy -> moves(enemy, true).filter { it.from.aerialDistanceTo(it.to) >= 3 && it.to.isExplored(civ) }.map { enemy to it } }
            .groupBy { (enemy, move) -> Triple(enemy, placeOf(civ, move.from, enemies), placeOf(civ, move.to, enemies)) }.entries
            .sortedByDescending { it.value.size }.take(6)
            .map { (key, group) -> mapOf("whose" to key.first, "what" to company(group.map { it.second }), "from" to key.second, "to" to key.third,
                "fromXY" to listOf(group[0].second.from.position.x, group[0].second.from.position.y), "toXY" to listOf(group[0].second.to.position.x, group[0].second.to.position.y)) }
        val enemyLost = enemies.flatMap { enemy -> moves(enemy, true).filter { it.fell && it.to.isExplored(civ) }.map { enemy to it } }
            .groupBy { (enemy, move) -> enemy to placeOf(civ, move.to, enemies) }.entries
            .map { (key, group) -> mapOf("whose" to key.first, "what" to company(group.map { it.second }), "where" to key.second) }
        // Cities that changed hands in the age
        val taken = trail.marks.filter { it.turn in from..to && it.kind == "taken" }.mapNotNull { mark ->
            game.tileMap.getIfTileExistsOrNull(mark.x, mark.y)?.getCity()?.let { city -> mapOf("city" to city.name, "by" to mark.civ, "year" to yearText(mark.turn)) } }

        // The known world's growth: what was seen for the first time, in which direction, how far, and what it held
        val fresh = trail.seen.subMap(from, false, to, true).values.flatMap { it[civ.civName]?.toList() ?: emptyList() }
            .mapNotNull { game.tileMap.tileList.getOrNull(it) }
        val capital = civ.getCapital()?.getCenterTile()
        val exploration = if (fresh.isEmpty()) null else {
            val land = fresh.count { it.isLand }
            val direction = capital?.let { compassOf(it, fresh.map { t -> HexMath.hex2WorldCoords(t.position) }.let { pts -> pts.reduce { a, b -> a.add(b) }.scl(1f / pts.size) }, HexMath.hex2WorldCoords(it.position)) }
            val farthest = capital?.let { c -> fresh.maxOf { it.aerialDistanceTo(c) } }
            val wonders = fresh.mapNotNull { it.naturalWonder }.distinct()
            val cities = fresh.filter { it.isCityCenter() }.mapNotNull { it.getCity() }.map { "${it.name} (${it.civ.civName})" }
            mapOf("tilesSeen" to fresh.size, "mostly" to (if (land * 2 >= fresh.size) "land" else "sea"), "direction" to direction, "farthest" to farthest,
                "naturalWonders" to wonders, "citiesFound" to cities)
        }
        val met = trail.met.subMap(from, false, to, true).values.flatMap { it[civ.civName] ?: emptyList() }.distinct()
        // Our farthest ranging units - the scouts and ships whose journeys the exploration came from
        val explorers = moves(civ.civName, true).filter { move -> civ.cities.none { it.getCenterTile().aerialDistanceTo(move.to) < 8 } && move.from.aerialDistanceTo(move.to) >= 8 }
            .sortedByDescending { it.from.aerialDistanceTo(it.to) }.take(3)
            .map { mapOf("what" to it.name, "reached" to placeOf(civ, it.to, enemies)) }

        // The same, told
        val lines = ArrayList<String>()
        for (m in marches) lines += "${m["what"]} marched from ${m["from"]} to ${m["to"]}."
        for (l in lost) lines += "We lost ${l["what"]} at ${l["where"]}."
        for (t in theirs) lines += "${t["whose"]}'s ${t["what"]} came from ${t["from"]} to ${t["to"]}."
        for (l in enemyLost) lines += "${l["whose"]} lost ${l["what"]} at ${l["where"]}."
        for (t in taken) lines += "${t["city"]} fell to ${t["by"]} in ${t["year"]}."
        if (exploration != null) lines += "The known world grew by ${exploration["tilesSeen"]} tiles, mostly ${exploration["mostly"]}${exploration["direction"]?.let { ", to the $it" } ?: ""}${exploration["farthest"]?.let { ", as far as $it tiles from the capital" } ?: ""}." +
            (exploration["naturalWonders"] as List<*>).takeIf { it.isNotEmpty() }?.let { " Found: ${it.joinToString()}." }.orEmpty() +
            (exploration["citiesFound"] as List<*>).takeIf { it.isNotEmpty() }?.let { " Cities first seen: ${it.joinToString()}." }.orEmpty()
        for (e in explorers) lines += "Our ${e["what"]} ranged as far as ${e["reached"]}."
        if (met.isNotEmpty()) lines += "We met ${met.joinToString()}."
        if (lines.isEmpty()) lines += "No company marched beyond its home fields, nothing new was seen, and no one new was met."

        return mapOf("from" to from, "to" to to, "fromYear" to yearText(from), "toYear" to yearText(to),
            "marches" to marches, "lost" to lost, "enemyMarches" to theirs, "enemyLost" to enemyLost, "citiesTaken" to taken,
            "exploration" to exploration, "explorers" to explorers, "met" to met, "lines" to lines,
            "curiosities" to curiosities(civ, from, to, first, last, enemies))
    }

    /** A place as a chronicle names it: a city, its fields, or a distance and direction from the nearest city we know */
    private fun placeOf(civ: Civilization, tile: Tile, enemies: Set<String>): String {
        val city = tile.getCity()
        if (city != null && tile.isCityCenter()) return if (city.civ == civ) city.name else "${city.name} (${city.civ.civName})"
        val near = civ.gameInfo.getCities().filter { it.getCenterTile().isExplored(civ) }.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return "far from any city"
        val distance = near.getCenterTile().aerialDistanceTo(tile)
        val whose = if (near.civ == civ) "" else " (${near.civ.civName})"
        return when {
            distance <= 1 && near.civ.civName in enemies -> "the walls of ${near.name}$whose"
            distance <= 2 -> "the fields of ${near.name}$whose"
            else -> "$distance tiles ${compassOf(near.getCenterTile(), HexMath.hex2WorldCoords(tile.position), HexMath.hex2WorldCoords(near.getCenterTile().position))} of ${near.name}$whose"
        }
    }

    private fun compassOf(origin: Tile, to: com.badlogic.gdx.math.Vector2, from: com.badlogic.gdx.math.Vector2): String {
        val angle = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()))
        return listOf("east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east")[(((angle + 360 + 22.5) % 360) / 45).toInt()]
    }
}
