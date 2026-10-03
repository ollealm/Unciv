package com.unciv.app.desktop.ruler

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.Tile
import kotlin.math.atan2
import com.unciv.app.desktop.ruler.Prose.andList
import com.unciv.app.desktop.ruler.Prose.article
import com.unciv.app.desktop.ruler.Prose.pluralOf
import com.unciv.logic.automation.RulerOverrides

/**
 *  An age's movements as a history atlas draws them: not every unit's wiggle, but the few forces that went somewhere together -
 *  where each set out, the way it went, where it was headed and how it ended. The age summary tells these and the campaign map
 *  draws them, so the words and the arrows cannot disagree.
 */
object RulerCampaigns {
    /** How a force's age ended: it took the city it marched on, was broken (half or more of it fell), turned back, or is still out there */
    enum class Outcome { Took, Broken, Back, Out }

    /** One unit's age: its path while it was [owner]'s, and the point it was making for - where it ended, fell, or turned back */
    class Member(val id: Int, val name: String, val path: List<Tile>, val goal: Int, val fell: Boolean, val back: Boolean, val sea: Boolean, val air: Boolean, val scout: Boolean) {
        val start get() = path.first()
        val end get() = path.last()
        val target get() = path[goal]
        val reach get() = start.aerialDistanceTo(target)
    }

    class Campaign(val whose: String, val ours: Boolean, val members: List<Member>, val outcome: Outcome, val city: City?, val lostAgain: Boolean,
                   /** From where the force set out to where it was making for, simplified to a few points */
                   val route: List<Tile>,
                   /** A force that turned back: the way home, from its farthest point to where it stands now */
                   val homeward: List<Tile>) {
        val sea get() = members.first().sea
        val size get() = members.size
        val fell get() = members.count { it.fell }
        val reach get() = route.first().aerialDistanceTo(route.last())
    }

    /** Majors at war with [civ] now, or lately: a peace treaty still in force means the war was within the age */
    fun enemiesOf(civ: Civilization): Set<String> = civ.gameInfo.civilizations.filter { other -> other.isMajorCiv() && other != civ && (civ.isAtWarWith(other) ||
        civ.getDiplomacyManager(other)?.trades?.any { trade -> (trade.ourOffers + trade.theirOffers).any { it.name == com.unciv.Constants.peaceTreaty } } == true) }
        .map { it.civName }.toSet()

    /** Every military unit of [owner]'s in the age, with its path while it was theirs. A unit given away within the age is left out:
     *  the gift is no march of the giver's, and the receiver's march begins at the handing over. An upgrade keeps the unit's id, so its path runs on. */
    fun members(trail: RulerTrail, game: GameInfo, owner: String, from: Int, to: Int): List<Member> {
        val turns = trail.stands.subMap(from, true, to, true)
        val last = turns.lastEntry()?.value ?: emptyMap()
        val ids = turns.values.flatMap { stands -> stands.filter { it.value.civ == owner && it.value.military }.keys }.toSet()
        return ids.mapNotNull { id ->
            val stands = turns.values.mapNotNull { it[id] }
            if (stands.last().civ != owner) return@mapNotNull null
            val path = ArrayList<Tile>()
            for (stand in stands) if (stand.civ == owner) {
                val tile = game.tileMap.getIfTileExistsOrNull(stand.x, stand.y) ?: continue
                if (path.lastOrNull() != tile) path += tile
            }
            // Where it fell ends its path: the battle hook's tile, which its last stand may not be (it marched there and died the same turn)
            val fall = if (id in last) null else trail.fallOf(id, owner, from, to)
                // a mark kept before the unit's number was saved with it (a host restarted mid-age): the mark on its last stand is its fall
                ?: path.lastOrNull()?.let { end -> trail.marks.firstOrNull { it.kind == "fallen" && it.id == -1 && it.turn in from..to && it.civ == owner && it.x == end.position.x && it.y == end.position.y } }
            fall?.let { game.tileMap.getIfTileExistsOrNull(it.x, it.y) }?.let { if (path.lastOrNull() != it) path += it }
            if (path.isEmpty()) return@mapNotNull null
            // Units lost before names were kept go by what they were: a ship or a soldier
            val name = stands.lastOrNull { it.name.isNotEmpty() }?.name ?: trail.nameOf(id)
            val unit = game.ruleset.units[name]
            val sea = unit?.isWaterUnit ?: path.all { it.isWater }
            val fell = fall != null
            val start = path.first()
            val far = path.indices.maxBy { start.aerialDistanceTo(path[it]) }
            // Went out and came home again: what it was making for is the farthest it got, not where it stands now
            val back = !fell && path[far].aerialDistanceTo(path.last()) >= 3 && start.aerialDistanceTo(path.last()) <= start.aerialDistanceTo(path[far]) - 3
            Member(id, name.ifEmpty { if (sea) "ship" else "soldier" }, path, if (back) far else path.lastIndex, fell, back, sea, unit?.isAirUnit() == true, unit?.unitType == "Scout")
        }
    }

