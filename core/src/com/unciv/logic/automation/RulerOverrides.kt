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

    /** The home reserve of a ruled civ at war: the land soldiers nearest its cities, as many as the posture keeps back. They hold home ground whatever
     *  the stance, and no siege takes them (the siege routine took a reserve garrison out of Sparta two turns running, audit 2026-10-03) */
    @yairm210.purity.annotations.Readonly
    fun inReserve(unit: MapUnit): Boolean {
        val posture = postures[unit.civ.civID] ?: return false
        if (posture.reserve <= 0 || !unit.isMilitary() || !unit.baseUnit.isLandUnit || !unit.civ.isAtWar()) return false
        return unit.civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit }
            .sortedBy { soldier -> unit.civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(soldier.getTile()) } ?: 99 }
            .take(posture.reserve).any { it.id == unit.id }
    }

    /** A war as the engine declares it (DeclareWar.declareWar), every civ's: [declarer] is the power whose war it now is - under a defensive pact that is
     *  the partner pulled in, not the manager's owner - [target] the power it fights from now, [ally] the power it joins or avenges where the kind
     *  names one. The host builds the world's events and the council's news from these, so a join is never told as a declaration */
    class War(val declarer: Civilization, val target: Civilization, val type: com.unciv.logic.civilization.diplomacy.WarType, val ally: Civilization?)
    @Volatile var warDeclared: ((War) -> Unit)? = null
    fun noteWar(civ: Civilization, other: Civilization, reason: com.unciv.logic.civilization.diplomacy.DeclareWarReason) {
        val pactPartnerJoins = reason.warType == com.unciv.logic.civilization.diplomacy.WarType.DefensivePactWar // the aggressor's manager declares on the partner it pulled in
        warDeclared?.invoke(War(if (pactPartnerJoins) other else civ, if (pactPartnerJoins) civ else other, reason.warType, reason.allyCiv))
    }

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

    /** The tiles the enemy soldiers near this civilian of a ruled civ could strike or take next turn, by their own movement and range - the engine's own
     *  reckoning of a tile's danger (UnitAutomation.getDangerousTiles), which it applies only to enemies three tiles off, while its civilian flees only what
     *  it could itself walk to: eight Workers were taken in the open at war (Run 23). Looks eight tiles out, the farthest-reaching arms plus the civilian's
     *  own step. Empty for an unruled civ's unit, which keeps the engine's habit */
    fun enemyReach(unit: MapUnit): Set<Tile> {
        if (unit.civ.civID !in ruledCivs) return emptySet()
        val threat = unit.civ.threatManager
        return threat.getEnemyUnitsOnTiles(threat.getTilesWithEnemyUnitsInDistance(unit.getTile(), 8))
            .flatMap { it.getTile().getTilesInDistance(it.getMaxMovement() - 1 + it.getRange()) }.toHashSet()
    }

    /** Where a ruled civ's civilian with enemy soldiers in reach of it keeps to: of the tiles it can step to that lie beyond their reach, the one nearest a city
     *  of ours - never the open field the engine's "furthest from danger" may choose. Null when none is open to it or none of the enemy can reach it */
    fun shelterNearHome(unit: MapUnit, open: Sequence<Tile>): Tile? {
        val reach = enemyReach(unit)
        if (unit.getTile() !in reach || unit.civ.cities.isEmpty()) return null
        return open.filter { it !in reach }.minByOrNull { tile -> unit.civ.cities.minOf { it.getCenterTile().aerialDistanceTo(tile) } }
    }

    /** The tiles a ruled civ's worker keeps off while enemy soldiers are near: those they could reach next turn (two Workers walked from safe ground into
     *  Babylon's tanks' reach the turn war was declared, on the way to a tile to work and a city short of workers), and while its own tile is among them, all
     *  that are not within two of a city of ours - it works near home till they are gone, or waits. Null when none is near: the engine's own list serves */
    fun keptOffAtWar(unit: MapUnit, dangerousTiles: HashSet<Tile>): HashSet<Tile>? {
        val reach = enemyReach(unit)
        if (reach.isEmpty()) return null
        val kept = HashSet(dangerousTiles); kept += reach
        if (unit.getTile() in reach) {
            val nearHome = unit.civ.cities.flatMap { it.getCenterTile().getTilesInDistance(2) }.toHashSet()
            kept += unit.getTile().getTilesInDistance(3).filter { it !in nearHome }
        }
        return kept
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
    /** civID -> faith the officials leave untouched (a ruler's standing order, as goldReserve is for gold) */
    val faithReserve = ConcurrentHashMap<String, Int>()
    /** Set by the host: told of every faith purchase the officials make for a ruled civ - (civ, faith, "Great Scientist in Persepolis") */
    @Volatile var faithSpent: ((Civilization, Int, String) -> Unit)? = null
    /** Set by the host: told of a free technology taken for a ruled civ - (civ, technology, the goal it was taken toward or null) */
    @Volatile var freeTechTaken: ((Civilization, String, String?) -> Unit)? = null

    /** The officials' faith purchase for any civ: a ruled civ's keeps above its faith reserve and off its never-build list, and is told to the host
     *  (Persia's officials spent 1,600 faith in a turn with no line, audit 2026-10-03). Unruled civs buy as the engine always has */
    fun officialsBuyWithFaith(city: com.unciv.logic.city.City, construction: com.unciv.models.ruleset.INonPerpetualConstruction): Boolean {
        val civ = city.civ
        val cost = construction.getStatBuyCost(city, com.unciv.models.stats.Stat.Faith) ?: return false
        val ruled = civ.civID in ruledCivs
        if (ruled && (civ.religionManager.storedFaith - cost < (faithReserve[civ.civID] ?: 0) || forbidden?.invoke(civ, construction) == true)) return false
        val bought = city.cityConstructions.purchaseConstruction(construction, -1, true, com.unciv.models.stats.Stat.Faith)
        if (bought && ruled) faithSpent?.invoke(civ, cost, "${construction.name} in ${city.name}")
        return bought
    }

    /** Set by the host: does the near-victory note name this power now? A ruled realm's officials offer it no Research Agreement */
    @Volatile var nearWinner: ((Civilization) -> Boolean)? = null

    /** May a ruled realm's officials offer [other] a Research Agreement now: never past the ruler's gold reserve, never in a war of ours, never to a power
     *  a step from victory (nine agreements fed Greece's science after its Apollo Program, Run 22). Unruled civs keep the engine's habit */
    fun officialsMayOfferResearch(civ: Civilization, other: Civilization, cost: Int): Boolean {
        if (civ.civID !in ruledCivs) return true
        if (civ.gold - cost < (goldReserve[civ.civID] ?: 0)) return false
        if (civ.getKnownCivs().any { it.isMajorCiv() && civ.isAtWarWith(it) }) return false
        return nearWinner?.invoke(other) != true
    }

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

    /** A blow struck by a ruled civ's unit or city, or on one: the engine tells only the defender of it, and in its own words. [targetCity] when the blow fell on a city
     *  (its walls' health after), else [targetUnit], the unit struck (by number in [targetUnitId], so two of a name are told apart); [fell] when the blow destroyed or
     *  captured it; [attackerUnit] what struck, or [attackerCity] when it was a city's guns; [attackerFell] when the attacker died in the attempt */
    class Strike(val attacker: Civilization, val targetCivName: String, val targetCity: String?, val targetUnit: String?, val health: Int, val maxHealth: Int, val fell: Boolean, val targetTile: Tile? = null,
                 val attackerUnit: String? = null, val attackerCity: String? = null, val attackerFell: Boolean = false, val targetUnitId: Int? = null)
    @Volatile var struck: ((Strike) -> Unit)? = null
    fun noteStrike(attacker: ICombatant, defender: ICombatant, fell: Boolean) {
        val civ = attacker.getCivInfo()
        if (civ == defender.getCivInfo() || (civ.civID !in ruledCivs && defender.getCivInfo().civID !in ruledCivs)) return
        struck?.invoke(Strike(civ, defender.getCivInfo().civName, if (defender.isCity()) defender.getName() else null, if (defender.isCity()) null else defender.getName(),
            defender.getHealth().coerceAtLeast(0), defender.getMaxHealth(), fell, defender.getTile(),
            if (attacker.isCity()) null else attacker.getName(), if (attacker.isCity()) attacker.getName() else null, !attacker.isCity() && attacker.isDefeated(),
            (defender as? com.unciv.logic.battle.MapUnitCombatant)?.unit?.id))
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
