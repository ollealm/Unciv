package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 *  A ruler's direct orders to single units. While a unit has an order the automation leaves it alone;
 *  the order is carried out during the civ's turn via [com.unciv.logic.automation.RulerOverrides.unitHandler].
 */
class RulerUnits(private val log: (Civilization, String) -> Unit) {

    /** action: move (then release), settle, fortify/garrison, hold, explore (then release), use (a great person's ability, then release) */
    class Order(val action: String, val x: Int?, val y: Int?) {
        /** Where the unit stood last turn and for how many turns, to notice an order that is going nowhere */
        var lastSpot: String? = null
        var turnsStuck = 0
        fun toMap() = mapOf("action" to action, "x" to x, "y" to y)
    }

    /** unit id -> order */
    val orders = HashMap<Int, Order>()

    fun load(json: JsonValue?) {
        orders.clear()
        json?.forEach { orders[it.name.toInt()] = Order(it.getString("action"), it.get("x")?.takeUnless { v -> v.isNull }?.asInt(), it.get("y")?.takeUnless { v -> v.isNull }?.asInt()) }
    }

    fun toMap() = orders.mapKeys { it.key.toString() }.mapValues { it.value.toMap() }

    /** Called by the automation for every unit of a ruled civ. True = we handled it, leave it alone. */
    /** Great People the ruler has handed back to the officials */
    val released = HashSet<Int>()

    fun handle(unit: MapUnit): Boolean {
        // A Great Person is the ruler's to spend: the officials never touch one unless it was released to them.
        // Caught here, in the automation's own loop, because one born at the start of a turn would otherwise be spent within it.
        if (unit.id !in orders && unit.id !in released && unit.name.startsWith("Great ")) {
            orders[unit.id] = Order("hold", null, null)
            log(unit.civ, "A ${unit.name} #${unit.id} is at ${where(unit.civ, unit.getTile())} and awaits orders (use, move, or release to the officials).")
        }
        val order = orders[unit.id] ?: return false
        val civ = unit.civ
        val target = if (order.x != null && order.y != null) civ.gameInfo.tileMap.getIfTileExistsOrNull(order.x, order.y) else null

        if (target != null && order.action == "settle") settleProblem(civ, target)?.let { problem ->
            // The site went bad on the way (someone settled nearby): say so at once, do not walk on
            log(civ, "Our Settler's site at ${where(civ, target)} is no longer usable: $problem${nearestSiteHint(civ, unit, target)}. The order is dropped; give a new site.")
            orders.remove(unit.id)
            return true
        }
        if (target != null && unit.getTile() != target && order.action == "garrison"
            && target.militaryUnit?.civ == civ && unit.movement.canUnitSwapTo(target)) {
            val relieved = target.militaryUnit!!
            unit.movement.swapMoveToTile(target)
            orders.remove(relieved.id)
            log(civ, "Our ${unit.name} relieved the ${relieved.name} as garrison of ${target.getCity()?.name}.")
        }
        if (target != null && unit.getTile() != target && order.action == "garrison" && target.militaryUnit?.civ == civ
            && !unit.movement.canReach(target)) {
            // The city is held by one of ours: walk up beside it, the swap happens once we are adjacent
            val beside = target.neighbors.filter { unit.movement.canReach(it) }.minByOrNull { it.aerialDistanceTo(unit.getTile()) }
            if (beside != null) { if (beside != unit.getTile()) unit.movement.headTowards(beside); return true }
        }
        if (target != null && unit.getTile() != target) {
            if (!unit.movement.canReach(target)) {
                log(civ, "Our ${unit.name} cannot reach ${where(civ, target)}; the order is dropped and the officials take it back.")
                orders.remove(unit.id)
                return false
            }
            unit.movement.headTowards(target)
            if (unit.getTile() != target) {
                val spot = unit.getTile().position.toString()
                order.turnsStuck = if (spot == order.lastSpot) order.turnsStuck + 1 else 0
                order.lastSpot = spot
                if (order.turnsStuck >= 3) {
                    val blocker = target.militaryUnit?.takeIf { !unit.isCivilian() } ?: target.civilianUnit?.takeIf { unit.isCivilian() }
                    log(civ, "Our ${unit.name} #${unit.id} has been stuck for ${order.turnsStuck} turns on its way to ${where(civ, target)}" +
                        (blocker?.let { " - the tile is held by ${it.civ.civName}'s ${it.name}" } ?: " - the way is blocked") + ". The order is dropped.")
                    orders.remove(unit.id)
                    return false
                }
                return true // still on the way
            }
            log(civ, "Our ${unit.name} arrived at ${where(civ, target)}.")
        }

        return when (order.action) {
            "settle" -> {
                if (UnitActions.invokeUnitAction(unit, UnitActionType.FoundCity)) {
                    log(civ, "Our Settler founded ${civ.cities.last().name} at ${where(civ, unit.getTile())}.")
                    orders.remove(unit.id)
                    true
                } else {
                    log(civ, "Our Settler cannot found a city at ${where(civ, unit.getTile())} (too close to another city, or unusable ground); the order is dropped.")
                    orders.remove(unit.id)
                    false
                }
            }
            "fortify", "garrison" -> { unit.fortifyIfCan(); true }
            "use" -> {
                val used = ABILITIES.firstOrNull { UnitActions.invokeUnitAction(unit, it) }
                if (used != null) log(civ, "Our ${unit.name} used its ability (${used.name}) at ${where(civ, unit.getTile())}.")
                else log(civ, "Our ${unit.name} could not use its ability at ${where(civ, unit.getTile())}: nothing to use it on here (trade missions need a city-state's city, improvements need an unimproved tile of ours).")
                orders.remove(unit.id)
                used != null
            }
            "hold" -> true
            "disband" -> { log(civ, "Our ${unit.name} #${unit.id} was disbanded."); orders.remove(unit.id); unit.disband(); true }
            "explore" -> { unit.action = UnitActionType.Explore.value; orders.remove(unit.id); false }
            else -> { orders.remove(unit.id); true } // "move": arrived, released to the officials from next turn
        }
    }