    /** The age's campaigns as [civ] can tell them: at most three of its own and three of the enemy's it could see, the weightiest first */
    fun reckon(trail: RulerTrail, civ: Civilization, from: Int, to: Int): List<Campaign> {
        val enemies = enemiesOf(civ)
        val ours = forces(trail, civ, civ.civName, from, to).take(3)
        val theirs = enemies.flatMap { forces(trail, civ, it, from, to) }.sortedByDescending { weight(it) }.take(3)
        return ours + theirs
    }

    private fun weight(c: Campaign) = c.size * (c.reach + 2) + when (c.outcome) { Outcome.Took -> 30; Outcome.Broken -> 15; else -> 0 }

    private fun cityNear(game: GameInfo, tile: Tile, within: Int) =
        game.getCities().map { it.getCenterTile() }.filter { it.aerialDistanceTo(tile) <= within }.minByOrNull { it.aerialDistanceTo(tile) }?.getCity()

    private fun forces(trail: RulerTrail, civ: Civilization, owner: String, from: Int, to: Int): List<Campaign> {
        val game = civ.gameInfo
        val ours = owner == civ.civName
        // Aircraft rebase from field to field and missiles fly once: no march of theirs is a campaign; a scout's journey is exploration, told as such.
        // The enemy's only on ground we know.
        val movers = members(trail, game, owner, from, to).filter { !it.air && !it.scout && it.reach >= 3 && (ours || it.target.isExplored(civ)) }
        // Gather into forces: set out from about the same place, making for about the same place or the same city
        val groups = ArrayList<MutableList<Member>>()
        for (m in movers.sortedByDescending { it.reach }) {
            val group = groups.firstOrNull { g -> g[0].sea == m.sea && g[0].start.aerialDistanceTo(m.start) <= 4 &&
                (g[0].target.aerialDistanceTo(m.target) <= 3 || cityNear(game, g[0].target, RulerOverrides.FIELDS)?.let { it == cityNear(game, m.target, RulerOverrides.FIELDS) } == true) }
            if (group != null) group += m else groups += mutableListOf(m)
        }
        val taken = trail.marks.filter { it.kind == "taken" && it.civ == owner && it.turn in from..to }.mapNotNull { game.tileMap.getIfTileExistsOrNull(it.x, it.y) }
        return groups.map { group ->
            // The force's way is the way its most typical member went
            val medoid = group.minBy { m -> group.sumOf { o -> m.start.aerialDistanceTo(o.start) + m.target.aerialDistanceTo(o.target) } }
            val took = taken.firstOrNull { city -> group.any { it.target.aerialDistanceTo(city) <= RulerOverrides.FIELDS } }
            val outcome = when {
                took != null -> Outcome.Took
                group.count { it.fell } * 2 >= group.size -> Outcome.Broken
                group.count { it.back } * 2 > group.size -> Outcome.Back
                else -> Outcome.Out
            }
            // To the first arrival before the goal: the dithering in front of the walls is no part of the march
            val arrive = (0..medoid.goal).first { medoid.path[it].aerialDistanceTo(medoid.target) <= 1 }
            val way = medoid.path.subList(0, arrive + 1).toMutableList()
            if (way.last() != medoid.target) way += medoid.target
            if (took != null && way.last() != took) way += took
            val route = broadWay(way)
            val homeward = if (outcome == Outcome.Back) broadWay(medoid.path.subList(medoid.goal, medoid.path.size)) else emptyList()
            val city = took?.getCity() ?: cityNear(game, medoid.target, RulerOverrides.FIELDS)
            Campaign(owner, ours, group, outcome, city, took != null && city?.civ?.civName != owner, route, homeward)
        }.filter { c -> worthAnArrow(game, c, owner) }.sortedByDescending { weight(it) }
    }

