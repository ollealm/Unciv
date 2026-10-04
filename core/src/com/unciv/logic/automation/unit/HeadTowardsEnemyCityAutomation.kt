package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

object HeadTowardsEnemyCityAutomation {

    /** @returns whether the unit has taken this action */
    fun tryHeadTowardsEnemyCity(unit: MapUnit): Boolean = timeThis("tryHeadTowardsEnemyCity") {
        if (unit.civ.cities.isEmpty()) return false

        // only focus on *attacking* 1 enemy at a time otherwise you'll lose on both fronts
        val closestReachableEnemyCity = getEnemyCitiesByPriority(unit)
            .firstOrNull { unit.movement.canReach(it.getCenterTile()) }
            ?: return headTowardsRingOfAimedCity(unit) // No enemy city reachable

        return headTowardsEnemyCity(
            unit,
            closestReachableEnemyCity.getCenterTile(),
            // This should be cached after the `canReach` call above.
            unit.movement.getShortestPath(closestReachableEnemyCity.getCenterTile())
        )
    }

    /** A ruler's war aim whose gate is plugged (its approaches held, the rest mountain or sea) has no path to its centre - but the host can still
     *  gather within striking range: engines to bombard, foot soldiers to fight for the approaches. The siege routine takes it from there. */
    private fun headTowardsRingOfAimedCity(unit: MapUnit): Boolean {
        val aim = com.unciv.logic.automation.RulerOverrides.postures[unit.civ.civID]?.warAim ?: return false
        val city = unit.civ.getKnownCivs().filter { unit.civ.isAtWarWith(it) }.flatMap { it.cities }.firstOrNull { it.name == aim } ?: return false
        val centre = city.getCenterTile()
        val reach = if (unit.baseUnit.isRanged()) unit.getRange() else 2
        if (centre.aerialDistanceTo(unit.currentTile) <= reach) return false // already in the ring: let attacking and the siege routine act
        val ringTile = centre.getTilesInDistance(reach)
            .filter { it.isLand && it != centre && unit.getDamageFromTerrain(it) <= 0 }
            .sortedBy { it.aerialDistanceTo(unit.currentTile) }
            .firstOrNull { unit.movement.canMoveTo(it) && unit.movement.canReach(it) }
            ?: return false
        return com.unciv.logic.automation.RulerOverrides.tryHeadTowards(unit, ringTile)
    }

    @Readonly
    fun getEnemyCitiesByPriority(unit: MapUnit): Sequence<City> {
        val enemies = unit.civ.getKnownCivs()
            .filter { unit.civ.isAtWarWith(it) && it.cities.isNotEmpty() }

        // A ruler's war aim beats distance: the whole host converges on the city they named
        val aimed = com.unciv.logic.automation.RulerOverrides.postures[unit.civ.civID]?.warAim
            ?.let { name -> enemies.flatMap { it.cities }.firstOrNull { it.name == name } }
        val closestEnemyCity = aimed ?: enemies
            .mapNotNull { NextTurnAutomation.getForeignCityNearCapital(unit.civ.getCapital(), it) }
            .minByOrNull { it.aerialDistance }?.city
            ?: return emptySequence() // no attackable cities found

        // Our main attack target is the closest city, but we're fine with deviating from that a bit
        var enemyCitiesByPriority = closestEnemyCity.civ.cities
            .associateWith { it.getCenterTile().aerialDistanceTo(closestEnemyCity.getCenterTile()) }
            .asSequence().filterNot { it.value > 10 } // anything 10 tiles away from the target is irrelevant
            .sortedBy { it.value }.map { it.key } // sort the list by closeness to target - least is best!

        if (unit.baseUnit.isRanged()) // ranged units don't harm capturable cities, waste of a turn
            enemyCitiesByPriority = enemyCitiesByPriority.filterNot { it.health == 1 }

        return enemyCitiesByPriority
    }


    private const val maxDistanceFromCityToConsiderForLandingArea = 5
    private const val minDistanceFromCityToConsiderForLandingArea = 3

