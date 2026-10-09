package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import kotlin.math.roundToInt
import com.unciv.app.desktop.ruler.Prose.article
import com.unciv.app.desktop.ruler.Prose.plural
import com.unciv.logic.automation.RulerOverrides

/**
 *  A ruler's direct orders to single units. While a unit has an order the automation leaves it alone;
 *  the order is carried out during the civ's turn via [com.unciv.logic.automation.RulerOverrides.unitHandler].
 */
class RulerUnits(private val log: (Civilization, String) -> Unit) {

    /** action: move (then release), settle, fortify/garrison, hold, explore (then release), use (a great person's ability, then release), gift (march onto [to]'s land and give the unit to them) */
    class Order(val action: String, var x: Int?, var y: Int?, val ability: String? = null, val to: String? = null) {
        /** The fewest steps left to the target at any turn's start so far, and how many turns running the march has not come nearer than that,
         *  to notice an order that is going nowhere - standing still or wandering alike */
        var stepsBest: Int? = null
        var turnsStuck = 0
        /** Turns the unit has marched under this order without arriving: the order ledger's "turns so far" */
        var turnsMarching = 0
        fun toMap() = mapOf("action" to action, "x" to x, "y" to y, "ability" to ability, "to" to to, "stepsBest" to stepsBest, "turnsStuck" to turnsStuck, "turnsMarching" to turnsMarching)
    }

    /** unit id -> order */
    val orders = HashMap<Int, Order>()

    /** Set by the host: puts the path to the standing research goals' next goal into the engine's queue, which holds only the path to the first */
    var researchRefill: (Civilization) -> Unit = {}

    /** Units given away by order since the host last reckoned its losses: gone from our rolls, but neither lost nor disbanded */
    val given = HashSet<Int>()

    /** Great People and missionaries used up by the ruler's own use order since the host last reckoned its losses: spent by order, not left to the officials */
    val usedByOrder = HashSet<Int>()

    /** Units ordered re-armed that had spent their movement: re-armed at the start of our next turn, when they have it again (unit id -> the reserve then in force) */
    private val rearmNext = HashMap<Int, Int>()

    /** Set by the host: a garrison march to this city failed - the city's defender warnings are to show in full again, acknowledged or not */
    var garrisonMissed: (Civilization, Tile) -> Unit = { _, _ -> }

    /** Garrison marches that failed ("unit id@city position" -> the turn): the way reckoned at the order's giving runs through unknown ground the march
     *  may find closed, so the same soldier is not sent to the same city again for [GARRISON_RETRY] turns (Grand River's Warrior was sent twice and failed twice, Run 23) */
    private val garrisonFailed = HashMap<String, Int>()

    /** Forget everything of one game */
    fun reset() { orders.clear(); released.clear(); given.clear(); usedByOrder.clear(); rearmNext.clear(); landLeft.clear(); stillSince.clear(); garrisonFailed.clear() }

    /** Orders for units that no longer exist (a soldier killed on the march; re-arming keeps a unit's number, so its order stays): forgotten, so a dead man never counts as "on the way" */
    fun forgetTheDead(game: com.unciv.logic.GameInfo) {
        val alive = game.civilizations.asSequence().flatMap { it.units.getCivUnits() }.map { it.id }.toHashSet()
        orders.keys.retainAll(alive); released.retainAll(alive); rearmNext.keys.retainAll(alive)
        garrisonFailed.values.removeIf { game.turns - it >= GARRISON_RETRY } // old enough to be tried again, so no longer kept
    }

    /** Did this soldier's garrison march to that city fail within the last [GARRISON_RETRY] turns? The standing garrison order passes it over then */
    fun failedLately(unit: MapUnit, city: Tile) = garrisonFailed[garrisonKey(unit.id, city)]?.let { unit.civ.gameInfo.turns - it < GARRISON_RETRY } == true
    private fun garrisonKey(id: Int, city: Tile) = "$id@${cityKey(city)}"
    private fun cityKey(city: Tile) = "${city.position.x.toInt()},${city.position.y.toInt()}"

    /** The failed garrison march to this city still remembered, for its defender warning: "our Warrior #53, sent to garrison it, could not reach it (turn 56)"; null if none */
    fun garrisonMiss(civ: Civilization, city: Tile): String? {
        val (key, turn) = garrisonFailed.entries.filter { it.key.endsWith("@${cityKey(city)}") }.maxByOrNull { it.value }?.let { it.key to it.value } ?: return null
        val id = key.substringBefore("@").toInt()
        val name = civ.gameInfo.civilizations.asSequence().flatMap { it.units.getCivUnits() }.firstOrNull { it.id == id }?.name ?: "soldier"
        return "our $name #$id, sent to garrison it, could not reach it (turn $turn)"
    }

    private fun noteGarrisonFailure(unit: MapUnit, order: Order, target: Tile) {
        if (order.action != "garrison") return
        garrisonFailed[garrisonKey(unit.id, target)] = unit.civ.gameInfo.turns
        garrisonMissed(unit.civ, target)
    }