    /** A battle, a city taken, a march on a foe, or a host on the move in a war: soldiers changing garrisons in peace are no campaign,
     *  nor is an assault from right under the walls - the ring round the city tells that */
    private fun worthAnArrow(game: GameInfo, c: Campaign, owner: String): Boolean {
        if (c.reach < 3) return false
        val foes = game.civilizations.firstOrNull { it.civName == owner }?.let { enemiesOf(it) } ?: emptySet()
        val onAFoe = game.getCities().any { it.civ.civName in foes && it.getCenterTile().aerialDistanceTo(c.route.last()) <= RulerOverrides.NEAR }
        return when (c.outcome) {
            Outcome.Took -> true
            Outcome.Broken -> c.size >= 2 || c.reach >= 6 // one soldier lost a few tiles out is a skirmish, told among the losses
            Outcome.Back -> c.fell > 0 || onAFoe
            Outcome.Out -> c.fell > 0 || (c.size >= 2 && onAFoe) || (foes.isNotEmpty() && c.size >= 3 && c.reach >= 5)
        }
    }

    /** The way as an atlas draws it: from start to goal, bending once where a long march went well aside from the straight line - round a sea
     *  or a range - and not for every zig of a column or jump of a railway. A march of a few tiles goes straight: its turns are tactics, not the way.
     *  A bend behind the start or beyond the goal is a detour, not the way. */
    private fun broadWay(path: List<Tile>): List<Tile> {
        if (path.size <= 2 || path.first().aerialDistanceTo(path.last()) < 6) return listOf(path.first(), path.last())
        val points = path.map { HexMath.hex2WorldCoords(it.position) }
        val a = points.first(); val dx = points.last().x - a.x; val dy = points.last().y - a.y
        val chord = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        var bend = -1; var farthest = maxOf(2.5f * TILE, 0.35f * chord)
        for (i in 1 until path.lastIndex) {
            val rx = points[i].x - a.x; val ry = points[i].y - a.y
            val along = (rx * dx + ry * dy) / (chord * chord)
            if (along < 0.15f || along > 0.85f) continue
            val aside = kotlin.math.abs(rx * dy - ry * dx) / chord
            if (aside > farthest) { bend = i; farthest = aside }
        }
        return if (bend < 0) listOf(path.first(), path.last()) else listOf(path.first(), path[bend], path.last())
    }
    private val TILE = kotlin.math.sqrt(3f) // neighbouring tiles lie this far apart on the drawn plane

    // Telling

    /** "3 Infantry and a Rocket Artillery" */
    fun company(names: List<String>): String {
        val parts = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { (name, n) -> if (n == 1) article(name) else "$n ${pluralOf(name)}" }
        return andList(parts)
    }

