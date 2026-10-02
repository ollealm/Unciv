package com.unciv.logic.automation

import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.nation.Personality
import java.util.concurrent.ConcurrentHashMap

/**
 *  Strategic overrides set from outside the game by a civ's "ruler" (see desktop RulerHost).
 *  Not serialized with the save - the host persists and re-applies them.
 */
object RulerOverrides {
    /** civID -> personality replacing the nation's ruleset personality for all AI decisions */
    val personalities = ConcurrentHashMap<String, Personality>()

    /** civIDs of AI civs that have a ruler - these keep their notifications, as the advisor reports from them */
    val ruledCivs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ids of cities whose build queue was set by the ruler - the automation must not overwrite its front item */
    val lockedCities: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ids of cities whose citizen focus was set by the ruler - the automation must not re-choose it every turn */
    val focusedCities: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** civID -> gold the officials must leave in the treasury; they spend on their own only what lies above it */
    val goldReserve = ConcurrentHashMap<String, Int>()

    /** A ruled civ's posture of arms: the city its army is to take (warAim), how it fights (attack | defend | raid), and how many soldiers stay home */
    class Posture(val warAim: String? = null, val stance: String = "attack", val reserve: Int = 0)
    val postures = ConcurrentHashMap<String, Posture>()

    /** civID -> when a ruled civ's officials may re-arm units with gold on their own (a standing order of the ruler): "always", "war" (the default), "never" */
    val rearming = ConcurrentHashMap<String, String>()
    /** May the officials re-arm this civ's units now? Under "war", only while at war with a major power - city-states and barbarians do not count. Unruled civs always may */
    fun mayRearm(civ: Civilization): Boolean = when (rearming[civ.civID] ?: if (civ.civID in ruledCivs) "war" else "always") {
        "always" -> true
        "never" -> false
        else -> civ.getKnownCivs().any { it.isMajorCiv() && civ.isAtWarWith(it) }
    }

    /** Strategic resources a ruled civ's own pending proposals offer (name -> amount): the officials' re-arming must not spend them out from under the deal */
    @Volatile var promised: ((Civilization) -> Map<String, Int>)? = null
    /** Would re-arming this unit take a resource [promised] holds, leaving less than the proposal offers? Judged on every newer arm it could take */
    fun spendsPromised(unit: MapUnit, promised: Map<String, Int>): Boolean = promised.isNotEmpty() && unit.baseUnit.getUpgradeUnits(unit.cache.state)
        .mapNotNull { unit.civ.gameInfo.ruleset.units[it] }.map { unit.civ.getEquivalentUnit(it) }
        .any { to -> to.getResourceRequirementsPerTurn(unit.cache.state).any { (resource, needs) -> resource in promised && !unit.requiresResource(resource)
            && unit.civ.getResourceAmount(resource) - needs < promised[resource]!! } }