    /** A hold that only ever meant "a Great Person awaits orders" must not bind a soldier: the old name check froze Great War Infantry and Bombers,
     *  and some of those have since been re-armed under the same id with a plainer name - so the test is the log line the bug wrote, never the name alone. */
    fun dropMistakenHolds(game: com.unciv.logic.GameInfo, chancery: Collection<List<Map<String, Any?>>>) {
        val frozenByTheBug = chancery.asSequence().flatten().map { it["text"] as String }
            .filter { "awaits orders" in it && (it.startsWith("A Great War ") || it.startsWith("A Great Bomber")) }
            .mapNotNull { Regex("#(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.toHashSet()
        orders.entries.removeIf { (id, order) -> order.action == "hold" && id in frozenByTheBug
            && game.civilizations.asSequence().flatMap { it.units.getCivUnits() }.firstOrNull { it.id == id }?.isGreatPerson() == false }
    }

    fun load(json: JsonValue?) {
        orders.clear()
        json?.forEach { orders[it.name.toInt()] = Order(it.getString("action"), it.get("x")?.takeUnless { v -> v.isNull }?.asInt(), it.get("y")?.takeUnless { v -> v.isNull }?.asInt(), it.get("ability")?.takeUnless { v -> v.isNull }?.asString(), it.getString("to", null)).apply {
            stepsBest = it.get("stepsBest")?.takeUnless { v -> v.isNull }?.asInt(); turnsStuck = it.getInt("turnsStuck", 0); turnsMarching = it.getInt("turnsMarching", 0) } }
    }

    fun toMap() = orders.mapKeys { it.key.toString() }.mapValues { it.value.toMap() }

    /** The rest of what a restart would lose: which Great People were handed to the officials, and how long each unit has stood where it stands */
    fun stateToMap() = mapOf(
        "released" to released.toList(),
        "embarkedSince" to embarkedSince.mapKeys { it.key.toString() },
        "rearmNext" to rearmNext.mapKeys { it.key.toString() },
        "garrisonFailed" to garrisonFailed,
        "stillSince" to stillSince.mapKeys { it.key.toString() }.mapValues { mapOf("spot" to it.value.first, "turn" to it.value.second) })

    fun loadState(json: JsonValue?) {
        released.clear(); stillSince.clear(); rearmNext.clear(); garrisonFailed.clear()
        json?.get("released")?.forEach { released += it.asInt() }
        json?.get("rearmNext")?.forEach { rearmNext[it.name.toInt()] = it.asInt() }
        json?.get("garrisonFailed")?.forEach { garrisonFailed[it.name] = it.asInt() }
        embarkedSince.clear(); json?.get("embarkedSince")?.forEach { embarkedSince[it.name.toInt()] = it.asInt() }
        json?.get("stillSince")?.forEach { stillSince[it.name.toInt()] = Pair(it.getString("spot"), it.getInt("turn")) }
    }

    /** Called by the automation for every unit of a ruled civ. True = we handled it, leave it alone. */
    /** Great People the ruler has handed back to the officials */
    val released = HashSet<Int>()

    /** How many turns a unit has stood on the same tile - reckoned each turn by the host's bookkeeping */
    private val stillSince = HashMap<Int, Pair<String, Int>>()
    /** Since which turn each land unit of ours has been at sea, embarked */
    private val embarkedSince = HashMap<Int, Int>()
    fun noteStanding(civ: Civilization) {
        val turn = civ.gameInfo.turns
        for (unit in civ.units.getCivUnits()) {
            if (unit.isEmbarked()) embarkedSince.putIfAbsent(unit.id, turn) else embarkedSince.remove(unit.id)
            val spot = unit.getTile().position.toString()
            val was = stillSince[unit.id]
            if (was == null || was.first != spot) stillSince[unit.id] = Pair(spot, turn)
        }
        stillSince.keys.retainAll(civ.gameInfo.civilizations.flatMap { c -> c.units.getCivUnits().map { it.id } }.toSet())
    }
    /** How many turns, this one counted, a land unit of ours has been at sea */
    fun turnsAtSea(unit: MapUnit) = embarkedSince[unit.id]?.let { unit.civ.gameInfo.turns - it + 1 } ?: 0
    fun turnsStill(unit: MapUnit) = stillSince[unit.id]?.let { unit.civ.gameInfo.turns - it.second } ?: 0

    fun handle(unit: MapUnit): Boolean {
        // A Great Person is the ruler's to spend: the officials never touch one unless it was released to them.
        // Caught here, in the automation's own loop, because one born at the start of a turn would otherwise be spent within it.
        if (unit.id !in orders && unit.id !in released && unit.isGreatPerson()) {
            // One born in the open waits in the nearest town of ours rather than in the field; a general or an admiral belongs beside the host.
            // One born in a town another of ours already waits in, or is bound for, moves on too: two never share a place
            val shelter = if (unit.isGreatPersonOfType("War") || unit.getTile().let { it.isCityCenter() && it.getOwner() == unit.civ && !claimed(unit, it) }) null else shelterFor(unit)
            orders[unit.id] = Order("hold", shelter?.position?.x?.toInt(), shelter?.position?.y?.toInt())
            log(unit.civ, "A ${unit.name} #${unit.id} ${RulerCampaigns.atPlaceOf(unit.civ, unit.getTile())} awaits orders (use, move, or release to the officials)" +
                // What it can do here and now, in the order's own words for its abilities (and what the rest need)
                "; ${abilitiesHere(unit)}" +
                (shelter?.let { if (it.isCityCenter()) "; meanwhile it walks to ${it.getCity()!!.name} and waits there" else "; meanwhile it waits beside ${it.getCity()!!.name}, whose town another of ours holds" } ?: "") + ".")
        }
        // A unit set exploring before the host explored it itself (older saves) carries the explore action and no order: it is the ruler's explorer still
        val order = orders[unit.id] ?: (if (unit.isExploring() && unit.id !in released) Order("explore", null, null).also { orders[unit.id] = it } else return false)
        if (order.action == "gift") return withCityStatePassage(unit, recipientOf(unit.civ, order)) { checkGift(unit, order) ?: carryOut(unit, order) }
        return carryOut(unit, order)
    }

    private fun carryOut(unit: MapUnit, order: Order): Boolean {
        val civ = unit.civ
        val target = order.x?.let { x -> order.y?.let { y -> civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) } }

        if (target != null && order.action == "settle") settleProblem(civ, target)?.let { problem ->
            // The site went bad on the way (someone settled nearby): say so at once, do not walk on
            log(civ, "Our Settler #${unit.id} was bound for ${RulerCampaigns.placeOf(civ, target)}, but the site is no longer usable: $problem${nearestSiteHint(civ, unit, target)}. The order is dropped; give it a new site.")
            orders.remove(unit.id)
            return true
        }
        // A garrison order does not turn a stronger soldier out of the city for a weaker one: the order is dropped, the stronger stays
        if (target != null && unit.getTile() != target && order.action == "garrison") target.militaryUnit?.takeIf { it.civ == civ && RulerOverrides.might(it) > RulerOverrides.might(unit) }?.let { holder ->
            log(civ, "Our ${unit.name} was to garrison ${target.getCity()?.name}, but our ${holder.name} holding it is the stronger, so it stays and the order is dropped.")
            drop(unit)
            return false
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
            if (unit.currentMovement <= 0f) return true
            val beside = target.neighbors.filter { canMarch(unit, it) }.minByOrNull { it.aerialDistanceTo(unit.getTile()) }
            // canMarch reckons with full movement; with less left the step may not open this turn - then it waits for the next
            if (beside != null) { if (beside != unit.getTile()) try { unit.movement.headTowards(beside) } catch (_: UnitMovement.UnreachableDestinationException) {}; return true }
        }
        // A trade mission is carried out from any tile of the city-state's land (the engine's rule - UnitActionsGreatPerson), and its city itself no foreign
        // unit may enter: standing on their land counts as arrived (six of six missions were dropped at the gate, Run 21)
        if (target != null && unit.getTile() != target && tradeMissionLand(unit, order, target)) {
            log(civ, "Our ${unit.name} arrived on ${target.getOwner()!!.civName}'s land, ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}.")
            order.x = unit.getTile().position.x.toInt(); order.y = unit.getTile().position.y.toInt()
            return carryOut(unit, order)
        }
        if (target != null && unit.getTile() != target) {
            // The escort and upgrade routines may have moved this soldier already this turn: no movement left is not "cannot reach"
            if (unit.currentMovement <= 0f) return true
            if (order.action == "hold" && unit.isGreatPerson() && (!canMarch(unit, target) || order.turnsStuck >= 2)) { order.x = null; order.y = null; return true }
            // A held one whose waiting place another of ours has taken meanwhile looks for another (Mongolia's two prophets swapped places every turn from T239)
            if (order.action == "hold" && unit.isGreatPerson() && claimed(unit, target)) {
                val other = shelterFor(unit)
                order.x = other?.position?.x?.toInt(); order.y = other?.position?.y?.toInt(); order.turnsStuck = 0; order.stepsBest = null
                if (other == null || other == unit.getTile()) return true
                return carryOut(unit, order)
            }
            val way = pathTo(unit, target)
            if (way.isEmpty()) {
                dropped(unit, "Our ${unit.name} cannot reach ${RulerCampaigns.placeOf(civ, target)}")
                return false
            }
            // A civilian whose target holds one of our own civilians changes places with it: a Great Person on its way to use itself, or a Settler held or
            // sent where our Worker stands (China's waited three turns beside its own Worker and lost its order, Run 19). A held Great Person never gets here: it found another place above
            if (unit.isCivilian() && target.civilianUnit?.civ == civ && unit.getTile().aerialDistanceTo(target) == 1 && unit.movement.canUnitSwapTo(target)) {
                unit.movement.swapMoveToTile(target)
            } else try { unit.movement.headTowards(target) } catch (_: UnitMovement.UnreachableDestinationException) {} // not moved: counted as stuck below
            if (tradeMissionLand(unit, order, target)) {
                log(civ, "Our ${unit.name} arrived on ${target.getOwner()!!.civName}'s land, ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}.")
                order.x = unit.getTile().position.x.toInt(); order.y = unit.getTile().position.y.toInt()
                return carryOut(unit, order)
            }
            if (unit.getTile() != target) {
                // Nearer is fewer steps left at the turn's start than at any before: a march that moves but comes no nearer - the way reckoned through unknown ground
                // closing as it is explored (Babylon's Warrior wandered at sea for 18 turns, Run 22) - is as stuck as one that stands still
                val best = order.stepsBest
                order.turnsMarching++
                order.turnsStuck = if (best != null && way.size >= best) order.turnsStuck + 1 else 0
                order.stepsBest = if (best == null) way.size else minOf(best, way.size)
                if (order.turnsStuck >= 3) {
                    val blocker = target.militaryUnit?.takeIf { !unit.isCivilian() } ?: target.civilianUnit?.takeIf { unit.isCivilian() }
                    // One of our own holds the place - a garrison's relief, or a civilian bound where our Worker stands: it waits beside it and swaps in the turn it can, instead of giving up
                    if (blocker != null && blocker.civ == civ && (order.action == "garrison" || unit.isCivilian())) {
                        if (order.turnsStuck == 3) log(civ, "Our ${unit.name} waits beside ${RulerCampaigns.placeOf(civ, target)}: our ${blocker.name} holds it and cannot change places yet. It swaps in as soon as it can.")
                        return true
                    }
                    dropped(unit, "Our ${unit.name} has been stuck for ${order.turnsStuck} turns on its way to ${RulerCampaigns.placeOf(civ, target)}, no nearer than it was" +
                        (blocker?.let { " - the tile is held by ${it.civ.civName}'s ${it.name}" } ?: seaBlock(unit, target)?.let { " - $it" } ?: " - no way to it opens"))
                    return false
                }
                return true // still on the way
            }
            log(civ, "Our ${unit.name} arrived ${RulerCampaigns.atPlaceOf(civ, target)}.")
        }

        return when (order.action) {
            "settle" -> {
                // Founding needs movement left; a settler that walked the last stretch this turn founds next turn
                if (unit.currentMovement <= 0f) return true
                val broken = promiseBroken(civ, unit.getTile(), "settle") // judged before the founding, which the other side's flags answer to
                if (UnitActions.invokeUnitAction(unit, UnitActionType.FoundCity)) {
                    log(civ, "Our Settler founded ${civ.cities.last().name}${broken.orEmpty()}.")
                    orders.remove(unit.id)
                    true
                } else {
                    log(civ, "Our Settler #${unit.id} cannot found a city ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}: ${settleProblem(civ, unit.getTile()) ?: "the ground will not take a city"}${nearestSiteHint(civ, unit, unit.getTile())}. The order is dropped; give it a new site.")
                    orders.remove(unit.id)
                    false
                }
            }
            "fortify", "garrison" -> { unit.fortifyIfCan(); true }
            "use" -> {
                // An ability needs movement left; a Great Person that walked here this turn uses it next turn
                if (unit.currentMovement <= 0f) return true
                // A Great Person can usually be spent more than one way; the ruler says which, or gets the first that works
                val wanted = ABILITIES.filter { order.ability == null || it in abilitiesNamed(order.ability) }
                // The first Great Scientist's boost finished the only technology the engine's queue held, and the second found no research under way (Run 19): the goals' next path is set before the boost
                if (UnitActionType.HurryResearch in wanted && civ.tech.currentTechnologyName() == null) researchRefill(civ)
                val used = wanted.firstOrNull { UnitActions.invokeUnitAction(unit, it) }
                if (used != null) { usedByOrder += unit.id; log(civ, "Our ${unit.name} used its ability (${abilityWord(used)}) ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}${if (used == UnitActionType.SpreadReligion) promiseBroken(civ, unit.getTile(), "religion").orEmpty() else ""}.") }
                else {
                    order.turnsStuck += 1
                    if (order.turnsStuck == 1) log(civ, "Our ${unit.name} #${unit.id} could not use its ability ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}${order.ability?.let { " ($it)" } ?: ""}: ${abilitiesHere(unit)}. Name one with the order and stand where it applies. It tries again for two more turns, then the order is dropped.")
                    if (order.turnsStuck < 3) return true
                    dropped(unit, "Our ${unit.name} still could not use its ability ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}")
                    return false // taken back this turn, as said
                }
                orders.remove(unit.id)
                true
            }
            "gift" -> {
                // Giving needs movement left, as founding does: a soldier that marched in this turn is given next turn
                if (unit.currentMovement <= 0f) return true
                val recipient = recipientOf(civ, order)!!
                val name = unit.name; val id = unit.id; val here = RulerCampaigns.atPlaceOf(civ, unit.getTile())
                val influence = { recipient.getDiplomacyManager(civ)?.getInfluence() ?: 0f }
                val before = influence()
                orders.remove(id)
                if (UnitActions.invokeUnitAction(unit, UnitActionType.GiftUnit)) {
                    released -= id; given += id
                    val regard = if (recipient.isCityState) ", whose regard for us rose (influence +${(influence() - before).roundToInt()}, now ${influence().toInt()})" else ""
                    log(civ, "By your order: our $name was given to ${recipient.civName} $here$regard.")
                    if (!recipient.isCityState) log(recipient, "${civ.civName} gave us their $name, now our $name #$id ${RulerCampaigns.atPlaceOf(recipient, unit.getTile())}.") // its number: a new unit to order
                    true
                } else {
                    dropped(unit, "Our $name could not be given to ${recipient.civName} $here: ${giftProblem(civ, unit, recipient) ?: "they would not take it there"}")
                    false
                }
            }
            "hold" -> true
            "disband" -> { log(civ, "By your order: our ${unit.name} was disbanded."); orders.remove(unit.id); unit.disband(); true }
            // Explored by our own hand each turn: left to the officials, an AI-played realm's routine never reads the unit's explore action, and a ship
            // sent to explore did whatever the officials had in mind instead (Babylon's Caravel sat 86 turns, Run 18). Done when nothing in reach is unexplored
            "explore" -> {
                unit.action = UnitActionType.Explore.value
                if (!unit.hasMovement()) return true
                com.unciv.logic.automation.unit.UnitAutomation.automatedExplore(unit)
                if (!unit.isDestroyed && !unit.isExploring()) {
                    orders.remove(unit.id)
                    civ.notifications.removeAll { it.text == "${unit.shortDisplayName()} finished exploring." } // the engine's own word for it: ours below says more
                    log(civ, "Our ${unit.name} has explored all it can reach ${if (unit.baseUnit.isLandUnit) "on foot" else "by sea"} and is back under the officials, ${RulerCampaigns.atPlaceOf(civ, unit.getTile())}." +
                        (if (unit.baseUnit.isLandUnit) " Lands beyond the water are found only by ships." else ""))
                }
                true
            }
            else -> { orders.remove(unit.id); true } // "move": arrived, released to the officials from next turn
        }
    }

    /** Unciv lets a human player's units walk through any city-state's land, but not an AI-played realm's - which ours are, to the engine -
     *  so a soldier sent as a gift to a city-state could never set foot on it. It walks in as a human player's would, for the while
     *  (the engine's own path-finder flips its cache flags the same way); the path cache is cleared on both sides, so no path found with it leaks out. */
    private fun <T> withCityStatePassage(unit: MapUnit, recipient: Civilization?, block: () -> T): T {
        if (recipient?.isCityState != true || unit.cache.canEnterCityStates) return block()
        unit.cache.canEnterCityStates = true; unit.movement.clearPathfindingCache()
        try { return block() } finally { unit.cache.canEnterCityStates = false; unit.movement.clearPathfindingCache() }
    }


    private fun recipientOf(civ: Civilization, order: Order) = civ.gameInfo.civilizations.firstOrNull { it.civName == order.to }

    /** A gift order re-checked each turn, as a settler's site is: war may have broken out or their borders moved on the way.
     *  Returns null to carry on (the target tile updated if need be), or what [handle] should return. */
    private fun checkGift(unit: MapUnit, order: Order): Boolean? {
        val civ = unit.civ
        val recipient = recipientOf(civ, order)?.takeIf { it.isAlive() }
        val problem = if (recipient == null) "${order.to} is gone" else giftProblem(civ, unit, recipient)
        if (problem != null || recipient == null) {
            dropped(unit, "Our ${unit.name} #${unit.id} cannot be given to ${order.to}: $problem")
            return false
        }
        val target = order.x?.let { x -> order.y?.let { y -> civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) } }
        // Standing on their land already (crossed into it on the way): given here, no need to walk on
        val tile = unit.getTile().takeIf { canBeGivenOn(unit, it, recipient) }
            ?: target?.takeIf { canBeGivenOn(unit, it, recipient) && canMarch(unit, it) }
            ?: giftTile(unit, recipient)
        if (tile == null) {
            dropped(unit, "${recipient.civName}'s land is now beyond the reach of our ${unit.name} #${unit.id}")
            return false
        }
        if (tile != target) {
            if (target != null && tile != unit.getTile()) log(civ, "The ground our ${unit.name} #${unit.id} was marching to " +
                (if (target.getOwner() != recipient) "is no longer ${recipient.civName}'s" else "cannot take it now") + "; it makes for ${RulerCampaigns.placeOf(civ, tile)} instead, to be given to ${recipient.civName} there.")
            order.x = tile.position.x.toInt(); order.y = tile.position.y.toInt()
            order.turnsStuck = 0; order.stepsBest = null
        }
        return null
    }