    fun count(n: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").getOrNull(n) ?: "$n"

    /** The campaign in one sentence of the world's words, as [civ] tells it */
    fun told(c: Campaign, civ: Civilization, enemies: Set<String>): String {
        val what = company(c.members.map { it.name }).replaceFirstChar { it.uppercase() }
        val who = if (c.ours) what else "$what of ${people(c.whose)}"
        val verb = if (c.sea) "sailed" else if (c.ours) "marched" else "came"
        val from = placeOf(civ, c.route.first(), enemies, c.whose)
        val to = placeOf(civ, c.route.last(), enemies, c.whose)
        val fell = when {
            c.fell == 0 -> ""
            c.size == 1 -> "it fell"
            c.fell == c.size -> if (c.size == 2) "both fell" else "all fell"
            else -> "${count(c.fell)} of the ${count(c.size)} fell"
        }
        return when (c.outcome) {
            Outcome.Took -> if (c.fell == c.size) "$who $verb from $from to $to, where $fell as the city was taken${if (c.lostAgain) ", though it was lost again" else ""}."
                else "$who $verb from $from to $to and took it${if (c.lostAgain) ", but lost it again" else ""}${if (fell.isEmpty()) "" else "; $fell"}."
            Outcome.Broken -> "$who $verb from $from to $to, where $fell."
            Outcome.Back -> "$who went from $from as far as $to and turned back${if (fell.isEmpty()) "" else "; $fell"}."
            Outcome.Out -> "$who $verb from $from to $to${if (fell.isEmpty()) "" else "; $fell"}."
        }
    }

    /** A place as a chronicle names it: a city, its fields, or a distance and direction from the nearest city we know.
     *  "The walls of" a city are the walls of a foe's: of our enemy's for our own companies, of ours for the enemy's ([mover] is whose company stands there) */
    fun placeOf(civ: Civilization, tile: Tile, enemies: Set<String>, mover: String = civ.civName): String {
        val city = tile.getCity()
        if (city != null && tile.isCityCenter()) return named(city, civ, mover)
        val near = civ.gameInfo.getCities().filter { it.getCenterTile().isExplored(civ) }.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return "far from any city"
        val distance = near.getCenterTile().aerialDistanceTo(tile)
        val foes = if (mover == civ.civName) near.civ.civName in enemies else near.civ == civ
        return when {
            // A ship's place is told in the sea's words: at a city's harbour, or in the waters off it - never its walls or its fields
            tile.isWater && distance <= 1 -> "the harbour of ${named(near, civ, mover)}"
            tile.isWater && distance <= RulerOverrides.FIELDS -> "the waters off ${named(near, civ, mover)}"
            distance <= 1 && foes -> "the walls of ${named(near, civ, mover)}"
            distance <= RulerOverrides.FIELDS -> "the fields of ${named(near, civ, mover)}"
            // Beyond a city's fields, the country or the waters that way, and distant ones well beyond; never a count of tiles
            else -> "the ${if (distance > 5) "distant " else ""}${if (tile.isWater) "waters" else "country"} ${compassOf(HexMath.hex2WorldCoords(tile.position), HexMath.hex2WorldCoords(near.getCenterTile().position))} of ${named(near, civ, mover)}"
        }
    }

    /** A city as [civ] names it, whose it is said in words and never in brackets: "Seoul", "Ethiopia's Axum". Our own cities, the [mover]'s own
     *  (the company's whose it is, already named) and a city-state named for its city go by the city's name alone */
    fun named(city: City, civ: Civilization, mover: String = civ.civName) =
        if (city.civ == civ || city.civ.civName == mover || city.civ.civName == city.name) city.name else "${possessive(city.civ.civName)} ${city.name}"

    /** A people's name within a sentence: "the Maya", "Ethiopia" */
    fun people(civName: String) = civName.replaceFirst(Regex("^The "), "the ")

    /** "Ethiopia's", "the Maya's", "the Netherlands'" - for use within a sentence */
    fun possessive(civName: String): String {
        val name = people(civName)
        return if (name.endsWith("s")) "$name'" else "$name's"
    }

    /** Cities by whose they are: "Axum and Addis Ababa of Ethiopia; Carthage" - a city-state named for its city stands alone */
    fun citiesOf(cities: List<City>, civ: Civilization): String = cities.groupBy { it.civ }.entries.joinToString("; ") { (owner, group) ->
        val names = andList(group.map { it.name })
        if (owner == civ || group.all { it.name == owner.civName }) names else "$names of ${people(owner.civName)}"
    }

    /** A place for a chancery line of [civ]'s own: its enemies reckoned here */
    fun placeOf(civ: Civilization, tile: Tile) = placeOf(civ, tile, enemiesOf(civ))
    fun atPlaceOf(civ: Civilization, tile: Tile) = atPlace(placeOf(civ, tile))

    /** A [placeOf] place with the word before it: at a city, its walls or its fields; in the country or waters beyond */
    fun atPlace(place: String) = when {
        place.startsWith("far ") -> place
        Regex("^the (distant )?(country|waters) ").containsMatchIn(place) -> "in $place"
        else -> "at $place"
    }

    fun compassOf(to: com.badlogic.gdx.math.Vector2, from: com.badlogic.gdx.math.Vector2, short: Boolean = false): String {
        val angle = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()))
        val point = (((angle + 360 + 22.5) % 360) / 45).toInt()
        // The advisor's unit list reads "NE"; the world's words read "north-east"
        return (if (short) listOf("E", "NE", "N", "NW", "W", "SW", "S", "SE") else listOf("east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"))[point]
    }
}
