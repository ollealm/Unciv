package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.RulerOverrides
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.app.desktop.ruler.Prose.andList
import com.unciv.app.desktop.ruler.Prose.article
import com.unciv.app.desktop.ruler.Prose.pluralOf

/**
 *  What a realm's people did in an age, reckoned from the trail: where each company marched and fell, what the scouts and ships found,
 *  whom the realm met. In the world's words, for the advisor's letter and the campaign map - the chancery's ledgers say what the
 *  officials decided; this says what happened out in the world.
 */
class RulerAges(private val trail: RulerTrail, private val yearText: (Int) -> String, private val stillFor: (MapUnit) -> Int) {

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
        // The place as the fallen side knew it, in the words every other place of the chronicle is told in
        val place = RulerCampaigns.atPlaceOf(loss.civ, loss.where)
        outclassed += Outclassed(turn, loss.civ.civName, loss.unit.name, era, enemy.civName, by, byEra, place)
        trail.stands.firstEntry()?.key?.let { oldest -> outclassed.removeAll { it.turn < oldest } }
    }

    /** Every detonation in the world's words, by the turn it belongs to: what every realm saw in its age */
    private val nukes = ArrayList<Pair<Int, String>>()
    fun noteNuke(turn: Int, text: String) { nukes += turn to text }

    fun clear() { outclassed.clear(); nukes.clear() }
    fun toMap() = mapOf("outclassed" to outclassed.map { listOf(it.turn, it.loser, it.unit, it.era, it.victor, it.by, it.byEra, it.place) },
        "nukes" to nukes.map { listOf(it.first, it.second) })
    fun load(json: JsonValue?) {
        outclassed.clear(); nukes.clear()
        json?.get("nukes")?.forEach { nukes += it.getInt(0) to it.getString(1) }
        json?.get("outclassed")?.forEach { outclassed += Outclassed(it.getInt(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)) }
    }

    /** Candidates for the Chronicler's oddity of the age, in the world's words: the world's own (arms of another age, a city passed from hand to hand) and ours ("Our ...").
     *  Empty most ages; each kind is capped, the strangest first */
    private fun curiosities(civ: Civilization, from: Int, to: Int, first: Map<Int, RulerTrail.Stand>, last: Map<Int, RulerTrail.Stand>, enemies: Set<String>): List<String> {
        val game = civ.gameInfo
        val lines = ArrayList<String>()
        fun count(n: Int, name: String) = if (n == 1) article(name).replaceFirstChar { it.uppercase() } else "$n ${pluralOf(name)}"
        // (a) arms of an older age destroyed by a far newer one - the world's
        outclassed.filter { it.turn in from..to }.groupBy { listOf(it.loser, it.unit, it.victor, it.by, it.place) }.entries.take(3).forEach { (_, group) ->
            val o = group[0]
            lines += "${count(group.size, o.unit)} of ${RulerCampaigns.people(o.loser)}, arms of the ${o.era}, fell to ${RulerCampaigns.possessive(o.victor)} ${o.by}, arms of the ${o.byEra}, ${o.place}."
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
                .forEach { (unit, _) -> lines += "Our ${unit.name} reached ${RulerCampaigns.placeOf(civ, unit.getTile(), enemies)}, far from any town of ours." }
            here.filter { it.isCivilian() && it.getTile().isCityCenter() && it.getTile().getOwner() != civ && tileOf(first[it.id]) != it.getTile() }.take(2)
                .forEach { unit -> lines += "Our ${unit.name} went into ${unit.getTile().getCity()!!.name}, a city of ${unit.getTile().getOwner()!!.civName}." }
        }
        // (d) a Great Person that has now stood still for forty turns, (e) a settler that has now waited thirty - told once, in the age the count was reached
        val ageLength = to - from
        for (unit in civ.units.getCivUnits()) {
            val still = stillFor(unit)
            if (unit.isGreatPerson() && still >= 40 && still - ageLength < 40) lines += "Our ${unit.name} has stood ${RulerCampaigns.atPlace(RulerCampaigns.placeOf(civ, unit.getTile(), enemies))} since ${yearText(to - still)}."
            else if (unit.hasUnique(UniqueType.FoundCity) && still >= 30 && still - ageLength < 30) lines += "Our ${unit.name} has waited ${RulerCampaigns.atPlace(RulerCampaigns.placeOf(civ, unit.getTile(), enemies))} since ${yearText(to - still)}."
        }
        return lines
    }

    fun summary(civ: Civilization, from: Int): Map<String, Any?> {
        val game = civ.gameInfo
        val to = game.turns
        val turns = trail.stands.subMap(from, true, to, true)
        val first = turns.firstEntry()?.value ?: emptyMap<Int, RulerTrail.Stand>()
        val last = turns.lastEntry()?.value ?: emptyMap<Int, RulerTrail.Stand>()
        val enemies = RulerCampaigns.enemiesOf(civ)

        // The age's campaigns, as an atlas would draw them: a few forces, each with where it set out, where it was making for and how it ended
        val campaigns = RulerCampaigns.reckon(trail, civ, from, to)
        fun entry(c: RulerCampaigns.Campaign) = mapOf("whose" to c.whose, "what" to RulerCampaigns.company(c.members.map { it.name }),
            "from" to RulerCampaigns.placeOf(civ, c.route.first(), enemies, c.whose), "to" to RulerCampaigns.placeOf(civ, c.route.last(), enemies, c.whose),
            "outcome" to c.outcome.name.lowercase(), "size" to c.size, "fell" to c.fell, "sea" to c.sea,
            "told" to RulerCampaigns.told(c, civ, enemies),
            "fromXY" to c.route.first().position.let { listOf(it.x, it.y) }, "toXY" to c.route.last().position.let { listOf(it.x, it.y) },
            "route" to c.route.map { listOf(it.position.x, it.position.y) })
        val marches = campaigns.filter { it.ours }.map(::entry)
        val theirs = campaigns.filter { !it.ours }.map(::entry)
        // Losses the campaigns do not already tell: a garrison overrun, a picket caught alone
        val inCampaigns = campaigns.flatMap { c -> c.members.map { c.whose to it.id } }.toSet()
        val ours = RulerCampaigns.members(trail, game, civ.civName, from, to)
        val lost = ours.filter { it.fell && (civ.civName to it.id) !in inCampaigns }.groupBy { RulerCampaigns.placeOf(civ, it.end, enemies) }.entries.sortedByDescending { it.value.size }
            .map { (place, group) -> mapOf("what" to RulerCampaigns.company(group.map { it.name }), "where" to place, "at" to RulerCampaigns.atPlace(place), "xy" to group[0].end.position.let { listOf(it.x, it.y) }) }
        val enemyLost = enemies.flatMap { enemy -> RulerCampaigns.members(trail, game, enemy, from, to).filter { it.fell && it.end.isExplored(civ) && (enemy to it.id) !in inCampaigns }.map { enemy to it } }
            .groupBy { (enemy, member) -> enemy to RulerCampaigns.placeOf(civ, member.end, enemies, enemy) }.entries
            .map { (key, group) -> mapOf("whose" to key.first, "what" to RulerCampaigns.company(group.map { it.second.name }), "where" to key.second, "at" to RulerCampaigns.atPlace(key.second)) }
        // Cities that changed hands in the age
        val taken = trail.marks.filter { it.turn in from..to && it.kind == "taken" }.mapNotNull { mark ->
            game.tileMap.getIfTileExistsOrNull(mark.x, mark.y)?.getCity()?.let { city -> mapOf("city" to city.name, "by" to mark.civ, "year" to yearText(mark.turn)) } }

        // The known world's growth: what was seen for the first time, in which direction, how far, and what it held
        val fresh = trail.seen.subMap(from, false, to, true).values.flatMap { it[civ.civName]?.toList() ?: emptyList() }
            .mapNotNull { game.tileMap.tileList.getOrNull(it) }
        val capital = civ.getCapital()?.getCenterTile()
        val exploration = if (fresh.isEmpty()) null else {
            val land = fresh.count { it.isLand }
            val direction = capital?.let { RulerCampaigns.compassOf(fresh.map { t -> HexMath.hex2WorldCoords(t.position) }.let { pts -> pts.reduce { a, b -> a.add(b) }.scl(1f / pts.size) }, HexMath.hex2WorldCoords(it.position)) }
            val farthest = capital?.let { c -> fresh.maxOf { it.aerialDistanceTo(c) } }
            val wonders = fresh.mapNotNull { it.naturalWonder }.distinct()
            val found = fresh.filter { it.isCityCenter() }.mapNotNull { it.getCity() }
            mapOf("tilesSeen" to fresh.size, "mostly" to (if (land * 2 >= fresh.size) "land" else "sea"), "direction" to direction, "farthest" to farthest,
                "naturalWonders" to wonders, "citiesFound" to found.map { "${it.name} (${it.civ.civName})" }, "citiesFoundTold" to RulerCampaigns.citiesOf(found, civ))
        }
        val met = trail.met.subMap(from, false, to, true).values.flatMap { it[civ.civName] ?: emptyList() }.distinct()
        // Our farthest ranging units - the scouts and ships whose journeys the exploration came from
        val explorers = ours.filter { m -> !m.air && civ.cities.none { it.getCenterTile().aerialDistanceTo(m.end) < 8 } && m.start.aerialDistanceTo(m.end) >= 8 }
            .sortedByDescending { it.start.aerialDistanceTo(it.end) }.take(3)
            .map { mapOf("what" to it.name, "reached" to RulerCampaigns.placeOf(civ, it.end, enemies)) }

        // The companies in sight now, as the map's badges show them: none inside a city, which stands for its garrison, and no aircraft
        // A soldier at sea is embarked, in boats - not standing on the water
        val inSight = civ.viewableTiles.filter { !it.isCityCenter() }.flatMap { tile -> tile.getUnits().filter { !it.baseUnit.isAirUnit() }.map { tile to it } }
            .groupBy { (tile, unit) -> Triple(unit.civ.civName, RulerCampaigns.placeOf(civ, tile, enemies, unit.civ.civName), tile.isWater && unit.baseUnit.isLandUnit) }.entries
            .sortedWith(compareBy({ it.key.first != civ.civName }, { it.key.first }))
            .map { (key, group) -> mapOf("whose" to key.first, "what" to RulerCampaigns.company(group.map { it.second.name }) + if (key.third) ", embarked in boats" else "",
                "where" to key.second, "at" to RulerCampaigns.atPlace(key.second), "sea" to group.all { it.second.baseUnit.isWaterUnit }) }
        // Known country standing apart from the rest of the known world - the lands round a far city heard of - which a painter would otherwise ring with a coast
        val glimpses = run {
            val known = game.tileMap.tileList.filter { it.isExplored(civ) }.toHashSet()
            val reached = HashSet<Tile>(); val names = ArrayList<String>()
            for (start in known) {
                if (!reached.add(start)) continue
                val part = ArrayList<Tile>(); val queue = ArrayDeque(listOf(start))
                while (queue.isNotEmpty()) { val tile = queue.removeFirst(); part += tile; for (next in tile.neighbors) if (next in known && reached.add(next)) queue += next }
                if (part.size <= 40 && part.none { it.getOwner() == civ }) names += part.filter { it.isCityCenter() }.mapNotNull { it.getCity()?.name }
            }
            names
        }

        // The same, told
        val lines = ArrayList<String>()
        for (m in marches) lines += m["told"] as String
        for (l in lost) lines += "We lost ${l["what"]} ${l["at"]}."
        for (t in theirs) lines += t["told"] as String
        for (l in enemyLost) lines += "${l["whose"]} lost ${l["what"]} ${l["at"]}."
        // A city taken and taken back is told as one story, ending with who holds it now
        for (hands in taken.groupBy { it["city"] }.values) {
            val city = hands[0]["city"]
            lines += if (hands.size == 1) "$city fell to ${RulerCampaigns.people(hands[0]["by"] as String)} in ${hands[0]["year"]}."
            else "$city changed hands ${RulerCampaigns.count(hands.size)} times: " + andList(hands.mapIndexed { i, t -> "to ${RulerCampaigns.people(t["by"] as String)}${if (hands.take(i).any { it["by"] == t["by"] }) " again" else ""} in ${t["year"]}" }) + "; ${RulerCampaigns.people(hands.last()["by"] as String)} holds it now."
        }
        // The known world's growth in words, never a count of tiles: how much, land or water, which way and how far from home
        if (exploration != null) lines += run {
            val seen = exploration["tilesSeen"] as Int
            val found = if (exploration["mostly"] == "sea") (if (seen < 12) "a stretch of new water" else if (seen > 60) "wide new waters" else "new waters")
                else (if (seen < 12) "a little new country" else if (seen > 60) "wide new country" else "new country")
            val reach = (exploration["farthest"] as Int?)?.let { if (it <= 6) ", close to home" else if (it <= 12) ", a good way from the capital" else ", far from the capital" } ?: ""
            "We found $found${exploration["direction"]?.let { " to the $it" } ?: ""}$reach."
        } +
            (exploration["naturalWonders"] as List<*>).takeIf { it.isNotEmpty() }?.let { " Found: ${it.joinToString()}." }.orEmpty() +
            (exploration["citiesFoundTold"] as String).takeIf { it.isNotEmpty() }?.let { " Cities first seen: $it." }.orEmpty()
        for (e in explorers) lines += "Our ${e["what"]} ranged as far as ${e["reached"]}."
        if (met.isNotEmpty()) lines += "We met ${met.joinToString { RulerCampaigns.people(it) }}."
        for ((turn, text) in nukes) if (turn in from..to) lines += text
        if (lines.isEmpty()) lines += "No company marched beyond its home fields, nothing new was seen, and no one new was met."

        return mapOf("from" to from, "to" to to, "fromYear" to yearText(from), "toYear" to yearText(to),
            "marches" to marches, "lost" to lost, "enemyMarches" to theirs, "enemyLost" to enemyLost, "citiesTaken" to taken,
            "exploration" to exploration, "explorers" to explorers, "met" to met, "inSight" to inSight, "glimpses" to glimpses, "lines" to lines,
            "curiosities" to curiosities(civ, from, to, first, last, enemies))
    }
}