    /** Why this unit cannot be given to that power at all, wherever it stands; null if it can */
    private fun giftProblem(civ: Civilization, unit: MapUnit, recipient: Civilization): String? = when {
        recipient == civ -> "a unit cannot be given to our own realm"
        !civ.knows(recipient) -> "we have not met ${recipient.civName}"
        civ.isAtWarWith(recipient) -> "we are at war with ${recipient.civName}"
        unit.isTransported -> "it is carried aboard another unit"
        // The engine would take it and destroy it: a city-state has no use for Great People
        recipient.isCityState && unit.isGreatPerson() -> "a Great Person cannot be gifted to a city-state"
        recipient.isCityState && !unit.isMilitary() && unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)
            .none { unit.matchesFilter(it.params[1]) } -> "a city-state takes only soldiers"
        else -> null
    }

    /** Could the unit be handed over standing on this tile: the recipient's own ground, friendly to us if they are a realm, and free for a unit of its kind */
    private fun canBeGivenOn(unit: MapUnit, tile: Tile, recipient: Civilization): Boolean {
        if (tile.getOwner() != recipient) return false
        if (!recipient.isCityState && !tile.isFriendlyTerritory(unit.civ)) return false
        val occupant = if (unit.isCivilian()) tile.civilianUnit else tile.militaryUnit
        return (occupant == null || occupant == unit) && (tile == unit.getTile() || unit.movement.canMoveTo(tile))
    }

    /** The nearest tile of the recipient's that this unit can march to and be given on */
    private fun giftTile(unit: MapUnit, recipient: Civilization): Tile? =
        recipient.cities.asSequence().flatMap { it.getTiles() }.filter { canBeGivenOn(unit, it, recipient) }
            .sortedBy { it.aerialDistanceTo(unit.getTile()) }.take(20) // path-finding is dear; twenty of the nearest is plenty
            .firstOrNull { canMarch(unit, it) }

    /** Is this tile held, or waited for, by another civilian of ours under a hold order: a second Great Person must not be sent there */
    private fun claimed(unit: MapUnit, tile: Tile) = (tile.civilianUnit?.takeIf { it != unit && it.civ == unit.civ } != null) ||
        orders.any { (id, order) -> id != unit.id && order.action == "hold" && order.x == tile.position.x.toInt() && order.y == tile.position.y.toInt() }

    /** The nearest town of ours a held Great Person can walk to and wait in - or, with every town near taken, the nearest free tile of ours beside one */
    private fun shelterFor(unit: MapUnit): Tile? = unit.civ.cities.map { it.getCenterTile() }.filter { !claimed(unit, it) }
        .sortedBy { it.aerialDistanceTo(unit.getTile()) }.take(8).firstOrNull { canMarch(unit, it) }
        ?: unit.civ.cities.map { it.getCenterTile() }.sortedBy { it.aerialDistanceTo(unit.getTile()) }.take(4).flatMap { it.neighbors.toList() }
            .filter { it.isLand && !it.isImpassible() && it.getOwner() == unit.civ && it.civilianUnit == null && !claimed(unit, it) }
            .sortedBy { it.aerialDistanceTo(unit.getTile()) }.take(8).firstOrNull { canMarch(unit, it) }

    /** An order that could not be carried out at all - the engine refused a step of it: dropped, said in the chancery, and the officials take the unit */
    fun dropAfterFailure(unit: MapUnit) {
        val order = orders[unit.id] ?: return
        dropped(unit, "Our ${unit.name} #${unit.id} could not carry out its ${order.action} order${order.x?.let { " for ${where(unit.civ, unit.civ.gameInfo.tileMap[it, order.y!!])}" } ?: ""} from ${where(unit.civ, unit.getTile())}: the way would not open")
    }

    /** An order given up: the chancery told why, a garrison that could not reach its city remembered for the warnings, and the officials take the unit back */
    private fun dropped(unit: MapUnit, why: String) {
        val order = orders[unit.id] ?: return
        log(unit.civ, "$why. The order is dropped and the officials take it back.")
        order.x?.let { x -> order.y?.let { y -> unit.civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) } }?.let { noteGarrisonFailure(unit, order, it) }
        drop(unit)
    }

    /** An order dropped for good: a Great Person goes to the officials with it, or the hold at the top of [handle] would take it back next turn and the ruler would be asked again */
    private fun drop(unit: MapUnit) {
        orders.remove(unit.id)
        if (unit.isGreatPerson()) released += unit.id
    }

    /** The order ledger's part of the units: marches under way (whither, turns so far) and re-armings put off to our next turn, in the log's words */
    fun awaiting(civ: Civilization): List<String> {
        val marching = setOf("move", "settle", "garrison", "use", "gift")
        return orders.entries.sortedBy { it.key }.mapNotNull { (id, order) ->
            val unit = civ.units.getUnitById(id) ?: return@mapNotNull null
            val target = order.x?.let { x -> order.y?.let { y -> civ.gameInfo.tileMap.getIfTileExistsOrNull(x, y) } }
            if (order.action !in marching || target == null || unit.getTile() == target) return@mapNotNull null
            val purpose = when (order.action) { "settle" -> " to found a city"; "garrison" -> " to garrison it"; "use" -> " to use its ability${order.ability?.let { " ($it)" } ?: ""}"; "gift" -> " to be given to ${order.to}"; else -> "" }
            "${unit.name} #$id is on its way to ${where(civ, target)}$purpose, ${order.turnsMarching} turn${if (order.turnsMarching == 1) "" else "s"} so far" +
                (if (order.turnsStuck > 0) ", no nearer for ${order.turnsStuck}" else "") + "."
        } + rearmNext.keys.mapNotNull { id -> civ.units.getUnitById(id)?.let { "${it.name} #$id had marched when its re-arming was ordered: it is re-armed at the start of our next turn, if the treasury still allows." } }
    }

    /** At the start of our turn, before the officials spend a coin: the re-armings ordered for units that had marched. Re-arming keeps a unit's number */
    fun rearmDue(civ: Civilization) {
        for ((id, reserve) in rearmNext.filterKeys { civ.units.getUnitById(it) != null }) {
            rearmNext.remove(id)
            val (ok, told) = rearm(civ, id, reserve, deferIfSpent = false)
            if (!ok) log(civ, "The re-arming you ordered could not be done: $told.")
        }
    }

    /** The newer arms we know how to make for this soldier, whatever they cost or need and wherever it stands: the one test the re-arm warning and
     *  the rearm order share (the order's own old test also asked that it stand on our land, so "order rearm" was said of soldiers abroad it then refused) */
    fun newerArms(unit: MapUnit): List<com.unciv.models.ruleset.unit.BaseUnit> = if (!unit.isMilitary()) emptyList() else unit.baseUnit.getUpgradeUnits(unit.cache.state)
        .mapNotNull { unit.civ.gameInfo.ruleset.units[it] }.map { unit.civ.getEquivalentUnit(it) }.filter { unit.upgrade.canUpgrade(it, ignoreResources = true) }.toList()

    /** Our soldiers that newer arms we know could replace (whether or not we can pay, have the resources, or they stand where it can be done now) */
    fun rearmable(civ: Civilization) = civ.units.getCivUnits().filter { newerArms(it).isNotEmpty() }.toList()

    /** Re-arm one of our units by the ruler's hand, paid from the treasury above [reserve]: (done, what to tell). A unit that has spent its movement
     *  is re-armed at the start of our next turn instead, as a player's would be */
    fun rearm(civ: Civilization, id: Int, reserve: Int, deferIfSpent: Boolean = true): Pair<Boolean, String> {
        val unit = civ.units.getUnitById(id) ?: return Pair(false, "No unit of ours is #$id - see the briefing's unit list")
        val label = "${unit.name} #$id"
        val ruleset = civ.gameInfo.ruleset
        val options = UnitActionsUpgrade.getUpgradeActions(unit).filterIsInstance<UpgradeUnitAction>().toList()
        if (options.isEmpty()) {
            val next = unit.baseUnit.getUpgradeUnits(unit.cache.state).mapNotNull { ruleset.units[it] }.map { civ.getEquivalentUnit(it) }.toList()
            return Pair(false, "$label cannot be re-armed: " + when {
                next.isEmpty() -> "there are no newer arms for ${article(unit.name)}"
                unit.getTile().getOwner() != civ -> "it stands outside our borders, and can be re-armed only on our own land"
                else -> next.first().let { to -> "we do not yet know how to make ${article(to.name)}" + (to.requiredTech?.let { " (it needs $it)" } ?: "") }
            })
        }
        // As the officials choose: the cheapest of the newer arms that can be had
        val usable = options.filter { unit.upgrade.canUpgrade(it.unitToUpgradeTo) }
        if (usable.isEmpty()) {
            val to = options.first().unitToUpgradeTo
            val lacking = to.getResourceRequirementsPerTurn(unit.cache.state).keys.filter { civ.getResourceAmount(it) < 1 }
            return Pair(false, "$label cannot be re-armed as ${article(to.name)}: it needs ${lacking.ifEmpty { to.getResourceRequirementsPerTurn(unit.cache.state).keys.toList() }.joinToString(" and ")}, which we lack")
        }
        val choice = usable.minBy { it.goldCostOfUpgrade }
        val to = choice.unitToUpgradeTo.name
        val cost = choice.goldCostOfUpgrade
        if (civ.gold - reserve < cost) return Pair(false, "$label cannot be re-armed as ${article(to)}: it costs $cost gold, and only ${(civ.gold - reserve).coerceAtLeast(0)} lies above the reserve")
        if (unit.isEmbarked()) return Pair(false, "$label is at sea, embarked; it can be re-armed once ashore on our land")
        if (!unit.hasMovement()) {
            if (!deferIfSpent) return Pair(false, "$label has no movement left to be re-armed")
            rearmNext[id] = reserve
            return Pair(true, "$label has marched this turn: it is re-armed as ${article(to)} at the start of our next turn, for about $cost gold if the treasury still allows")
        }
        val action = choice.action ?: return Pair(false, "$label cannot be re-armed as ${article(to)} here and now")
        action.invoke()
        val now = civ.units.getUnitById(id)
        if (now == null || now.name == unit.name) return Pair(false, "$label could not be re-armed as ${article(to)}: the new arms found no room to stand")
        log(civ, "By your order: re-armed our ${unit.name} #$id as $to for $cost gold.")
        return Pair(true, "$label re-armed as $to for $cost gold")
    }

    /** Give an order from the API: {unit: id, action, x?, y?, city?} */
    fun give(civ: Civilization, json: JsonValue): String {
        val unitId = json.getInt("unit")
        val unit = civ.units.getUnitById(unitId) ?: throw IllegalArgumentException("No unit with id $unitId - see the briefing's unit list")
        val action = json.getString("action", "move")
        if (action !in UNIT_ACTIONS) throw IllegalArgumentException("Unknown unit action $action; use ${UNIT_ACTIONS.joinToString()}")
        if (action == "disband") { orders.remove(unitId); val name = unit.name; unit.disband(); log(civ, "By your order: our $name #$unitId was disbanded."); return "$name #$unitId disbanded." }
        if (action == "release") { orders.remove(unitId); released += unitId; if (unit.isExploring()) unit.action = null; return "${unit.name} #$unitId is back under the officials." }
        if (action == "settle" && !unit.hasUnique(UniqueType.FoundCity)) throw IllegalArgumentException("${unit.name} cannot found cities")
        if (action == "explore" && unit.isCivilian()) throw IllegalArgumentException("Civilians cannot explore; send a military unit")
        if (action == "gift") return giveGift(civ, unit, json)

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
        // A way this unit does not have at all (a Great Prophet has no golden age) is refused now, not tried for three turns
        json.get("ability")?.takeUnless { it.isNull }?.asString()?.lowercase()?.trim()?.let { ability -> if (action == "use" && ABILITY_UNIQUES[ability]?.none { unit.hasUnique(it) } == true)
            throw IllegalArgumentException("${unit.name} #$unitId has no '$ability' to use; the ways it has: ${ABILITY_UNIQUES.filter { (_, uniques) -> uniques.any { unit.hasUnique(it) } }.keys.joinToString().ifEmpty { "none" }}") }
        if (action == "use") when (json.get("ability")?.takeUnless { it.isNull }?.asString()?.lowercase()?.trim()) {
            "trade mission" -> if (target.getOwner()?.isCityState != true) throw IllegalArgumentException("A trade mission needs a city-state's city, or a tile of its land, as its target; ${where(civ, target)} is neither")
            "hurry production" -> target.getCity()?.let { city -> when (val current = city.cityConstructions.getCurrentConstruction()) {
                // A Great Engineer hurries a building or a wonder (UnitActionsGreatPerson), never a unit - spaceship parts are units - nor what the rules bar
                !is com.unciv.models.ruleset.INonPerpetualConstruction -> throw IllegalArgumentException("${city.name} is building nothing that can be hurried right now (its queue is empty or set to Science/Gold); queue something first")
                !is com.unciv.models.ruleset.Building -> throw IllegalArgumentException("${city.name} is building ${current.name}, a unit: hurrying production speeds only a building or a wonder; put one first in ${city.name}'s queue")
                else -> if (current.hasUnique(UniqueType.CannotBeHurried)) throw IllegalArgumentException("${current.name} cannot be hurried - the rules of this world forbid it; put another building or wonder first in ${city.name}'s queue")
            } }
        }
        if (action == "garrison" && !target.isCityCenter()) throw IllegalArgumentException("garrison needs a city")
        if (action == "garrison" && unit.isCivilian()) throw IllegalArgumentException("Only soldiers can garrison")
        if (action == "garrison") target.militaryUnit?.takeIf { it != unit && it.civ != civ }?.let { holder ->
            throw IllegalArgumentException("${target.getCity()!!.name} is held by ${holder.civ.civName}'s ${holder.name}")
        }
        val willSwap = action == "garrison" && target.militaryUnit?.civ == civ
        val occupant = (if (unit.isCivilian()) target.civilianUnit else target.militaryUnit)?.takeIf { it != unit }
        val civilianSwap = unit.isCivilian() && occupant?.civ == civ // a Great Person bound for a city tile our Worker holds: they change places on arrival
        if (occupant != null && !willSwap && !civilianSwap) throw IllegalArgumentException(
            "${where(civ, target)} is already held by ${if (occupant.civ == civ) "our" else occupant.civ.civName + "'s"} ${occupant.name} #${occupant.id}; two ${if (unit.isCivilian()) "civilians" else "soldiers"} cannot share a tile. " +
            (if (occupant.civ == civ) "Use garrison to swap places with it, pick a tile beside it, or leave it be - the place is already covered." else "Pick another tile."))
        // Orders are given between turns, when a unit has usually spent its movement - and Unciv finds no path at all for a unit with none left
        val path = if (target == unit.getTile()) listOf() else pathTo(unit, target)
        // A way closed only because it puts to sea within an enemy's reach (RulerOverrides.underEnemyShips) is refused in those words, not as no way at all
        if (target != unit.getTile() && !willSwap && path.isEmpty()) seaBlock(unit, target)?.let { throw IllegalArgumentException("${unit.name} cannot reach ${where(civ, target)}: $it") }
        if (target != unit.getTile() && !willSwap && path.isEmpty()) throw IllegalArgumentException("${unit.name} cannot reach ${where(civ, target)} (water, mountains or foreign borders in the way)" + if (action == "settle") nearestSiteHint(civ, unit, target) else "")
        val ability = json.get("ability")?.takeUnless { it.isNull }?.asString()
        if (ability != null && abilitiesNamed(ability).isEmpty()) throw IllegalArgumentException("Unknown ability '$ability'; use one of: ${ABILITY_WORDS.keys.joinToString()}")
        orders[unitId] = Order(action, x, y, ability)
        released -= unitId // an order given is the ruler's hand back on it
        val turns = if (target == unit.getTile()) 0 else kotlin.math.ceil(path.size / unit.getMaxMovement().coerceAtLeast(1).toFloat()).toInt()
        // A ruler may break the realm's word, but never unknowing: the reply says which promise the order breaks
        val broken = when { action == "settle" -> promiseBroken(civ, target, "settle"); action == "use" && (ability == null || "religion" in ability.lowercase()) -> promiseBroken(civ, target, "religion"); else -> null }
        if (action == "explore") return "${unit.name} #$unitId will explore" + (if (turns > 0) " from ${where(civ, target)}, about $turns turns away" else "") + ", turn after turn, until nothing within its reach is left unexplored; then it is back under the officials. " +
            (if (unit.baseUnit.isLandUnit) "It explores the land it can walk: it does not cross the sea - the sea is for ships." else "A ship explores the sea and the coasts it can reach.")
        return "${unit.name} #$unitId will $action at ${where(civ, target)}" + (if (turns > 0) ", about $turns turns away" else "") + broken.orEmpty()
    }

    /** The promise an order at this tile would break, as a clause for the reply and the log: a city founded within six tiles of a power that holds our
     *  word not to settle near them, or our religion spread into a city of one that holds our word to spare them. The officials keep these promises on
     *  their own (RulerOverrides.keepsPromise); the ruler's own order stands, said plainly. Null when no promise is touched */
    fun promiseBroken(civ: Civilization, tile: Tile, what: String): String? {
        val (flag, deed) = when (what) {
            "settle" -> DiplomacyFlags.AgreedToNotSettleNearUs to "settle near them"
            else -> DiplomacyFlags.AgreedToNotSpreadReligion to "spread our religion to them"
        }
        val touched = if (what == "settle") civ.getKnownCivs().filter { other -> other.isMajorCiv() && other.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= 6 } }.toList()
            else listOfNotNull(tile.getCity()?.civ?.takeIf { it != civ })
        val wronged = touched.firstOrNull { it.getDiplomacyManager(civ)?.hasFlag(flag) == true } ?: return null
        return " - this breaks our promise to ${wronged.civName} not to $deed"
    }

    /** {unit, action: "gift", to}: march onto the nearest of their land we can reach, and be given to them there */
    private fun giveGift(civ: Civilization, unit: MapUnit, json: JsonValue): String {
        val name = json.getString("to", null) ?: throw IllegalArgumentException("gift needs `to`: the city-state or realm to receive the unit")
        val recipient = civ.getKnownCivs().firstOrNull { it.civName == name && it.isAlive() } ?: throw IllegalArgumentException("We have not met $name, or they are gone")
        giftProblem(civ, unit, recipient)?.let { throw IllegalArgumentException("${unit.name} #${unit.id} cannot be given to $name: $it") }
        val (tile, steps) = withCityStatePassage(unit, recipient) { giftTile(unit, recipient)?.let { Pair(it, pathTo(unit, it).size) } } ?: throw IllegalArgumentException(
            if (!recipient.isCityState && recipient.cities.none { city -> city.getTiles().any { it.isFriendlyTerritory(civ) } })
                "$name's borders are closed to us: a realm can be given a unit only on its own land, which takes open borders"
            else "$name's land is beyond the reach of ${unit.name} #${unit.id}")
        orders[unit.id] = Order("gift", tile.position.x.toInt(), tile.position.y.toInt(), to = recipient.civName)
        released -= unit.id
        val turns = if (tile == unit.getTile()) 0 else kotlin.math.ceil(steps / unit.getMaxMovement().coerceAtLeast(1).toFloat()).toInt()
        return "${unit.name} #${unit.id} will be given to $name at ${where(civ, tile)}" + (if (turns > 0) ", about $turns turns away" else "")
    }

    /** Left wholly to the officials: no order of ours, not fortified, asleep or exploring - what describeUnit calls "automated" */
    fun leftToOfficials(unit: MapUnit) = orders[unit.id] == null && unit.action?.startsWith("Fortify") != true && unit.action != "Sleep" && unit.action?.contains("explore", ignoreCase = true) != true

    fun describeUnit(civ: Civilization, unit: MapUnit): String {
        val order = orders[unit.id]
        val state = when {
            order != null -> "under orders: ${order.action}" + (order.to?.let { " to $it" } ?: "") + (if (order.x != null) " at (${order.x},${order.y})" else "")
            // Whose doing it is: an order of ours reads "under orders"; these are the officials'
            unit.action?.startsWith("Fortify") == true -> "fortified by the officials"
            unit.action == "Sleep" -> "asleep, by the officials"
            unit.action?.contains("explore", ignoreCase = true) == true -> "exploring, by the officials"
            else -> "automated"
        }
        val health = if (unit.health < 100) ", ${unit.health} hp" else ""
        val atSea = if (unit.isEmbarked()) ", at sea, embarked" + (embarkedSince[unit.id]?.let { " since turn $it" } ?: "") else ""
        // A ship left to the officials that has not moved in ten turns: said, as an embarked soldier's time at sea is (Babylon's Caravel sat 86 turns off Seoul)
        val idle = if (state == "automated" && unit.isMilitary() && unit.baseUnit.isWaterUnit && turnsStill(unit) >= 10) ", idle here since turn ${civ.gameInfo.turns - turnsStill(unit)}" else ""
        return "${unit.name} #${unit.id} at ${where(civ, unit.getTile())}$atSea, $state$health$idle"
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
            .filter { settleProblem(civ, it.key) == null && pathTo(unit, it.key).isNotEmpty() }
            .minByOrNull { it.key.aerialDistanceTo(target) * 10 - it.value } ?: return ". No usable site is within this settler's reach - see citySites in the briefing, or disband it"
        return ". Nearest good site: ${where(civ, best.key)}"
    }

    /** The ways this Great Person can be spent, by the order's words: those open where it stands now, and each of the others with what it waits on.
     *  Only the abilities it has at all (a Great Prophet has no golden age), and open means the engine would carry it out now - not merely list it */
    fun abilitiesHere(unit: MapUnit): String {
        val tile = unit.getTile()
        val city = tile.getCity()?.takeIf { tile.isCityCenter() }
        val has = ABILITY_UNIQUES.filter { (_, uniques) -> uniques.any { unit.hasUnique(it) } }.keys
        val open = has.filter { word -> ABILITY_WORDS[word]!!.any { type -> UnitActions.getUnitActions(unit, type).any { it.action != null } } }
        fun why(word: String) = when (word) {
            "improvement" -> "an unimproved tile of ours outside a city to stand on, where its improvement can be built"
            "hurry research" -> if (unit.civ.tech.currentTechnologyName() == null) "research under way - every research goal is reached; give the next with orders.research" else "research that can be hurried"
            "hurry production" -> when {
                city == null || city.civ != unit.civ -> "a city of ours building a building or a wonder"
                city.cityConstructions.getCurrentConstruction() !is com.unciv.models.ruleset.Building -> "a building or a wonder under way in ${city.name}, not ${city.cityConstructions.currentConstructionName().ifEmpty { "nothing" }}"
                !city.cityConstructions.canBeHurried() -> "work that can be hurried: ${city.cityConstructions.currentConstructionName()} cannot be"
                else -> "more work left on ${city.cityConstructions.currentConstructionName()}"
            }
            "trade mission" -> "a city-state's land to stand in, at peace with us"
            "religion" -> "a city of ours to found or enhance our religion in, or a city of another faith to spread it to"
            else -> "movement left this turn"
        }
        // Each ability by the order's word for it, the improvement with what it raises
        val named = { word: String -> if (word == "improvement") greatImprovementOf(unit)?.let { "improvement (it raises $it)" } ?: word else word }
        return (if (open.isEmpty()) "nothing is open to it here" else "open to it here now: ${open.joinToString("; ") { named(it) }}") +
            (has - open.toSet()).takeIf { it.isNotEmpty() }?.let { blocked -> "; " + blocked.joinToString("; ") { "${named(it)} needs ${why(it)}" } }.orEmpty()
    }

    /** Could this unit, given a full turn's movement, walk to that tile at all? Orders are given between turns, when units have spent theirs, and Unciv finds no
     *  path for a spent unit. A yes is a hope, not a promise: the engine's path reckoning counts unexplored tiles as passable, so a way found now may close as the
     *  march uncovers the ground - why a garrison march can fail turns later and a march can wander (Runs 22 and 23), and why failures are remembered and
     *  "stuck" means no nearer */
    /** Why [unit] cannot reach [target] when the reason is the embark rule alone: "it would have to put to sea within reach of Japan's Frigate".
     *  Null when the way is closed for any other reason too */
    fun seaBlock(unit: MapUnit, target: Tile): String? {
        unit.movement.clearPathfindingCache()
        val way = try { RulerOverrides.withEmbarkRuleWaived { pathTo(unit, target) } } finally { unit.movement.clearPathfindingCache() }
        if (way.isEmpty() || way.none { it.isWater }) return null
        val reach = RulerOverrides.shipReach(unit.civ)
        val wet = way.filter { it.isWater && it in reach }.ifEmpty { return null }
        val strikers = wet.flatMap { RulerOverrides.strikersOf(unit.civ, it) }.distinct()
        val named = strikers.groupBy { it.first }.map { (owner, units) -> "${RulerCampaigns.possessive(owner.civName)} " +
            Prose.andList(units.map { it.second }.groupingBy { it }.eachCount().map { (name, n) -> if (n == 1) name else "$n ${Prose.pluralOf(name)}" }) }
        return "it would have to put to sea within reach of ${Prose.andList(named).ifEmpty { "the enemy" }}, and a soldier ashore at war does not"
    }

    fun canMarch(unit: MapUnit, target: Tile) = target == unit.getTile() || pathTo(unit, target).isNotEmpty()

    /** Does this unit, sent on a trade mission to [target]'s city-state, stand on that city-state's land - where the engine lets the mission be made (the tile's owner,
     *  a city-state not at war with us)? The order's words name the city; the mission needs only its land */
    private fun tradeMissionLand(unit: MapUnit, order: Order, target: Tile): Boolean {
        if (order.action != "use" || order.ability?.lowercase()?.trim() != "trade mission") return false
        val state = target.getOwner()?.takeIf { it.isCityState } ?: return false
        return unit.getTile().getOwner() == state && !state.isAtWarWith(unit.civ)
    }

    private fun pathTo(unit: MapUnit, target: Tile): List<Tile> {
        val spent = unit.currentMovement
        unit.currentMovement = unit.getMaxMovement().toFloat()
        return try { unit.movement.getShortestPath(target) } finally { unit.currentMovement = spent }
    }

    /** Best places to found a city near our lands: the officials' ranking when a settler can judge, else our own simple survey - and, once our people
     *  can embark, sites across the sea from the same survey, marked so (Run 18's two continents: the list hid every overseas site) */
    fun citySites(civ: Civilization): List<String> {
        val settler = civ.units.getCivUnits().firstOrNull { it.hasUnique(UniqueType.FoundCity) }
        // A realm with no city yet has no home landmass by its cities: the ground its settler, or any of its people, stands on is home (at turn 0 every site read "across the sea", Run 19)
        val home = civ.cities.map { it.getCenterTile().getContinent() }.toSet()
            .ifEmpty { (listOfNotNull(settler) + civ.units.getCivUnits()).map { it.getTile() }.filter { it.isLand }.map { it.getContinent() }.toSet() }
        val surveyed = survey(civ)
        val ranked: Map<Tile, Float> = settler?.let { CityLocationTileRanker.getBestTilesToFoundCity(it, 8, 0f).tileRankMap }
            ?.filterKeys { settleProblem(civ, it) == null }?.takeIf { it.isNotEmpty() }
            ?: surveyed.filterKeys { it.getContinent() in home }
        // A site nobody can walk to is no site: asked of the settler if we have one, else judged by landmass
        val near = ranked.entries.sortedByDescending { it.value }.asSequence()
            .filter { (tile, _) -> if (settler != null) tile == settler.getTile() || pathTo(settler, tile).isNotEmpty() else tile.getContinent() in home }
            .take(5).map { it.key to it.value }.toList()
        val overseas = surveyed.filterKeys { it.getContinent() !in home && near.none { (tile, _) -> tile == it } }.entries.sortedByDescending { it.value }
            .take(if (near.isEmpty()) 5 else 2).map { it.key to it.value }
        return (near + overseas).map { (tile, rank) -> describeSite(civ, tile, rank) + (if (tile.getContinent() in home) "" else crossing(civ, tile)) + promiseBroken(civ, tile, "settle")?.replace(" - this breaks", " - settling here breaks").orEmpty() }
    }

    /** How a site across the sea is reached: from which coastal city of ours, and that the settler embarks on its own (the move and settle orders path across water) */
    private fun crossing(civ: Civilization, tile: Tile): String {
        val port = civ.cities.filter { it.isCoastal() }.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return " - across the sea"
        return " - across the sea, ${port.getCenterTile().aerialDistanceTo(tile)} tiles from ${port.name}, our nearest coastal city; a settler ordered there embarks and crosses on its own"
    }

    /** Is there anywhere left to found a city within our reach? The same survey as the briefing's sites, so the officials' settler gate and the
     *  briefing never disagree; unexplored ground in reach still counts as possible land. Reckoned once a turn per civ. */
    private val landLeft = HashMap<String, Pair<Int, Boolean>>()
    fun hasLandForACity(civ: Civilization): Boolean {
        val turn = civ.gameInfo.turns
        landLeft[civ.civName]?.takeIf { it.first == turn }?.let { return it.second }
        // Unexplored ground counts as possible land: early on almost nothing is explored, and a gate that closes then starves the realm of settlers
        val any = civ.cities.isEmpty() || survey(civ).isNotEmpty() || civ.cities.asSequence().flatMap { it.getCenterTile().getTilesInDistance(REACH) }
            .any { tile -> !tile.isExplored(civ) && (civ.tech.unitsCanEmbark || tile.isLand) }
        // Said once each way, so a run can be checked for what this gate did
        val before = landLeft[civ.civName]?.second ?: true
        if (before && !any) log(civ, "No land is left within our reach for a new city; the officials will train no settlers until that changes.")
        if (!before && any && civ.cities.isNotEmpty()) log(civ, "There is land for a new city again; the officials may train settlers.") // a realm that has just fallen has no cities, not new land
        landLeft[civ.civName] = Pair(turn, any)
        return any
    }

    /** Explored land within reach of our cities that could take a city - on a landmass we live on, or across the sea once our people can embark -
     *  scored by what lies around it, nearer home the better */
    private fun survey(civ: Civilization): Map<Tile, Float> {
        if (civ.cities.isEmpty()) return emptyMap()
        val home = civ.cities.map { it.getCenterTile().getContinent() }.toSet()
        val candidates = civ.cities.asSequence().flatMap { it.getCenterTile().getTilesInDistance(REACH) }.distinct()
            .filter { it.isExplored(civ) && it.isLand && (civ.tech.unitsCanEmbark || it.getContinent() in home) && settleProblem(civ, it) == null }
        return candidates.associateWith { tile ->
            var score = 0f
            for (near in tile.getTilesInDistance(RulerOverrides.FIELDS)) {
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
        val resources = tile.getTilesInDistance(RulerOverrides.FIELDS).mapNotNull { it.resource }.groupingBy { it }.eachCount()
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
        /** What a unit order may ask, as the order names it (published by GET /schema) */
        val UNIT_ACTIONS = listOf("move", "settle", "garrison", "fortify", "hold", "explore", "use", "gift", "disband", "release")
        /** The ways of spending a Great Person, as the order names them */
        val ABILITY_NAMES get() = ABILITY_WORDS.keys.toList()
        /** How far from our cities the land survey looks: the officials' settlers range as far (15 tiles and more) */
        private const val REACH = 16
        /** Turns a soldier whose garrison march to a city failed is passed over for that city by the standing garrison order */
        private const val GARRISON_RETRY = 10
        private val ABILITIES = listOf(UnitActionType.ConductTradeMission, UnitActionType.HurryResearch, UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding, UnitActionType.HurryPolicy, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion,
            UnitActionType.TriggerUnique, UnitActionType.CreateImprovement, UnitActionType.SpreadReligion)

        /** The words a ruler may use for the ways of spending a Great Person */
        private val ABILITY_WORDS = mapOf(
            "improvement" to listOf(UnitActionType.CreateImprovement), // academy, manufactory, customs house, holy site, citadel
            "hurry research" to listOf(UnitActionType.HurryResearch),
            "hurry production" to listOf(UnitActionType.HurryWonder, UnitActionType.HurryBuilding),
            "hurry policy" to listOf(UnitActionType.HurryPolicy),
            "trade mission" to listOf(UnitActionType.ConductTradeMission),
            "religion" to listOf(UnitActionType.FoundReligion, UnitActionType.EnhanceReligion, UnitActionType.SpreadReligion),
            "golden age" to listOf(UnitActionType.TriggerUnique))
        private fun abilitiesNamed(word: String) = ABILITY_WORDS[word.lowercase().trim()] ?: emptyList()
        /** An ability as the order names it ("trade mission", "golden age"), never the engine's own name ("ConductTradeMission", "TriggerUnique") */
        fun abilityWord(action: UnitActionType) = ABILITY_WORDS.entries.firstOrNull { action in it.value }?.key ?: action.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
        /** What makes a unit have each way at all, whatever the tile it stands on */
        private val ABILITY_UNIQUES = mapOf(
            "improvement" to listOf(UniqueType.ConstructImprovementInstantly),
            "hurry research" to listOf(UniqueType.CanHurryResearch),
            "hurry production" to listOf(UniqueType.CanSpeedupWonderConstruction, UniqueType.CanSpeedupConstruction),
            "hurry policy" to listOf(UniqueType.CanHurryPolicy),
            "trade mission" to listOf(UniqueType.CanTradeWithCityStateForGoldAndInfluence),
            "religion" to listOf(UniqueType.MayFoundReligion, UniqueType.MayEnhanceReligion, UniqueType.CanSpreadReligion),
            "golden age" to listOf(UniqueType.OneTimeEnterGoldenAge, UniqueType.OneTimeEnterGoldenAgeTurns))

        /** A line of the rulebook in words: "[+2 Science] <after discovering [Scientific Theory]>" -> "+2 Science (after discovering Scientific Theory)" */
        fun inWords(unique: String) = unique.replace(Regex("\\[([^\\]]*)\\]"), "$1").replace(Regex("\\s*<([^>]*)>"), " ($1)")

        /** Yields in words, without the game's stat icons: "+6 Faith, +1 Gold" */
        fun statsInWords(stats: com.unciv.models.stats.Stats) = stats.filter { it.value != 0f }.joinToString { "${if (it.value > 0) "+" else ""}${it.value.toInt()} ${it.key.name}" }

        /** Rulebook lines a ruler has no use for: where the map generator puts a thing, and the AI's own weights */
        fun isRulebookNoise(unique: String) = listOf("Generated", "Minor deposits", "Guaranteed with", "Deposits", "Considered ", "A Region", "Must be adjacent", "Must not be", "Must be on", "Occurs ").any { unique.startsWith(it) }
            // the rulebook's notes to itself: the AI's weights, what is shown or chosen where, a game setting's switch
            || listOf("weight to this choice", "Will not be displayed", "Will not be chosen", "Will not be replaced by automated", "is enabled>").any { it in unique }
            || "for AI decisions" in unique || "Will not be displayed" in unique

        /** A tile improvement told plainly: its yield and the bonuses that come later - or, for one that yields nothing (a Citadel), what it does */
        fun improvementInWords(improvement: com.unciv.models.ruleset.tile.TileImprovement): String {
            val stats = statsInWords(improvement.cloneStats()).ifEmpty { null }
            val later = improvement.uniques.filter { "after discovering" in it || "<" in it && it.startsWith("[+") }.map { inWords(it) }
            val does = improvement.uniques.filter { it != "Great Improvement" && it !in listOf("Removes removable features when built", "Will not be replaced by automated units") && !it.startsWith("Pillaging") && !it.startsWith("[+") && !isRulebookNoise(it) }.map { inWords(it) }
            return (listOfNotNull(stats?.let { "$it a turn on a worked tile" }) + later).ifEmpty { does }.joinToString("; ")
        }

        /** The improvement this unit can raise in an instant (a Great Person's), with what it gives and whether it uses the unit up; null for one that raises none */
        fun greatImprovementOf(unit: MapUnit): String? {
            val unique = unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).firstOrNull() ?: return null
            val improvement = unit.civ.gameInfo.ruleset.tileImprovements[unique.params[0]] ?: return null
            val consumed = "consuming" in unique.text
            return "${Prose.article(improvement.name)} (${improvementInWords(improvement)}${if (consumed) "; it uses up the ${unit.baseUnit.name}" else ""})"
        }

        /** "(x,y), 3 tiles NE of Rome": for the briefing's unit list and order replies, where the advisor needs the coordinates to give orders. A chancery line, which may
         *  reach the ruler, names a place as RulerCampaigns.placeOf does */
        fun where(civ: Civilization, tile: Tile): String {
            val coords = "(${tile.position.x},${tile.position.y})"
            val nearest = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return coords
            val distance = nearest.getCenterTile().aerialDistanceTo(tile)
            if (distance == 0) return "$coords, in ${nearest.name}"
            val compass = RulerCampaigns.compassOf(HexMath.hex2WorldCoords(tile.position), HexMath.hex2WorldCoords(nearest.getCenterTile().position), short = true)
            return "$coords, $distance tile${plural(distance)} $compass of ${nearest.name}"
        }
    }
}
