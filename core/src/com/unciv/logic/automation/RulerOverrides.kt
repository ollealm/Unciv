package com.unciv.logic.automation

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.ruleset.unique.UniqueType
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

    /** Does [other]'s own offer of this kind of deal already wait on a ruled civ's desk for its advisor? Then its officials propose none of that kind
     *  to them - the two would cross (China and Egypt each proposed a Research Agreement while the other's waited, Run 19). Luxuries are one kind;
     *  a proposal of friendship waits as an alert, not a trade. An unruled civ answers its desk before it proposes, so its proposals never cross */
    @yairm210.purity.annotations.Readonly
    fun crossesWaitingOffer(civ: Civilization, other: Civilization, kind: String): Boolean {
        if (civ.civID !in ruledCivs) return false
        if (kind == "Declaration of Friendship") return civ.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }
        return civ.tradeRequests.any { request -> request.requestingCiv == other.civID && (request.trade.ourOffers + request.trade.theirOffers)
            .any { offer -> if (kind == "luxuries") offer.type == TradeOfferType.Luxury_Resource else offer.name == kind } }
    }

    /** A ruled civ keeps the promises its advisor gave: true when [other] holds our word under [promise] (the flag sits on the promisee's side, toward
     *  us - Demand.kt). The engine's own automation reneges unless the other side is an Ally; a ruler's word is not the officials' to break.
     *  An explicit order of the ruler's is another matter and is not gated here. Unruled civs keep the engine's habit */
    @yairm210.purity.annotations.Readonly
    fun keepsPromise(civ: Civilization, other: Civilization, promise: DiplomacyFlags): Boolean =
        civ.civID in ruledCivs && other.getDiplomacyManager(civ)?.hasFlag(promise) == true

    /** A building that serves only the spaceship - enabling its parts, or speeding their construction - in a world whose spaceship victory is off:
     *  nobody builds it, officials or ruler (Egypt's officials queued a Spaceship Factory with the Scientific victory off, Run 19). The parts and the
     *  Apollo Program bar themselves by their "Only available" lines; the Spaceship Factory carries none, and its +3 production is no reason to raise
     *  it. Every civ's, not only a ruled one's: the thing is useless to anyone */
    @yairm210.purity.annotations.Readonly
    fun servesOnlyAnAbsentVictory(construction: INonPerpetualConstruction, game: GameInfo): Boolean {
        val spaceshipVictories = game.ruleset.victories.values.filter { it.requiredSpaceshipParts.isNotEmpty() }
        if (spaceshipVictories.isEmpty() || spaceshipVictories.any { it.name in game.gameParameters.victoryTypes }) return false
        val purpose = (construction as IHasUniques).uniqueObjects.filter { it.type != UniqueType.OnlyAvailable && it.type != UniqueType.CannotBeHurried && it.type != UniqueType.TriggersAlertOnCompletion }
        return purpose.isNotEmpty() && purpose.all { it.type == UniqueType.EnablesConstructionOfSpaceshipParts || it.type == UniqueType.SpaceshipPart || "Spaceship part" in it.params }
    }

    /** Set by the host: is this unit of a ruled civ under the ruler's own order? Routines that draft units of their own (the settler escort) pass it by */
    @Volatile var hasOrder: ((MapUnit) -> Boolean)? = null
    @yairm210.purity.annotations.Readonly
    fun isUnderOrders(unit: MapUnit) = unit.civ.civID in ruledCivs && hasOrder?.invoke(unit) == true

    /** A step toward a tile that may not open after all (the path reckoning and the march disagree): taken if it can be, else the unit stands where it is.
     *  For the moves this fork added to the officials' routines - an unreachable step there once stopped the whole turn (Run 20, T187) */
    fun tryHeadTowards(unit: MapUnit, tile: Tile): Boolean =
        try { unit.movement.headTowards(tile); true } catch (_: com.unciv.logic.map.mapunit.movement.UnitMovement.UnreachableDestinationException) { false }

    /** Called with (civ, unit, why) when a ruled civ's unit could not be moved by the officials this turn: the turn goes on without it */
    @Volatile var unitNotMoved: ((Civilization, MapUnit, String) -> Unit)? = null
    /** A ruled civ's unit moved by [move]: whatever goes wrong costs that unit its turn, never the turn itself. Other civs keep the engine's habit */
    fun guarded(unit: MapUnit, move: () -> Unit) {
        if (unit.civ.civID !in ruledCivs) return move()
        try { move() } catch (ex: Exception) {
            System.err.println("The officials could not move ${unit.civ.civName}'s ${unit.name} #${unit.id}: $ex")
            unitNotMoved?.invoke(unit.civ, unit, if (ex is com.unciv.logic.map.mapunit.movement.UnitMovement.UnreachableDestinationException) "the way the officials chose for it would not open" else "the officials' routine failed on it")
        }
    }

    /** Called with (civ, what, why) when a block of a ruled civ's officials' work moving several units at once failed: the turn goes on without it */
    @Volatile var blockFailed: ((Civilization, String, String) -> Unit)? = null
    /** A block of the officials' work for a ruled civ ([what]: "escorting our settlers"): whatever goes wrong skips that block for the turn, never the turn itself */
    fun guardedBlock(civ: Civilization, what: String, block: () -> Unit) {
        if (civ.civID !in ruledCivs) return block()
        try { block() } catch (ex: Exception) {
            System.err.println("The officials could not finish $what for ${civ.civName}: $ex")
            blockFailed?.invoke(civ, what, if (ex is com.unciv.logic.map.mapunit.movement.UnitMovement.UnreachableDestinationException) "a way they chose would not open" else "their routine failed")
        }
    }

    /** Called with (civ, unit, why) when a ruled civ's automation disbands a unit for a reason of its own, before it goes */
    @Volatile var unitDisbanded: ((com.unciv.logic.civilization.Civilization, MapUnit, String) -> Unit)? = null

    /** Called with (civ, unit, why) when a ruled civ's unit goes by neither battle nor disbanding - used up by its own work ([MapUnit.consume]) or killed by the
     *  ground it stood on - before it goes: the host otherwise could only guess at "disbanded or used up". [why] finishes "Our X #id <where> ..." */
    @Volatile var unitExpended: ((Civilization, MapUnit, String) -> Unit)? = null
    fun noteExpended(unit: MapUnit, why: String) { if (unit.civ.civID in ruledCivs) unitExpended?.invoke(unit.civ, unit, why) }

    /** A blow struck by a ruled civ's unit or city: the engine tells only the defender of it. [targetCity] when the blow fell on a city (its walls' health after),
     *  else [targetUnit], the unit struck; [fell] when the blow destroyed or captured it */
    class Strike(val attacker: Civilization, val targetCivName: String, val targetCity: String?, val targetUnit: String?, val health: Int, val maxHealth: Int, val fell: Boolean)
    @Volatile var struck: ((Strike) -> Unit)? = null
    fun noteStrike(attacker: ICombatant, defender: ICombatant, fell: Boolean) {
        val civ = attacker.getCivInfo()
        if (civ.civID !in ruledCivs || civ == defender.getCivInfo()) return
        struck?.invoke(Strike(civ, defender.getCivInfo().civName, if (defender.isCity()) defender.getName() else null, if (defender.isCity()) null else defender.getName(),
            defender.getHealth().coerceAtLeast(0), defender.getMaxHealth(), fell))
    }

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
