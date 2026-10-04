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
    /** A city's fields: the ring of tiles about it that the chancery's words call its own ground - "the fields of Athens", a site's surroundings, home ground */
    const val FIELDS = 2
    /** "Near": the one distance the briefing means by it - the soldiers about an enemy city in the war estimate, the soldiers about ours in the threats */
    const val NEAR = 3
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

    /** What a ruled civ's officials may do on their own, as one table. [fixed]: the act binds the court - war and peace, its sworn word, its cities - and is
     *  never the officials', whatever an order says. The rest are the officials' own by [byDefault], and a court's standing order may open or bar each.
     *  Unruled civs do as the engine always has. [label] is the order's and the briefing's word for it */
    enum class Act(val label: String, val fixed: Boolean, val byDefault: Boolean) {
        Peace("peace", true, false), War("war", true, false), DefensivePact("Defensive Pact", true, false), CallToWar("calls to war", true, false),
        DemandNotToAttack("the demand not to attack us", true, false), Marriage("marriage", true, false), Annexation("annexation", true, false),
        Denouncement("denouncement", true, false), Protection("protection", true, false), Tribute("tribute", true, false),
        // The realm's sworn word for thirty turns, and the door to pacts: closed unless the court opens it (an unadvised power accepts a proposal on its turn)
        Friendship("Declaration of Friendship", false, false),
        Luxuries("luxuries", false, true), OpenBorders("Open Borders", false, true), Embassy("Embassy", false, true), ResearchAgreement("Research Agreement", false, true),
        Gifts("gifts", false, true), Demands("demands", false, true),
        // Re-arming soldiers with gold above the reserve: in a war with a great power by default; at peace only when the court opens it
        Rearm("re-arming in war", false, true), RearmAtPeace("re-arming at peace", false, false);
        // "re-arming" was Rearm's label before "in war" was said (a court read "re-arming" open and "re-arming at peace" barred as a contradiction, Run 29)
        companion object { fun of(label: String) = entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) } ?: Rearm.takeIf { label.trim().equals("re-arming", ignoreCase = true) } }
    }
    /** civID -> the court's own word on its officials' acts, where it differs from [Act.byDefault] (a standing order of the ruler, kept by the host) */
    val officialsOrders = ConcurrentHashMap<String, Map<Act, Boolean>>()
    /** May this civ's officials do [act] on their own now? */
    @yairm210.purity.annotations.Readonly
    fun officialsMay(civ: Civilization, act: Act): Boolean =
        civ.civID !in ruledCivs || !act.fixed && (officialsOrders[civ.civID]?.get(act) ?: act.byDefault)
    /** May the officials re-arm this civ's units now: at war with a great power (city-states and barbarians do not count), or at peace where the court allows it */
    fun mayRearm(civ: Civilization): Boolean = officialsMay(civ, Act.Rearm) &&
        (civ.getKnownCivs().any { it.isMajorCiv() && civ.isAtWarWith(it) } || civ.civID !in ruledCivs || officialsMay(civ, Act.RearmAtPeace))

    /** Strategic resources a ruled civ's own pending proposals offer (name -> amount): the officials' re-arming and their construction choices must not spend
     *  them out from under the deal (a Horseman queued while a Horses-for-Copper proposal waited would void it, audit 2026-10-04) */
    @Volatile var promised: ((Civilization) -> Map<String, Int>)? = null
    /** What a civ's pending proposals promise of [resource]: nothing for an unruled civ */
    @yairm210.purity.annotations.Readonly
    fun promisedOf(civ: Civilization, resource: String): Int = if (civ.civID in ruledCivs) promised?.invoke(civ)?.get(resource) ?: 0 else 0
    /** Would re-arming this unit take a resource [promised] holds, leaving less than the proposal offers? Judged on every newer arm it could take */
    fun spendsPromised(unit: MapUnit, promised: Map<String, Int>): Boolean = promised.isNotEmpty() && unit.baseUnit.getUpgradeUnits(unit.cache.state)
        .mapNotNull { unit.civ.gameInfo.ruleset.units[it] }.map { unit.civ.getEquivalentUnit(it) }
        .any { to -> to.getResourceRequirementsPerTurn(unit.cache.state).any { (resource, needs) -> resource in promised && !unit.requiresResource(resource)
            && unit.civ.getResourceAmount(resource) - needs < promised[resource]!! } }

    /** A soldier's might, as every reckoning of ours weighs one: the greater of its strength and its ranged strength */
    @yairm210.purity.annotations.Readonly
    fun might(unit: MapUnit): Int = unit.baseUnit.strength.coerceAtLeast(unit.baseUnit.rangedStrength)

    /** Far behind its civ's best arms: less than half the might of its strongest land soldier, engines aside. The one test for both uses - the attack
     *  posture keeping it home (outclassed) and the host's weak-garrison warning and relief (RulerHost.poorDefender); a warship's might never counts */
    @yairm210.purity.annotations.Readonly
    fun farBehind(unit: MapUnit): Boolean {
        val best = unit.civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit && !it.baseUnit.isProbablySiegeUnit() }.maxOfOrNull { might(it) } ?: return false
        return might(unit) * 2 < best
    }

    /** A ruled civ's land soldier far behind its best arms ([farBehind]): under the attack posture it is not sent to a siege or an assault but stays home
     *  as garrison and reserve. Warriors, Lancers and a Pikeman thrown at Information-era guns died for nothing (Run 16) */
    @yairm210.purity.annotations.Readonly
    fun outclassed(unit: MapUnit): Boolean {
        if (unit.civ.civID !in ruledCivs || !unit.isMilitary() || !unit.baseUnit.isLandUnit || unit.baseUnit.isProbablySiegeUnit()) return false
        if ((postures[unit.civ.civID]?.stance ?: "attack") != "attack") return false
        return farBehind(unit)
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
            val nearHome = unit.civ.cities.flatMap { it.getCenterTile().getTilesInDistance(FIELDS) }.toHashSet()
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

    /** civID -> faith the officials leave untouched (a ruler's standing order, as goldReserve is for gold) */
    val faithReserve = ConcurrentHashMap<String, Int>()
    /** The faith a civ's officials may spend on their own: what lies above the ruler's reserve for a ruled civ, all of it for any other. Every faith spender
     *  of the officials' measures its price against this - a purchase, and the pantheon, which was bought past the reserve till audit 2026-10-04 */
    @yairm210.purity.annotations.Readonly
    fun faithAboveReserve(civ: Civilization): Int =
        if (civ.civID !in ruledCivs || civ.civID in underFloor) civ.religionManager.storedFaith
        else ((civ.religionManager.storedFaith - (faithReserve[civ.civID] ?: 0)) * officialsFaithShare(civ)).toInt()

    /** The share of the faith above the reserve that a ruled civ's officials spend on their own: the court's Faith weight read as a bias - at the neutral 5
     *  or more, all of it, as the engine's own officials would; below, weight/5 of it; at 0, none, and the faith lies for the court to spend. Unruled: all */
    @yairm210.purity.annotations.Readonly
    fun officialsFaithShare(civ: Civilization): Float =
        if (civ.civID !in ruledCivs) 1f else (civ.getPersonality()[com.unciv.models.ruleset.nation.PersonalityValue.Faith] / 5f).coerceIn(0f, 1f)
    /** The gold a civ's officials may spend on their own, as [faithAboveReserve] is for faith */
    @yairm210.purity.annotations.Readonly
    fun goldAboveReserve(civ: Civilization): Int = civ.gold - (if (civ.civID in ruledCivs && civ.civID !in underFloor) goldReserve[civ.civID] ?: 0 else 0)

    /** Ruled civs whose officials' turn runs under the floor now: their reserves are set aside, out of sight */
    private val underFloor: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The officials' whole turn for a ruled civ under one spending floor: the gold and faith the ruler keeps are set aside before it and put back after,
     *  so every spender of theirs - a purchase, an upgrade, a gift, a deal's gold, the pantheon, a Great Person - sees only what lies above the reserves
     *  and chooses within it (the faith reserve once refused after the officials had picked, so nothing at all was bought). Unruled civs play as ever */
    fun <T> underTheFloor(civ: Civilization, turn: () -> T): T {
        if (civ.civID !in ruledCivs) return turn()
        val gold = (goldReserve[civ.civID] ?: 0).coerceIn(0, civ.gold.coerceAtLeast(0))
        val stored = civ.religionManager.storedFaith.coerceAtLeast(0)
        val reserve = (faithReserve[civ.civID] ?: 0).coerceIn(0, stored)
        // The court's Faith weight keeps back what it does not give the officials of the faith above the reserve (officialsFaithShare)
        val faith = reserve + ((stored - reserve) * (1f - officialsFaithShare(civ))).toInt()
        civ.addGold(-gold); civ.religionManager.storedFaith -= faith; underFloor += civ.civID
        try { return turn() } finally { underFloor -= civ.civID; civ.addGold(gold); civ.religionManager.storedFaith += faith }
    }
    /** Set by the host: told of every faith purchase the officials make for a ruled civ - (civ, faith, "Great Scientist in Persepolis") */
    @Volatile var faithSpent: ((Civilization, Int, String) -> Unit)? = null
    fun noteFaithSpent(civ: Civilization, faith: Int, what: String) { if (civ.civID in ruledCivs) faithSpent?.invoke(civ, faith, what) }
    /** Set by the host: the beliefs the officials chose for a ruled civ - (civ, what they did with them: "founded our pantheon", "founded Buddhism",
     *  "enhanced Buddhism", "added to Buddhism", the beliefs). The engine tells the other players of a founding, never the founder */
    @Volatile var beliefsChosen: ((Civilization, String, List<com.unciv.models.ruleset.Belief>) -> Unit)? = null
    fun noteBeliefs(civ: Civilization, what: String, beliefs: List<com.unciv.models.ruleset.Belief>) { if (civ.civID in ruledCivs) beliefsChosen?.invoke(civ, what, beliefs) }
    /** Set by the host: told of a free technology taken for a ruled civ - (civ, technology, the goal it was taken toward or null) */
    @Volatile var freeTechTaken: ((Civilization, String, String?) -> Unit)? = null
    /** Set by the host: a building the officials sold for a ruled civ - (civ, city, building, the gold it fetched, why: "to free its Aluminum for the spaceship") */
    @Volatile var buildingSold: ((Civilization, com.unciv.logic.city.City, String, Int, String) -> Unit)? = null

    /** The officials' faith purchase for any civ: a ruled civ's keeps above its faith reserve and off its never-build list, and is told to the host
     *  (Persia's officials spent 1,600 faith in a turn with no line, audit 2026-10-03). Unruled civs buy as the engine always has */
    fun officialsBuyWithFaith(city: com.unciv.logic.city.City, construction: com.unciv.models.ruleset.INonPerpetualConstruction): Boolean {
        val civ = city.civ
        val cost = construction.getStatBuyCost(city, com.unciv.models.stats.Stat.Faith) ?: return false
        if (cost > faithAboveReserve(civ) || city.isDisabledForAutomation(construction)) return false
        val bought = city.cityConstructions.purchaseConstruction(construction, -1, true, com.unciv.models.stats.Stat.Faith)
        if (bought) noteFaithSpent(civ, cost, "${construction.name} in ${city.name}")
        return bought
    }

    /** Set by the host: does the near-victory note name this power now? A ruled realm's officials offer it no Research Agreement */
    @Volatile var nearWinner: ((Civilization) -> Boolean)? = null

    /** May a ruled realm's officials offer [other] a Research Agreement now: never past the ruler's gold reserve, never in a war of ours, never to a power
     *  a step from victory (nine agreements fed Greece's science after its Apollo Program, Run 22). Unruled civs keep the engine's habit */
    fun officialsMayOfferResearch(civ: Civilization, other: Civilization, cost: Int): Boolean {
        if (civ.civID !in ruledCivs) return true
        if (goldAboveReserve(civ) < cost) return false
        if (civ.getKnownCivs().any { it.isMajorCiv() && civ.isAtWarWith(it) }) return false
        return nearWinner?.invoke(other) != true
    }


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

    /** Set by the host: is this unit of a ruled civ under the ruler's own order, of any kind - a march as much as a garrison? The officials' routines that
     *  draft units of their own (the settler escort, the siege, the retreat's swap) leave it where its order has it */
    @Volatile var hasOrder: ((MapUnit) -> Boolean)? = null
    @yairm210.purity.annotations.Readonly
    fun isUnderOrders(unit: MapUnit) = unit.civ.civID in ruledCivs && hasOrder?.invoke(unit) == true

    /** A ruled civ at war under the defend posture: its soldiers and planes keep to home ground and out of every enemy city's guns, wherever the automation
     *  would otherwise send them. The one test behind every "defending" site (it was written three times) */
    @yairm210.purity.annotations.Readonly
    fun defending(civ: Civilization) = postures[civ.civID]?.stance == "defend" && civ.isAtWar()

    /** A soldier of a ruled civ that stays home: under the ruler's own order whatever the season, or at war in a defending host, in the home reserve, or
     *  outclassed. No routine of the officials' sends it abroad - not the siege, the settler escort, a ruin's lure - and its own turn holds home ground
     *  (UnitAutomation). The one test behind the posture sites, which each had their own trio before audit 2026-10-04 */
    @yairm210.purity.annotations.Readonly
    fun staysHome(unit: MapUnit) = isUnderOrders(unit) || unit.isMilitary() && unit.civ.isAtWar() && (defending(unit.civ) || inReserve(unit) || outclassed(unit))

    /** A step toward a tile that may not open after all (the path reckoning and the march disagree): taken if it can be, else the unit stands where it is.
     *  For the moves this fork added to the officials' routines - an unreachable step there once stopped the whole turn (Run 20, T187) */
    fun tryHeadTowards(unit: MapUnit, tile: Tile): Boolean =
        try { unit.movement.headTowards(tile); true } catch (_: com.unciv.logic.map.mapunit.movement.UnitMovement.UnreachableDestinationException) { false }

    /** Called with (civ, unit, why) when a ruled civ's unit could not be moved by the officials this turn: the turn goes on without it */
    @Volatile var unitNotMoved: ((Civilization, MapUnit, String) -> Unit)? = null
    /** A ruled civ's unit moved by [move]: whatever goes wrong costs that unit its turn, never the turn itself. Other civs keep the engine's habit */
    /** Set by the host only when a regression run plants a failure (RULER_FAULT): throws once at the named site, for the given civ or any */
    @Volatile var fault: ((String, Civilization?) -> Unit)? = null

    fun guarded(unit: MapUnit, move: () -> Unit) {
        if (unit.civ.civID !in ruledCivs) return move()
        try { fault?.invoke("move", unit.civ); move() } catch (ex: Exception) {
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

    /** Called with (civ, unit, why) when a ruled civ's unit is disbanded by the engine's own hand: the routines that know their reason say it before the
     *  disbanding ("to free its Uranium for the spaceship"), and the sink every disbanding passes through ([MapUnit.disband]) says what it can of the rest
     *  (null: no more than that it was disbanded). The host keeps the first word it hears of a unit, so a reason given is never overwritten by the sink's */
    @Volatile var unitDisbanded: ((com.unciv.logic.civilization.Civilization, MapUnit, String?) -> Unit)? = null
    fun noteDisbanded(unit: MapUnit, why: String?) { if (unit.civ.civID in ruledCivs) unitDisbanded?.invoke(unit.civ, unit, why) }

    /** Called with (civ, unit, why) when a ruled civ's unit goes by neither battle nor disbanding - used up by its own work ([MapUnit.consume]) or killed by the
     *  ground it stood on - before it goes: the host otherwise could only guess at "disbanded or used up". [why] finishes "Our X #id <where> ..." */
    @Volatile var unitExpended: ((Civilization, MapUnit, String) -> Unit)? = null
    fun noteExpended(unit: MapUnit, why: String) { if (unit.civ.civID in ruledCivs) unitExpended?.invoke(unit.civ, unit, why) }

    /** Called with (civ, unit, where it stood) for every unit of a ruled civ that is destroyed, from the one sink every end passes through ([MapUnit.destroy]):
     *  a battle, a capture, a disbanding, work done, a nuclear blast, an air wing drowned with its carrier, a unit with no tile left to stand on. The sources
     *  that know the cause tell it by noteLoss, noteExpended or noteDisbanded; the host reads those first and this last, so a death no source claimed is told
     *  as a death where it stood, never guessed at. A unit re-armed or captured is destroyed and made anew under its number: the host's roll-call never asks after those */
    @Volatile var unitDestroyed: ((Civilization, MapUnit, Tile) -> Unit)? = null
    fun noteDestroyed(unit: MapUnit, where: Tile) { if (unit.civ.civID in ruledCivs) unitDestroyed?.invoke(unit.civ, unit, where) }

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

    /** A nuclear blow told whole, as the engine never tells it (each victim hears of its own tile, the attacker of nothing): who launched which weapon at
     *  where, the cities in its blast with the walls' health after or razed, and every unit it killed by owner. Each unit killed is also a loss by noteLoss,
     *  the weapon named as the killer, so the roll-call and the trail have it as any death in battle */
    class NukeFall(val attacker: Civilization, val weapon: String, val target: Tile) {
        class CityHit(val city: com.unciv.logic.city.City, val razed: Boolean)
        val cities = ArrayList<CityHit>()
        val killed = ArrayList<MapUnit>()
    }
    @Volatile var nuked: ((NukeFall) -> Unit)? = null
    /** Told when the attacker or any power struck is a ruled civ */
    fun noteNuke(fall: NukeFall) {
        if (fall.attacker.civID in ruledCivs || fall.cities.any { it.city.civ.civID in ruledCivs } || fall.killed.any { it.civ.civID in ruledCivs }) nuked?.invoke(fall)
    }
}