    /** @returns whether the unit has taken this action */
    fun headTowardsEnemyCity(
        unit: MapUnit,
        closestReachableEnemyCity: Tile,
        shortestPath: List<Tile>
    ): Boolean {
        // A ruled civ's land unit going by sea never holds, nor ends a turn, at sea within reach of the city or of the enemy near it: it lands out of
        // reach and walks. Unruled civs go as upstream has them
        if (com.unciv.logic.automation.RulerOverrides.goesBySea(unit, shortestPath)) {
            // Ashore with a way there by land, it walks it: the pathfinder's hop along the coast would embark it, and the landing bring it back -
            // landed, at sea, landed, or held at its landing for good (review of the landings, 2026-10-05). Without one, it goes by sea to a landing that has.
            // A way planned with no embarking can still cross water (that bars ending a move at sea, not passing through): such a way is none. The walk is
            // its own routine, never this one again - calling this one recursed without end and stopped Run 32 at T169
            if (!unit.isEmbarked()) keptAshore(unit) {
                unit.movement.getShortestPath(closestReachableEnemyCity).takeIf { way -> way.isNotEmpty() && way.none { it.isWater } }
                    ?.let { way -> walkTowardsEnemyCity(unit, closestReachableEnemyCity, way) }
            }?.let { return it }
            return headForLanding(unit, closestReachableEnemyCity)
        }
        return walkTowardsEnemyCity(unit, closestReachableEnemyCity, shortestPath)
    }

    /** Upstream's march on an enemy city along [shortestPath]: straight on when far, to the landing grounds round it while it cannot be taken soon */
    private fun walkTowardsEnemyCity(unit: MapUnit, closestReachableEnemyCity: Tile, shortestPath: List<Tile>): Boolean {
        val unitDistanceToTiles = unit.movement.getDistanceToTiles()

        val unitRange = unit.getRange()
        if (unitRange > 2) { // long-ranged unit, should never be in a bombardable position
            return headTowardsEnemyCityLongRange(closestReachableEnemyCity, unitDistanceToTiles, unitRange, unit)
        }

        val nextTileInPath = shortestPath[0]

        // None of the stuff below is relevant if we're still quite far away from the city, so we
        // short-circuit here for performance reasons.
        if (unit.currentTile.aerialDistanceTo(closestReachableEnemyCity) > maxDistanceFromCityToConsiderForLandingArea
            // Even in the worst case of only being able to move 1 tile per turn, we would still
            // not overshoot.
            && shortestPath.size > minDistanceFromCityToConsiderForLandingArea ) {
            unit.movement.moveToTile(nextTileInPath)
            return true
        }

        val ourUnitsAroundEnemyCity = closestReachableEnemyCity.getTilesInDistance(6)
            .flatMap { it.getUnits() }
            .filter { it.isMilitary() && it.civ == unit.civ }

        val city = closestReachableEnemyCity.getCity()!!

        if (cannotTakeCitySoon(ourUnitsAroundEnemyCity, city)) {
            return headToLandingGrounds(closestReachableEnemyCity, unit)
        }

        unit.movement.moveToTile(nextTileInPath) // go for it!

        return true
    }

    /** [body] with [unit] unable to embark, its paths reckoned afresh for it and again after - as [UnitMovement] lifts canEnterForeignTerrain for a moment */
    private fun <T> keptAshore(unit: MapUnit, body: () -> T): T {
        val couldNot = unit.cache.cannotEmbark
        unit.cache.cannotEmbark = true
        unit.movement.clearPathfindingCache()
        try { return body() } finally { unit.cache.cannotEmbark = couldNot; unit.movement.clearPathfindingCache() }
    }