    /** Give an order from the API: {unit: id, action, x?, y?, city?} */
    fun give(civ: Civilization, json: JsonValue): String {
        val unitId = json.getInt("unit")
        val unit = civ.units.getUnitById(unitId) ?: throw IllegalArgumentException("No unit with id $unitId - see the briefing's unit list")
        val action = json.getString("action", "move")
        if (action !in listOf("move", "settle", "fortify", "garrison", "hold", "explore", "use", "disband", "release"))
            throw IllegalArgumentException("Unknown unit action $action; use move, settle, fortify, garrison, hold, explore, use, disband or release")
        if (action == "disband") { orders.remove(unitId); val name = unit.name; unit.disband(); log(civ, "Our $name #$unitId was disbanded."); return "$name #$unitId disbanded." }
        if (action == "release") { orders.remove(unitId); released += unitId; return "${unit.name} #$unitId is back under the officials." }
        if (action == "settle" && !unit.hasUnique(UniqueType.FoundCity)) throw IllegalArgumentException("${unit.name} cannot found cities")
        if (action == "explore" && unit.isCivilian()) throw IllegalArgumentException("Civilians cannot explore; send a military unit")

        var x: Int? = null; var y: Int? = null
        json.get("city")?.takeUnless { it.isNull }?.asString()?.let { cityName ->
            val city = civ.gameInfo.getCities().firstOrNull { it.name == cityName } ?: throw IllegalArgumentException("No city named $cityName")
            x = city.getCenterTile().position.x; y = city.getCenterTile().position.y
        }
        json.get("x")?.takeUnless { it.isNull }?.let { x = it.asInt(); y = json.getInt("y") }
        if (x != null && civ.gameInfo.tileMap.getIfTileExistsOrNull(x!!, y!!) == null) throw IllegalArgumentException("No tile at ($x,$y)")
        if (x == null && action in listOf("move", "garrison")) throw IllegalArgumentException("$action needs a target: x/y or city")

        val target = if (x != null) civ.gameInfo.tileMap[x!!, y!!] else unit.getTile()
        if (action == "settle") settleProblem(civ, target)?.let { throw IllegalArgumentException("Cannot found a city at ${where(civ, target)}: $it" + nearestSiteHint(civ, unit, target)) }
        if (action == "garrison" && !target.isCityCenter()) throw IllegalArgumentException("garrison needs a city")
        if (action == "garrison" && unit.isCivilian()) throw IllegalArgumentException("Only soldiers can garrison")
        if (action == "garrison") target.militaryUnit?.takeIf { it != unit && it.civ != civ }?.let { holder ->
            throw IllegalArgumentException("${target.getCity()!!.name} is held by ${holder.civ.civName}'s ${holder.name}")
        }
        val willSwap = action == "garrison" && target.militaryUnit?.civ == civ
        val occupant = (if (unit.isCivilian()) target.civilianUnit else target.militaryUnit)?.takeIf { it != unit }
        if (occupant != null && !willSwap) throw IllegalArgumentException(
            "${where(civ, target)} is already held by ${if (occupant.civ == civ) "our" else occupant.civ.civName + "'s"} ${occupant.name} #${occupant.id}; two ${if (unit.isCivilian()) "civilians" else "soldiers"} cannot share a tile. " +
            (if (occupant.civ == civ) "Use garrison to swap places with it, pick a tile beside it, or leave it be - the place is already covered." else "Pick another tile."))
        if (target != unit.getTile() && !willSwap && !unit.movement.canReach(target)) throw IllegalArgumentException("${unit.name} cannot reach ${where(civ, target)} (water, mountains or foreign borders in the way)")
        orders[unitId] = Order(action, x, y)
        val turns = if (target == unit.getTile()) 0 else kotlin.math.ceil(unit.movement.getShortestPath(target).size / unit.getMaxMovement().coerceAtLeast(1).toFloat()).toInt()
        return "${unit.name} #$unitId will $action at ${where(civ, target)}" + (if (turns > 0) ", about $turns turns away" else "")
    }