    /** A ruled civ's land soldier far behind its best arms - less than half the strength of our strongest land soldier, engines aside (RulerHost.poorDefender's
     *  test): under the attack posture it is not sent to a siege or an assault but stays home as garrison and reserve. Warriors, Lancers and a Pikeman thrown
     *  at Information-era guns died for nothing (Run 16) */
    @yairm210.purity.annotations.Readonly
    fun outclassed(unit: MapUnit): Boolean {
        if (unit.civ.civID !in ruledCivs || !unit.isMilitary() || !unit.baseUnit.isLandUnit || unit.baseUnit.isProbablySiegeUnit()) return false
        if ((postures[unit.civ.civID]?.stance ?: "attack") != "attack") return false
        fun might(soldier: MapUnit) = soldier.baseUnit.strength.coerceAtLeast(soldier.baseUnit.rangedStrength)
        val best = unit.civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit && !it.baseUnit.isProbablySiegeUnit() }.maxOfOrNull { might(it) } ?: return false
        return might(unit) * 2 < best
    }

    /** The officials' own demands of another power: (our civ, the other power, the demand). Demands made OF a ruled civ are not answered by its officials -
     *  they wait on its desk (its popupAlerts) for the advisor */
    @Volatile var demandMade: ((Civilization, Civilization, com.unciv.logic.civilization.diplomacy.Demand) -> Unit)? = null

    /** A ruled civ's vote for world leader, when the world votes: the civID of the power it is pledged to, or null to abstain - never the officials' choice */
    @Volatile var worldVoteOf: ((Civilization) -> String?)? = null

    /** Called for a ruled civ before its officials spend any gold: the ruler's own purchases due this turn come first */
    @Volatile var rulersFirst: ((Civilization) -> Unit)? = null

    /** civIDs whose officials may not give gold to city-states on their own (a standing order of the ruler) */
    val noGifts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Kinds of deal a ruled civ's officials may not propose on their own (a standing order of the ruler): "Open Borders", "Research Agreement",
     *  "Defensive Pact", "Declaration of Friendship", "luxuries", "calls to war" (asking another power to join a war of ours), or "all" */
    val noProposals = ConcurrentHashMap<String, Set<String>>()
    fun mayPropose(civ: com.unciv.logic.civilization.Civilization, kind: String) = noProposals[civ.civID]?.let { "all" in it || kind in it } != true

    /** Called with (civ, unit, why) when a ruled civ's automation disbands a unit for a reason of its own, before it goes */
    @Volatile var unitDisbanded: ((com.unciv.logic.civilization.Civilization, MapUnit, String) -> Unit)? = null

    /** civIDs that may raise no world wonder at all (a game setting: e.g. only the rulers' realms compete for them) */
    val noWonders: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when a ruled civ's never-build list covers this construction: the automation must not choose it at all */
    @Volatile var forbidden: ((com.unciv.logic.civilization.Civilization, com.unciv.models.ruleset.INonPerpetualConstruction) -> Boolean)? = null

    /** Called with (civ, gold, what for) whenever a ruled civ's automation spends from the treasury */
    @Volatile var spendingNoted: ((com.unciv.logic.civilization.Civilization, Int, String) -> Unit)? = null

    /**
     *  Asked before the automation moves each unit of a ruled civ. Returning true means the ruler's own order
     *  handled this unit this turn and the automation must leave it alone.
     */
    @Volatile var unitHandler: ((MapUnit) -> Boolean)? = null

    /** True when the ruler gave this unit its own orders: the officials' retreat must not swap it out of its place */
    @Volatile var underOrders: ((MapUnit) -> Boolean)? = null

    /**
     *  What became of a trade request a ruled civ laid before another: accepted | countered | declined | invalid (the officials could not
     *  honour it and dropped it) | voided (one side could no longer supply). Told to the host with the request itself, so a proposal is
     *  settled by its own record and not by guesswork over notifications.
     */
    @Volatile var tradeAnswered: ((TradeRequest, Civilization, String) -> Unit)? = null
    fun noteAnswer(request: TradeRequest, receiver: Civilization, outcome: String) {
        if (request.requestingCiv in ruledCivs) tradeAnswered?.invoke(request, receiver, outcome)
    }

    /** A unit destroyed or captured in battle, attacker or defender alike: the host otherwise cannot tell a death from a disbanding. Every civ's, not only a ruled one's - the world's oddities are anyone's */
    class UnitLoss(val civ: Civilization, val unit: MapUnit, val where: Tile, val enemy: Civilization?, val enemyUnit: String?, val captured: Boolean)
    @Volatile var unitLost: ((UnitLoss) -> Unit)? = null
    /** [civ] is passed apart from the unit because a captured unit already belongs to its captor by the time this is called */
    fun noteLoss(civ: Civilization, unit: MapUnit, where: Tile, enemy: ICombatant?, captured: Boolean = false) {
        unitLost?.invoke(UnitLoss(civ, unit, where, enemy?.getCivInfo(), enemy?.getName(), captured))
    }
}