    /** A ruled civ's land unit going by sea: the landing is a land tile on the city's landmass, at least three from it and out of every reach
     *  (RulerOverrides.perilAtSea), that can be come to by water out of reach - the one nearest the city, further along the coast when the near shore is
     *  under the guns (Munich's whole western shore was, across the strait from London, Run 32). Each turn the unit goes as far along that way as it can,
     *  never ending a turn at sea within reach, and lands; on its landing it walks as any soldier does. With no such landing, it keeps out of reach */
    private fun headForLanding(unit: MapUnit, cityTile: Tile): Boolean {
        val peril = com.unciv.logic.automation.RulerOverrides.perilAtSea(unit)
        val landmass = cityTile.getContinent()
        val start = unit.currentTile
        // The land the city can be walked to from, by terrain and borders: a shore cut off from it by mountains or closed borders is no landing.
        // Soldiers in the way are not counted - they move; a landed host waits them out rather than putting to sea again, and an enemy come within
        // reach of it does not send it back to sea either: reach is the danger at sea, ashore it stands and fights as any soldier does
        val byLand = hashSetOf(cityTile).also { reached ->
            val queue = ArrayDeque(listOf(cityTile))
            while (queue.isNotEmpty()) for (next in queue.removeFirst().neighbors)
                if (next !in reached && next.isLand && !next.isImpassible() && next.aerialDistanceTo(cityTile) <= 10 && (next.getOwner() == null || next.canCivPassThrough(unit.civ)))
                    { reached += next; queue += next }
        }
        fun landing(tile: Tile) = tile.isLand && tile.getContinent() == landmass && tile in byLand && (tile !in peril || tile == start) &&
            tile.aerialDistanceTo(cityTile) in minDistanceFromCityToConsiderForLandingArea..10 && unit.getDamageFromTerrain(tile) <= 0 && (tile == start || unit.movement.canMoveTo(tile))
        // The ways by sea out of reach: water not within reach, and our own side's land to walk along to the shore; a landing ends a way
        val came = HashMap<Tile, Tile>().apply { put(start, start) }
        val depth = HashMap<Tile, Int>().apply { put(start, 0) }
        val queue = ArrayDeque(listOf(start))
        val landings = ArrayList<Tile>()
        while (queue.isNotEmpty()) {
            val tile = queue.removeFirst()
            if (landing(tile)) { landings += tile; continue }
            if (tile != start && tile.isLand && tile.getContinent() == landmass) continue
            for (next in tile.neighbors) {
                if (next in came || next.aerialDistanceTo(start) > 30 || next.isImpassible()) continue
                if (next.isWater && next in peril) continue
                if (next.isLand && next.getContinent() == landmass && !landing(next)) continue
                came[next] = tile; depth[next] = depth[tile]!! + 1; queue.addLast(next)
            }
        }
        // The landing it was bound for last turn, while it still stands: a host whose landings flicker as the enemy moves turns about at sea for good
        val heading = com.unciv.logic.automation.RulerOverrides.headingFor[unit.id]?.let { unit.civ.gameInfo.tileMap.getIfTileExistsOrNull(it.x, it.y) }
        val target = heading?.takeIf { it in landings } ?: landings.minWithOrNull(compareBy({ it.aerialDistanceTo(cityTile) }, { depth[it] }))
        val open = unit.movement.getDistanceToTiles().keys.filter { (it == start || unit.movement.canMoveTo(it)) && !(it.isWater && it in peril) }.toHashSet()
        val step = if (target == null) {
            // No shore out of reach: it keeps out of reach, and the court sees it hold (the posture's hostAtSea, and the log once)
            if (start in peril) open.minByOrNull { tile -> unit.civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 0 } else null
        } else {
            // The reachable tile nearest the landing by the way's own waters: a host strung out along a narrow channel stood each on the next one's
            // way and none moved (Siam, Run 32); round a tile taken by one of our own it goes alongside
            val toLanding = HashMap<Tile, Int>().apply { put(target, 0) }
            val back = ArrayDeque(listOf(target))
            while (back.isNotEmpty()) {
                val tile = back.removeFirst()
                for (next in tile.neighbors) if (next !in toLanding && next in depth) { toLanding[next] = toLanding[tile]!! + 1; back.addLast(next) }
            }
            open.filter { it in toLanding }.minByOrNull { toLanding[it]!! }?.takeIf { toLanding[it]!! < (toLanding[start] ?: Int.MAX_VALUE) }
        }
        if (target != null) com.unciv.logic.automation.RulerOverrides.headingFor[unit.id] = target.position else com.unciv.logic.automation.RulerOverrides.headingFor.remove(unit.id)
        if (target != start && unit.isEmbarked()) com.unciv.logic.automation.RulerOverrides.noteLanding(unit, target, cityTile)
        if (step != null && step != start) unit.movement.moveToTile(step)
        return true
    }