    fun describeUnit(civ: Civilization, unit: MapUnit): String {
        val order = orders[unit.id]
        val state = when {
            order != null -> "under orders: ${order.action}" + (if (order.x != null) " at (${order.x},${order.y})" else "")
            unit.action?.startsWith("Fortify") == true -> "fortified"
            unit.action == "Sleep" -> "sleeping"
            unit.action?.contains("explore", ignoreCase = true) == true -> "exploring"
            else -> "automated"
        }
        val health = if (unit.health < 100) ", ${unit.health} hp" else ""
        return "${unit.name} #${unit.id} at ${where(civ, unit.getTile())}, $state$health"
    }

    /** Why a city cannot be founded on this tile, or null if it can */
    fun settleProblem(civ: Civilization, tile: Tile): String? {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val tooClose = tile.getTilesInDistance(constants.minimalCityDistance).firstOrNull { it.isCityCenter() }
        return when {
            tile.isWater -> "it is water"
            tile.isImpassible() -> "it is impassable"
            tooClose != null -> "too close to ${tooClose.getCity()!!.name} (cities need ${constants.minimalCityDistance + 1} tiles between them)"
            tile.getOwner() != null && tile.getOwner() != civ -> "it belongs to ${tile.getOwner()!!.civName}"
            !tile.canBeSettled(civ) -> "the ground will not do"
            !tile.isExplored(civ) -> "we have not explored it"
            else -> null
        }
    }

    private fun nearestSiteHint(civ: Civilization, unit: MapUnit, target: Tile): String {
        val best = CityLocationTileRanker.getBestTilesToFoundCity(unit, 8, 0f).tileRankMap.entries
            .filter { settleProblem(civ, it.key) == null }
            .minByOrNull { it.key.aerialDistanceTo(target) * 10 - it.value } ?: return ""
        return ". Nearest good site: ${where(civ, best.key)}"
    }