    /** Cannot take within 5 turns */
    @Readonly
    private fun cannotTakeCitySoon(
        ourUnitsAroundEnemyCity: Sequence<MapUnit>,
        city: City
    ): Boolean {
        val cityCombatant = CityCombatant(city)
        val expectedDamagePerTurn = ourUnitsAroundEnemyCity
            .sumOf { BattleDamage.calculateDamageToDefender(MapUnitCombatant(it), cityCombatant) }

        val cityHealingPerTurn = 20
        return expectedDamagePerTurn < city.health && // Cannot take immediately
            (expectedDamagePerTurn <= cityHealingPerTurn // No lasting damage
                || city.health / (expectedDamagePerTurn - cityHealingPerTurn) > 5) // Can damage, but will take more than 5 turns
    }

    private fun headToLandingGrounds(closestReachableEnemyCity: Tile, unit: MapUnit): Boolean {
        // don't head straight to the city, try to head to landing grounds -
        // this is against tha AI's brilliant plan of having everyone embarked and attacking via sea when unnecessary.
        val tileToHeadTo = closestReachableEnemyCity.getTilesInDistanceRange(minDistanceFromCityToConsiderForLandingArea..maxDistanceFromCityToConsiderForLandingArea)
            .filter { it.isLand && unit.getDamageFromTerrain(it) <= 0 } // Don't head for hurty terrain
            .sortedBy { it.aerialDistanceTo(unit.currentTile) }
            .firstOrNull { (unit.movement.canMoveTo(it) || it == unit.currentTile) && unit.movement.canReach(it) }

        if (tileToHeadTo != null) { // no need to worry, keep going as the movement alg. says
            unit.movement.headTowards(tileToHeadTo)
        }
        return true
    }

    private fun headTowardsEnemyCityLongRange(
        closestReachableEnemyCity: Tile,
        unitDistanceToTiles: PathsToTilesWithinTurn,
        unitRange: Int,
        unit: MapUnit
    ): Boolean {
        // If we're already in fire range but have no sight, hold position
        // If there IS sight, automateUnitMoves would order attacking instead of heading to city
        if (closestReachableEnemyCity.getTilesInDistance(unitRange).contains(unit.getTile()))
            return true

        val tilesInBombardRange = closestReachableEnemyCity.getTilesInDistance(2).toSet()
        val candidateTiles = unitDistanceToTiles.asSequence().filter {
            it.key.aerialDistanceTo(closestReachableEnemyCity) >= unitRange
                && it.key !in tilesInBombardRange
                && unit.getDamageFromTerrain(it.key) <= 0 // Don't set up on a mountain 
                // Avoid mountains in path because unitDistanceToTiles parameter doesn't exclude them due to getMovementToTilesAtPosition
                && unit.movement.canMoveTo(it.key)
        }

        // Sort by closest distance to target city, then by the least amount of moves needed to get into fire range
        val tileToMoveTo = candidateTiles.sortedWith(compareBy(
            { it.key.aerialDistanceTo(closestReachableEnemyCity) },
            { it.value.totalMovement }
        )).firstOrNull()

        if (tileToMoveTo != null) {
            // move into position far away enough so that city bombard or enemy units don't hurt
            unit.movement.headTowards(tileToMoveTo.key)
            return true
        }

        // return false if no tile to move to
        return false
    }
}