    /** Best places to found a city near our lands: the officials' ranking when a settler can judge, else our own simple survey */
    fun citySites(civ: Civilization): List<String> {
        val settler = civ.units.getCivUnits().firstOrNull { it.hasUnique(UniqueType.FoundCity) }
        val ranked: Map<Tile, Float> = settler?.let { CityLocationTileRanker.getBestTilesToFoundCity(it, 8, 0f).tileRankMap }
            ?.filterKeys { settleProblem(civ, it) == null }?.takeIf { it.isNotEmpty() }
            ?: survey(civ)
        return ranked.entries.sortedByDescending { it.value }.take(5).map { (tile, rank) -> describeSite(civ, tile, rank) }
    }

    /** Land within reach of our cities that could take a city, scored by what lies around it */
    private fun survey(civ: Civilization): Map<Tile, Float> {
        if (civ.cities.isEmpty()) return emptyMap()
        val candidates = civ.cities.asSequence().flatMap { it.getCenterTile().getTilesInDistance(9) }.distinct()
            .filter { it.isExplored(civ) && settleProblem(civ, it) == null }
        return candidates.associateWith { tile ->
            var score = 0f
            for (near in tile.getTilesInDistance(2)) {
                if (!near.isExplored(civ)) continue
                score += when (near.baseTerrain) { "Grassland" -> 2f; "Plains" -> 1.6f; "Coast" -> 1f; "Desert", "Tundra", "Snow", "Ocean", "Mountain" -> 0.2f; else -> 1f }
                if (near.resource != null) score += 3f
                if (near.isHill()) score += 0.5f
                if (near.isAdjacentToRiver()) score += 0.5f
            }
            if (tile.isAdjacentToRiver()) score += 4f
            if (tile.neighbors.any { it.isWater }) score += 2f
            if (tile.isHill()) score += 2f
            score - civ.cities.minOf { it.getCenterTile().aerialDistanceTo(tile) } * 0.8f // nearer home is easier to hold
        }
    }

    private fun describeSite(civ: Civilization, tile: Tile, rank: Float): String {
        val resources = tile.getTilesInDistance(2).mapNotNull { it.resource }.groupingBy { it }.eachCount()
            .entries.joinToString { if (it.value > 1) "${it.key} x${it.value}" else it.key }
        val features = listOfNotNull(
            tile.baseTerrain,
            if (tile.isAdjacentToRiver()) "river" else null,
            if (tile.neighbors.any { it.isWater }) "coast" else null,
            if (tile.isHill()) "hill" else null,
        ).joinToString(", ")
        return "${where(civ, tile)}: $features" + (if (resources.isNotEmpty()) "; nearby $resources" else "") + " (rank ${rank.roundToInt()})"
    }

    companion object {
        private val ABILITIES = listOf(UnitActionType.ConductTradeMission, UnitActionType.HurryResearch, UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding, UnitActionType.HurryPolicy, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion,
            UnitActionType.TriggerUnique, UnitActionType.CreateImprovement, UnitActionType.SpreadReligion)

        /** "(x,y), 3 tiles NE of Rome" */
        fun where(civ: Civilization, tile: Tile): String {
            val coords = "(${tile.position.x},${tile.position.y})"
            val nearest = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return coords
            val distance = nearest.getCenterTile().aerialDistanceTo(tile)
            if (distance == 0) return "$coords, in ${nearest.name}"
            val from = HexMath.hex2WorldCoords(nearest.getCenterTile().position)
            val to = HexMath.hex2WorldCoords(tile.position)
            val angle = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()))
            val compass = listOf("E", "NE", "N", "NW", "W", "SW", "S", "SE")[(((angle + 360 + 22.5) % 360) / 45).toInt()]
            return "$coords, $distance tiles $compass of ${nearest.name}"
        }
    }
}
