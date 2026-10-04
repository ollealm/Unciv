package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.RulerOverrides
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.app.desktop.ruler.Prose.plural

/**
 *  Diplomacy a ruler can order. A proposal to an unadvised power is weighed by its officials on their turn; one to an advised court waits
 *  for that court's advisor. Either way the engine tells the host what became of it ([RulerOverrides.tradeAnswered]) and the chancery log
 *  says so, with the terms. Every offer made to an advised court waits for its ruler in the same way (answerOffers).
 */
/** Turns the officials of both sides leave terms alone after a court refused them: the engine's own wait after a human player's refusal */
/** The engine's own wait after its officials refuse a proposal of friendship (NextTurnAutomation): a court's refusal holds the proposer off as long */
private const val FRIENDSHIP_REFUSED_WAITS = 10
/** What proposeTrade says when the same offer of theirs waited on our desk and was accepted instead */
private const val TAKEN_THEIRS = "their offer was taken"
/** The war leave, said once: the host refuses every way of declaring war (the order, a deal that has us declare it, a refused demand not to attack) without it -
 *  a sovereign has it, an advisor only by the ruler's plain word (record_rulers_will) or at an audience. The officials never declare one (RulerOverrides.Act.War) */
const val WAR_LEAVE = "war is the ruler's alone to declare, and they have not given leave to declare it on your own - bring the case for war to the ruler"
/** A friendship is no trade, but it is offered, answered and renewed beside them under this name */
private const val FRIENDSHIP = "Declaration of Friendship"
/** A treaty's duration in a brief: it ticks down, so terms are compared without it */
private val DURATION = Regex(" for \\d+ turns?\\b")
/** A Research Agreement as a deal in force reads it, both sides alike: one wording for the loss of it */
private val RESEARCH_PAID = Regex("we give Research Agreement \\((\\d+) gold from each side\\); they give Research Agreement \\(\\d+ gold from each side\\)")

class RulerDiplomacy(private val log: (Civilization, String) -> Unit, private val event: (String) -> Unit = {}) {

    private val hiddenOffers = setOf(TradeOfferType.City, TradeOfferType.Introduction, TradeOfferType.Technology)

    /** Our open proposals: (our civ, their civ, terms) - each settled by its own record when the engine reports its answer */
    val pending = ArrayList<Triple<String, String, String>>()
    /** Offers that left a desk by another hand than its owner's answer - withdrawn, replaced, lapsed - keyed "desk<from": (turn, what became of it).
     *  An advisor answering from a briefing read before it left (another court's look-in runs beside its own) is told what happened, not "nothing waits" */
    private val gone = HashMap<String, MutableList<Triple<Int, String, String>>>()
    private fun noteGone(desk: Civilization, from: Civilization, terms: String, what: String) {
        val list = gone.getOrPut("${desk.civName}<${from.civName}") { ArrayList() }
        list.removeAll { desk.gameInfo.turns - it.first > 2 }
        list += Triple(desk.gameInfo.turns, terms, what) // several can leave one desk in a turn: each is kept
    }
    /** What became of an offer that left this desk in the last two turns: the latest, or the one [label] names (durations, case and spacing aside, on both sides) */
    private fun goneFrom(desk: Civilization, from: Civilization, label: String? = null) = gone["${desk.civName}<${from.civName}"]
        ?.filter { desk.gameInfo.turns - it.first <= 2 && (label == null || loosely(it.second).contains(loosely(label))) }?.lastOrNull()
        ?.let { "${from.civName}'s offer (${it.second}) ${it.third}" }
    /** Terms as an advisor tends to copy them: durations, case and spacing are what get dropped or changed */
    private fun loosely(text: String) = sansTurns(text).replace(Regex("\\s+"), " ").trim().lowercase()
    private fun isLuxury(game: GameInfo, name: String) = game.ruleset.tileResources[name]?.resourceType?.name == "Luxury"

    /** A court's refusal (or a lapse) holds the officials of both sides off those terms as long as the engine's own refusal holds the proposer's
     *  (TradeRequest.decline sets its waits per kind, by the proposer's weights): the refusing court's officials are held off the same terms as long,
     *  so they do not offer back what their court just refused. The turns held, 0 when the engine holds no kind of these terms */
    private fun holdOff(decliner: Civilization, request: TradeRequest): Int {
        val proposer = decliner.gameInfo.getCivilization(request.requestingCiv)
        val theirs = proposer.getDiplomacyManager(decliner) ?: return 0
        val ours = decliner.getDiplomacyManager(proposer)
        var held = 0
        for (flag in listOf(DiplomacyFlags.DeclinedLuxExchange, DiplomacyFlags.DeclinedResearchAgreement, DiplomacyFlags.DeclinedDefensivePact,
                DiplomacyFlags.DeclinedOpenBorders, DiplomacyFlags.DeclinedEmbassy)) {
            val turns = theirs.getFlag(flag).takeIf { theirs.hasFlag(flag) } ?: continue
            held = maxOf(held, turns)
            if (ours != null && ours.getFlag(flag) < turns) ours.setFlag(flag, turns)
        }
        return held
    }

    /** What one side of a request can no longer give, named from [civ]'s side ("our Horses x1", "Greece's Copper x1"); the request holds the trade as [receiver] sees it */
    private fun shortOf(request: TradeRequest, receiver: Civilization, civ: Civilization): List<String> {
        val game = civ.gameInfo
        val logic = TradeLogic(receiver, civ)
        return request.trade.theirOffers.filter { offer -> logic.theirAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "our ${it.brief(game)}" } +
            request.trade.ourOffers.filter { offer -> logic.ourAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "${receiver.civName}'s ${it.brief(game)}" }
    }

    /** A deal in force gives [civ] these luxuries: its other requests still asking for any of them are withdrawn, for a second supply adds nothing and would be paid for twice */
    private fun withdrawDuplicates(civ: Civilization, gained: Collection<String>, except: TradeRequest) {
        val game = civ.gameInfo
        val luxuries = gained.filter { isLuxury(game, it) }.toSet()
        if (luxuries.isEmpty()) return
        for (other in game.civilizations) {
            // a request holds the trade as its receiver sees it: what the receiver gives is what the requester gets
            val twice = other.tradeRequests.filter { it !== except && it.requestingCiv == civ.civID && !isPeace(it) && it.trade.ourOffers.any { offer -> offer.name in luxuries } }
            for (request in twice) {
                other.tradeRequests.remove(request)
                val what = request.trade.ourOffers.filter { it.name in luxuries }.joinToString { it.name }
                noteGone(other, civ, request.trade.reverse().brief(game), "was withdrawn by ${civ.civName}: $what now comes to them by another deal")
                settle(request, other, "duplicate")
            }
        }
    }

    /** The words an offer goes by in an order and in the options: its name - or, for a call to war, which carries a civ's id for a name, the power named
     *  ("declaration of war on Aztecs"; Japan's call on Songhai failed on the id alone, Run 21) */
    private fun TradeOffer.word(game: GameInfo) = if (type == TradeOfferType.WarDeclaration) "declaration of war on ${game.civilizations.firstOrNull { it.civID == name }?.civName ?: name}" else name
    /** An order's item against an offer: its name, or its words with or without the article the briefing's wording carries ("a declaration of war on Aztecs") */
    private fun TradeOffer.isCalled(item: String, game: GameInfo) = name.equals(item, ignoreCase = true) || word(game).equals(item.trim().replace(Regex("^an? ", RegexOption.IGNORE_CASE), ""), ignoreCase = true)

    fun TradeOffer.brief(game: GameInfo, turns: Int = duration): String = when (type) {
        // These three carry an id where every other offer carries its name
        TradeOfferType.WarDeclaration -> "a declaration of war on ${game.civilizations.firstOrNull { it.civID == name }?.civName ?: name}"
        TradeOfferType.Introduction -> "an introduction to ${game.civilizations.firstOrNull { it.civID == name }?.civName ?: name}"
        TradeOfferType.City -> "the city of ${game.getCities().firstOrNull { it.id == name }?.name ?: name}"
        else -> name + (if (type.numberType != TradeOfferType.TradeTypeNumberType.None) " x$amount" else if (name == Constants.researchAgreement && amount > 0) " ($amount gold from each side)" else "") + (if (duration > 0) " for $turns turn${plural(turns)}" else "")
    }
    /** Both sides named, so the direction of the gold is never misread. Every match of terms goes through this one wording */
    // One deal, one length: each side's half ticks down on its own realm's turn, so between turns the halves differ by one ("for 7 turns; ... for 8 turns");
    // the longer is the deal's
    fun Trade.brief(game: GameInfo): String { val turns = (ourOffers + theirOffers).maxOfOrNull { it.duration } ?: 0
        return "we give ${ourOffers.joinToString { it.brief(game, turns) }.ifEmpty { "nothing" }}; they give ${theirOffers.joinToString { it.brief(game, turns) }.ifEmpty { "nothing" }}" }

    /** Offers of ours out in the world, by partner and terms, so a new one the officials made can be told from the ruler's */
    private val oursOut = HashMap<String, Set<String>>()
    private fun oursOutNow(civ: Civilization) = civ.gameInfo.civilizations.flatMap { other -> other.tradeRequests.filter { it.requestingCiv == civ.civID }.map { "${other.civName}: ${it.trade.reverse().brief(civ.gameInfo)}" } +
        other.popupAlerts.filter { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID }.map { "${other.civName}: $FRIENDSHIP" } }.toSet()
    fun noteRequests(civ: Civilization) { oursOut[civ.civName] = oursOutNow(civ) }
    /** The officials' own proposals still out, as [pending] holds the ruler's: settled with the engine's word, so the realm hears what became of them */
    val officialsPending = ArrayList<Triple<String, String, String>>()

    /** After the turn: what the officials proposed on their own (what was asked of us waits for the ruler, so that half is not theirs) */
    fun officialsDealings(civ: Civilization) {
        for (offer in oursOutNow(civ) - (oursOut[civ.civName] ?: emptySet())) {
            // A friendship the officials proposed: the answer comes as the engine's own notice
            if (offer.endsWith(": $FRIENDSHIP")) { if ("${offer.substringBefore(":")}<${civ.civName}" !in friendshipsAsked) log(civ, "The officials proposed a Declaration of Friendship to ${offer.substringBefore(":")}${civ.gameInfo.civilizations.firstOrNull { it.civName == offer.substringBefore(":") }?.let { "; ${whoAnswers(it)}" } ?: ""}.${nearVictoryNote(civ.gameInfo.civilizations.firstOrNull { it.civName == offer.substringBefore(":") })}"); continue }
            if (pending.any { it.first == civ.civName && "${it.second}: ${it.third}" == offer }) continue
            val partner = offer.substringBefore(":")
            val entry = Triple(civ.civName, partner, offer.substringAfter(": "))
            if (entry !in officialsPending) officialsPending += entry
            val other = civ.gameInfo.civilizations.firstOrNull { it.civName == partner }
            val offers = other?.tradeRequests?.firstOrNull { it.requestingCiv == civ.civID && "${other.civName}: ${it.trade.reverse().brief(civ.gameInfo)}" == offer }?.trade?.let { it.ourOffers + it.theirOffers }.orEmpty()
            log(civ, "The officials proposed a deal to $partner: ${entry.third}. ${other?.let { whoAnswers(it).capitalised() } ?: "They answer on their turn"}.${nearVictoryNote(other, offers)}")
        }
        friendshipsAsked.removeAll { it.endsWith("<${civ.civName}") }
    }

    /** A power one step from a victory in play, as a fact: the last spaceship part to come, or every branch of policy a cultural victory asks done. Said beside
     *  a deal with it - an offer, a renewal, an officials' proposal - and never a block: in Run 18 three rivals fed the winner's research to the last turn */
    /** How far [other] stands on one road to victory in play: the steps it has taken of those the road asks, and the fact as the world can count it */
    class VictoryStanding(val road: String, val have: Int, val need: Int, val fact: String)

    /** Every road to victory in play, as [other] stands on it: the one source the near-victory note, the officials' research hold and the renewal hold read */
    fun victoryStandings(other: Civilization): List<VictoryStanding> {
        val game = other.gameInfo
        val inPlay = game.gameParameters.victoryTypes
        val standings = ArrayList<VictoryStanding>()
        if ("Scientific" in inPlay) game.ruleset.victories["Scientific"]?.requiredSpaceshipPartsAsCounter?.values?.sum()?.takeIf { it > 0 }?.let { total ->
            val have = other.victoryManager.currentsSpaceshipParts.values.sum()
            standings += VictoryStanding("Scientific", have, total, "${other.civName} has $have of $total parts of its spaceship") }
        if ("Diplomatic" in inPlay && other.victoryManager.getUNBuildingAndOwnerNames().second != null) {
            // What the world can count: the city-states allied to it, each a vote for it. A great power's vote is its own to cast (and a power may not vote for itself)
            val allies = game.civilizations.count { it.isCityState && it.isAlive() && it.allyCiv == other }
            val needed = votesNeeded(game)
            standings += VictoryStanding("Diplomatic", allies, needed, "${other.civName} holds $allies of the $needed votes needed for a diplomatic victory, in the city-states allied to it")
        }
        if ("Cultural" in inPlay) game.ruleset.victories["Cultural"]?.milestoneObjects?.let { milestones ->
            milestones.firstOrNull { it.type == com.unciv.models.ruleset.MilestoneType.CompletePolicyBranches }?.params?.firstOrNull()?.toIntOrNull()?.let { needed ->
                // The last step is the project the victory names: the branches and it are the steps, so a power with every branch complete is a step away
                val last = milestones.firstOrNull { it.type == com.unciv.models.ruleset.MilestoneType.BuiltBuilding }?.params?.firstOrNull()
                val done = other.policies.completedBranches.size.coerceAtMost(needed)
                standings += VictoryStanding("Cultural", done, needed + (if (last == null) 0 else 1),
                    if (done >= needed && last != null) "${other.civName} has completed the $needed branches of policy a cultural victory asks, and needs only the $last"
                    else "${other.civName} has completed $done of the $needed branches of policy a cultural victory asks")
            } }
        return standings
    }

    /** The near-victory fact: a power a step from victory on any road in play, one threshold for every road */
    fun nearVictory(other: Civilization): String? = victoryStandings(other).firstOrNull { it.have >= it.need - 1 }?.fact

    /** The votes needed to be elected world leader, as VictoryManager reckons them (the original game's own sum on the votes there are) */
    fun votesNeeded(game: GameInfo): Int {
        // Counted as once the United Nations stands: its holder's second vote included
        val total = game.civilizations.count { !it.isBarbarian && !it.isSpectator() && !it.isDefeated() } + 1
        return if (total > 28) total * 35 / 100 else total * (67 - (1.1 * total).toInt()) / 100 + 1
    }

    private fun nearVictoryNote(other: Civilization?, offers: List<TradeOffer> = emptyList()) = other?.let { nearVictory(it) }?.let { fact ->
        " (note: $fact" + (if (offers.any { it.name == Constants.researchAgreement }) "; a research agreement adds to its learning" else "") + ")" } ?: ""

    /** [other]'s arms against ours, in the words of a comparison: "stronger than ours, clearly", "about as strong as ours" */
    fun armsAgainstOurs(civ: Civilization, other: Civilization): String {
        val ours = civ.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force).coerceAtLeast(1).toDouble()
        val theirs = other.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force).coerceAtLeast(1).toDouble()
        val ratio = maxOf(ours, theirs) / minOf(ours, theirs)
        return if (ratio < 1.15) "about as strong as ours" else "${if (theirs > ours) "stronger" else "weaker"} than ours, ${Prose.lead(ratio)}"
    }

    /** Who answers what is put to [other], in one wording wherever it is said: an advised court's advisor at its look-in, or the officials on their turn */
    fun whoAnswers(other: Civilization): String =
        if (other.civID in RulerOverrides.ruledCivs) "${other.civName} is an advised court: its advisor answers at its next look-in" else "they answer on their turn"
    private fun String.capitalised() = replaceFirstChar { it.uppercase() }


    /** Friendships proposed by the ruler's own order since the last bookkeeping, keyed "them<us": told by their own line, not as the officials' */
    private val friendshipsAsked = HashSet<String>()

    /** Partners whose last word on a proposal of ours was a counteroffer, keyed "us<them": the offer now on our desk is announced as an answer, not a fresh approach */
    private val counters = HashSet<String>()

    private fun entryFor(civ: Civilization, receiver: Civilization, request: TradeRequest, among: List<Triple<String, String, String>> = pending) = among.firstOrNull {
        it.first == civ.civName && it.second == receiver.civName && (if (it.third == "peace") isPeace(request) else it.third == request.trade.reverse().brief(civ.gameInfo)) }

    /**
     *  The word on one request of ours - from the engine ([RulerOverrides.tradeAnswered]) or from our own dealings with a held offer.
     *  The proposal is settled by its record and the log says how it ended - the ruler's own, or the officials' (told as theirs).
     */
    fun settle(request: TradeRequest, receiver: Civilization, outcome: String) {
        val civ = receiver.gameInfo.civilizations.firstOrNull { it.civID == request.requestingCiv } ?: return
        if (outcome == "accepted") {
            withdrawDuplicates(civ, request.trade.ourOffers.map { it.name }, request)
            withdrawDuplicates(receiver, request.trade.theirOffers.map { it.name }, request)
        }
        val ours = entryFor(civ, receiver, request)
        // Made by the officials this very turn and answered before the turn ended: it never reached officialsPending (written after the turn) - it is theirs all the same
        // (Run 23: an agreement with England, signed mid-siege, read as gold gone "without the officials' hand")
        if (ours == null && entryFor(civ, receiver, request, officialsPending) == null && outcome == "accepted") {
            val terms = request.trade.reverse().brief(civ.gameInfo)
            val gold = request.trade.theirOffers.filter { it.name == Constants.researchAgreement || it.type == TradeOfferType.Gold }.sumOf { it.amount }
            if (gold > 0) officialsDealGold[civ.civName] = (officialsDealGold[civ.civName] ?: 0) + gold
            log(civ, "The officials proposed a deal to ${receiver.civName} ($terms), and it was accepted on their turn - the deal is in force${if (gold > 0) "; it took $gold gold from the treasury" else ""}.${nearVictoryNote(receiver)}")
            return
        }
        val entry = ours ?: entryFor(civ, receiver, request, officialsPending) ?: return
        val dealGold = if (ours == null && outcome == "accepted") request.trade.theirOffers.filter { it.name == Constants.researchAgreement || it.type == TradeOfferType.Gold }.sumOf { it.amount } else 0
        if (dealGold > 0) officialsDealGold[civ.civName] = (officialsDealGold[civ.civName] ?: 0) + dealGold
        if (ours != null) pending.remove(entry) else officialsPending.remove(entry)
        val answer = when (outcome) {
            "accepted" -> "was accepted - the deal is in force"
            "declined" -> "was refused"
            "countered" -> { counters += "${civ.civName}<${receiver.civName}"; "got a counteroffer, which now waits on our desk for your answer (answerOffers accept or refuse)" }
            "lapsed" -> "waited $DESK_WAITS turns at their court unanswered and lapsed as refused"
            "invalid" -> "could not be honoured when their officials weighed it (a resource or the treasury had changed) and was dropped"
            "voided" -> shortOf(request, receiver, civ).let { short -> if (short.isEmpty()) "lapsed: one side could no longer supply what was offered (a resource ran out, or a treaty state changed)"
                else "lapsed: ${short.joinToString(" and ")} ${if (short.size == 1) "was" else "were"} no longer there to give" }
            "withdrawn" -> "was withdrawn: it asked the same swap as the offer of theirs we accepted, and a deal is made once"
            "duplicate" -> "was withdrawn: what it asked for now comes to us by another deal, and a second supply of a luxury adds nothing"
            else -> "ended without a recorded answer"
        }
        log(civ, "${if (ours != null) "Our proposal" else "The officials' proposal"} to ${receiver.civName} (${entry.third}) $answer${if (dealGold > 0) "; it took $dealGold gold from the treasury" else ""}.")
    }

    /** Gold the officials' own deals took this turn, by realm: told with the deal, so the bookkeeping does not call it unexplained */
    val officialsDealGold = HashMap<String, Int>()

    /** Called after each turn: a proposal whose request left the partner's desk with no word from the engine (war sweeps a desk clean, a fallen realm has none) is closed here, so nothing waits for ever */
    fun sweepLost(civ: Civilization) {
        for ((list, whose) in listOf(pending to "Our proposal", officialsPending to "The officials' proposal")) for (entry in list.filter { it.first == civ.civName }.toList()) {
            val other = civ.gameInfo.civilizations.firstOrNull { it.civName == entry.second }
            if (other != null && other.tradeRequests.any { it.requestingCiv == civ.civID && (if (entry.third == "peace") isPeace(it) else it.trade.reverse().brief(civ.gameInfo) == entry.third) }) continue // still waiting
            list.remove(entry)
            log(civ, "$whose to ${entry.second} (${entry.third}) " + when {
                !civ.isAlive() -> "lapsed with the fall of ${civ.civName}"
                other == null || !other.isAlive() -> "lapsed with the fall of ${entry.second}"
                civ.isAtWarWith(other) -> "was swept off the table by the war between us"
                else -> "left their desk without a recorded answer"
            } + ".")
        }
    }

    private fun Civilization.known(name: String): Civilization =
        getKnownCivs().firstOrNull { it.civName == name && it.isAlive() } ?: throw IllegalArgumentException("We have not met $name, or they are gone")

    private fun isGold(offer: TradeOffer) = offer.type == TradeOfferType.Gold || offer.type == TradeOfferType.Gold_Per_Turn
    /** The goods of one side with the numbers struck out: amounts, durations and a gold sweetener differ between a deal and its mirror, the goods do not */
    private fun goodsOf(offers: List<TradeOffer>) = offers.filterNot(::isGold).ifEmpty { offers }.map { it.name }.toSortedSet()
    /** Two trades seen from the same side that swap the same goods */
    private fun sameSwap(a: Trade, b: Trade) = goodsOf(a.ourOffers) == goodsOf(b.ourOffers) && goodsOf(a.theirOffers) == goodsOf(b.theirOffers)

    /** {with, give: [offers], want: [offers]}; an offer is {item, amount?} - see options.diplomacy for what can be traded */
    fun proposeTrade(civ: Civilization, json: JsonValue, leave: Boolean = true): String {
        val other = civ.known(json.getString("with"))
        val game = civ.gameInfo
        if (other.isCityState) throw IllegalArgumentException("City-states do not trade; send them a gift instead")
        val logic = TradeLogic(civ, other)
        val trade = Trade()
        fun add(list: JsonValue?, available: List<TradeOffer>, into: MutableList<TradeOffer>, side: String, owner: Civilization) {
            list?.forEach { entry ->
                val item = entry.getString("item")
                val offer = available.firstOrNull { it.isCalled(item, game) && it.amount > 0 }
                    ?: throw IllegalArgumentException(inForce(civ, other, item) ?: cannotPassOn(owner, item, side) ?: cannotCallToWar(civ, other, item, side)
                        ?: "$side cannot offer '$item' right now; tradeable: ${available.filter { it.amount > 0 && it.type !in hiddenOffers }.joinToString { it.word(game) + if (it.type.numberType != TradeOfferType.TradeTypeNumberType.None) " x" + it.amount else "" }}")
                val copy = offer.copy()
                // A treaty runs a fixed length: an amount given for one (the duration, usually) is ignored
                val wanted = if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.None) offer.amount else if (entry.has("amount")) entry.getInt("amount") else if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.Gold) throw IllegalArgumentException("'$item' needs an amount") else 1
                if (wanted < 1 || wanted > offer.amount) throw IllegalArgumentException("$side can offer at most ${offer.amount} of '$item', not $wanted")
                copy.amount = wanted
                into.add(copy)
            }
        }
        if (json.get("give")?.any { it.getString("item").equals(Constants.researchAgreement, ignoreCase = true) } == true ||
            json.get("want")?.any { it.getString("item").equals(Constants.researchAgreement, ignoreCase = true) } == true)
            researchAgreementLacks(civ, other)?.let { throw IllegalArgumentException("A research agreement with ${other.civName} cannot be made: $it") }
        if (listOf("give", "want").any { side -> json.get(side)?.any { it.getString("item").equals(Constants.defensivePact, ignoreCase = true) } == true })
            defensivePactLacks(civ, other)?.takeIf { inForce(civ, other, Constants.defensivePact) == null }?.let { throw IllegalArgumentException("A defensive pact with ${other.civName} cannot be made: $it") }
        add(json.get("give"), logic.ourAvailableOffers, trade.ourOffers, "We", civ)
        if (!leave) trade.ourOffers.firstOrNull { it.type == TradeOfferType.WarDeclaration }?.let { throw IllegalArgumentException("A deal in which we give ${it.brief(game)} is a declaration of war, and $WAR_LEAVE. Asking them for one stays open") }
        add(json.get("want"), logic.theirAvailableOffers, trade.theirOffers, other.civName, other)
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) throw IllegalArgumentException("An empty trade")
        if (!TradeEvaluation().isTradeValid(trade, civ, other)) throw IllegalArgumentException("That trade is not valid right now")
        // The same swap proposed back at a power whose offer of it already waits on our desk would be accepted on both sides and made twice.
        // Word for word the same is what the advisor wants: their offer is taken. Different terms for the same goods are the advisor's to weigh
        civ.tradeRequests.firstOrNull { it.requestingCiv == other.civID && !isPeace(it) && sameSwap(it.trade, trade) }?.let { held ->
            if (held.trade.brief(game) == trade.brief(game))
                return "${other.civName} had already offered exactly this, so $TAKEN_THEIRS instead of proposing it back. " + answerOffer(civ, other.civName, "accept", held.trade.brief(game))
            throw IllegalArgumentException("${other.civName} has offered you this already (${held.trade.brief(game)}) - answer it with answerOffers rather than proposing it back") }
        // A luxury we have, have asked another power for, or are offered already: a second supply adds nothing to the people's mood and is paid for twice
        for (luxury in trade.theirOffers.map { it.name }.filter { isLuxury(game, it) }) {
            if ((civ.getCivResourcesByName()[luxury] ?: 0) > 0)
                throw IllegalArgumentException("We already have $luxury" + (civ.diplomacy.values.firstOrNull { m -> m.trades.any { t -> t.theirOffers.any { it.name == luxury } } }?.let { " (it comes from ${it.otherCivName} by a deal in force)" } ?: "") + "; a second supply adds nothing to the people's mood")
            game.civilizations.firstOrNull { o -> o != other && o.tradeRequests.any { it.requestingCiv == civ.civID && !isPeace(it) && it.trade.ourOffers.any { g -> g.name == luxury } } }?.let {
                throw IllegalArgumentException("We have already asked ${it.civName} for $luxury and their answer is still to come; if both said yes we would pay for it twice. Ask again if they refuse") }
            civ.tradeRequests.firstOrNull { !isPeace(it) && it.trade.theirOffers.any { g -> g.name == luxury } }?.let {
                throw IllegalArgumentException("${game.getCivilization(it.requestingCiv).civName} already offers us $luxury (${it.trade.brief(game)}) - answer that offer with answerOffers rather than buying it twice") }
        }
        // A TradeRequest holds the trade as the receiver sees it, so it goes out reversed
        val acceptable = TradeEvaluation().isTradeAcceptable(trade.reverse(), other, civ)
        // A second offer for the same goods replaces the first - otherwise both can be accepted and paid for
        // Keyed on the goods asked for; gold is a sweetener, not a good, so two offers that both carry gold are not the same offer
        val asked = trade.theirOffers.filterNot(::isGold).map { it.name }.toSet().ifEmpty { trade.theirOffers.map { it.name }.toSet() }
        val superseded = other.tradeRequests.filter { request -> request.requestingCiv == civ.civID && !isPeace(request) && request.trade.ourOffers.filterNot(::isGold).ifEmpty { request.trade.ourOffers }.any { it.name in asked } }
        // Which of them were the ruler's own: an officials' offer that gives way to ours is not "your earlier offer" (Run 22, their agreement replaced by a renewal)
        val oursSuperseded = superseded.filter { Triple(civ.civName, other.civName, it.trade.reverse().brief(game)) in pending }
        other.tradeRequests.removeAll(superseded.toSet())
        for (old in superseded) noteGone(other, civ, old.trade.brief(game), "was replaced by ${civ.civName} with a newer offer for the same goods")
        // a withdrawn offer cannot be "accepted" later - and only the withdrawn ones go, another offer to the same power for other goods still waits
        for (old in superseded) Triple(civ.civName, other.civName, old.trade.reverse().brief(game)).let { pending.remove(it); officialsPending.remove(it) }
        other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
        val summary = trade.brief(game)
        if (oursSuperseded.isNotEmpty()) log(civ, "Our earlier offer to ${other.civName} (${oursSuperseded.joinToString("; ") { it.trade.reverse().brief(game) }}) was withdrawn in favour of the new one, which asks for the same goods.")
        pending += Triple(civ.civName, other.civName, summary)
        log(civ, "We proposed a trade to ${other.civName}: $summary.")
        val balance = TradeEvaluation().getTradeAcceptability(trade.reverse(), other, civ, true)
        if (other.civID in RulerOverrides.ruledCivs)
            return (if (superseded.isNotEmpty()) "Your earlier pending offer for the same goods was withdrawn. " else "") + "Proposed to ${other.civName}: $summary. ${whoAnswers(other)}, and may accept, refuse or send a counteroffer. (Their officials would reckon it worth $balance to them, but the choice is not theirs.)" + nearVictoryNote(other, trade.ourOffers + trade.theirOffers)
        return (if (superseded.isNotEmpty()) "Your earlier pending offer for the same goods was withdrawn. " else "") + "Proposed to ${other.civName}: $summary. ${whoAnswers(other).capitalised()}; by their own reckoning ${if (balance < -100_000) "they will not part with this at any price" else "this deal is worth $balance to them (they accept at 0 or more)"}, so they are ${if (acceptable) "likely to accept" else if (balance < -100_000) "certain to refuse" else "likely to refuse - sweeten it or ask for less"}." + nearVictoryNote(other, trade.ourOffers + trade.theirOffers)
    }

    /** A call to war on a power that cannot be made, in words: the power is unknown to one side, already at war with the one who would declare, or bound to them by a treaty
     *  (TradeLogic lists a declaration only where the engine's canDeclareWar holds for both); null when the item is no call to war at all */
    private fun cannotCallToWar(civ: Civilization, other: Civilization, item: String, side: String): String? {
        val named = Regex("^(?:an? )?declaration of war on (.+)$", RegexOption.IGNORE_CASE).find(item.trim())?.groupValues?.get(1)?.trim() ?: return null
        val declaring = if (side == "We") civ else other
        val target = civ.gameInfo.civilizations.firstOrNull { it.civName.equals(named, ignoreCase = true) && it.isMajorCiv() } ?: return "$side cannot offer a declaration of war on $named: no great power of that name"
        return "$side cannot offer a declaration of war on ${target.civName}: " + when {
            target == civ || target == other -> "a power does not declare war on a party to the deal"
            !civ.knows(target) || !other.knows(target) -> "both sides must have met them, and ${if (!civ.knows(target)) "we" else other.civName} ${if (!civ.knows(target)) "have" else "has"} not"
            declaring.isAtWarWith(target) -> "${if (declaring == civ) "we are" else "they are"} at war with them already"
            else -> "${if (declaring == civ) "we are" else "they are"} bound by a treaty with them (peace, friendship or a pact) that bars it for now"
        }
    }

    /** A resource [owner] holds only by a deal or from a city-state: the game lets nobody pass such on (Civilization.getPerTurnResourcesWithOriginsForTrade), so the refusal says so */
    private fun cannotPassOn(owner: Civilization, item: String, side: String): String? {
        val supplies = owner.detailedCivResources.filter { it.resource.name.equals(item, ignoreCase = true) && it.amount > 0 }
        if (supplies.isEmpty() || supplies.any { !it.isCityStateOrTradeOrigin() }) return null
        val whose = if (side == "We") "us" else "them"
        val from = supplies.map { supply -> if (supply.origin == Constants.cityStates) "a city-state allied to $whose"
            else owner.diplomacy.values.firstOrNull { manager -> manager.trades.any { trade -> trade.theirOffers.any { it.name == supply.resource.name } } }?.let { "a deal with ${it.otherCivName}" } ?: "a deal" }.distinct()
        return "$side cannot offer ${supplies.first().resource.name}: it comes to $whose only by ${from.joinToString(" and ")}, and what comes by trade or from a city-state cannot be passed on"
    }

    /** What allows research agreements in this world, and whether [civ] has it: the technology (or other source) the rules name */
    private fun enablingLearning(civ: Civilization, who: String): String {
        val sources = civ.gameInfo.ruleset.technologies.values.filter { it.hasUnique(UniqueType.EnablesResearchAgreements) }.map { it.name }
        return if (sources.isEmpty()) "the rules of this world allow them only by a gift no realm of ours holds" else "it takes ${sources.joinToString(" or ")}, which $who not learned"
    }

    /** What a research agreement between the two lacks, as the rules have it (DiplomacyFunctions.canSignResearchAgreementWith); null when nothing does */
    private fun researchAgreementLacks(civ: Civilization, other: Civilization): String? {
        val ours = civ.getDiplomacyManager(other) ?: return "we have not met them"
        val cost = civ.diplomacyFunctions.getResearchAgreementCost(other)
        return when {
            // One cause each: the learning that allows it, or nothing left to learn (DiplomacyFunctions.canSignResearchAgreement)
            civ.tech.allTechsAreResearched() -> "we have nothing left to learn"
            !civ.diplomacyFunctions.canSignResearchAgreement() -> "we cannot sign research agreements yet: ${enablingLearning(civ, "we have")}"
            other.tech.allTechsAreResearched() -> "${other.civName} has nothing left to learn"
            !other.diplomacyFunctions.canSignResearchAgreement() -> "${other.civName} cannot sign research agreements yet: ${enablingLearning(other, "they have")}"
            !civ.diplomacyFunctions.meetsEmbassyRequirementFor(other) -> "we have no shared embassies with ${other.civName}"
            !ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> "there is no Declaration of Friendship between us (propose one with the friendship order first)"
            ours.hasFlag(DiplomacyFlags.ResearchAgreement) || ours.otherCivDiplomacy().hasFlag(DiplomacyFlags.ResearchAgreement) -> "one is already running between us"
            civ.gold < cost -> "it costs $cost gold from each side and our treasury holds ${civ.gold}"
            other.gold < cost -> "it costs $cost gold from each side and ${other.civName} has less than that"
            else -> null
        }
    }

    /** What a defensive pact between the two lacks, as the rules have it (DiplomacyFunctions.canSignDefensivePactWith); null when nothing does */
    private fun defensivePactLacks(civ: Civilization, other: Civilization): String? {
        val ours = civ.getDiplomacyManager(other) ?: return "we have not met them"
        return when {
            !civ.diplomacyFunctions.canSignDefensivePact() -> "we cannot sign defensive pacts yet (it takes the learning that allows it)"
            !other.diplomacyFunctions.canSignDefensivePact() -> "${other.civName} cannot sign defensive pacts yet"
            !civ.diplomacyFunctions.meetsEmbassyRequirementFor(other) -> "we have no shared embassies with ${other.civName}"
            !ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> "there is no Declaration of Friendship between us (propose one with the friendship order first)"
            ours.hasFlag(DiplomacyFlags.DefensivePact) || ours.otherCivDiplomacy().hasFlag(DiplomacyFlags.DefensivePact) || ours.diplomaticStatus == DiplomaticStatus.DefensivePact -> "one is already in force between us"
            else -> null
        }
    }

    fun proposePeace(civ: Civilization, name: String): String {
        val other = civ.known(name)
        if (!civ.isAtWarWith(other)) throw IllegalArgumentException("We are not at war with $name")
        // Their envoy is already waiting with terms: the ruler's "peace" is the answer to it
        heldPeaceOffer(civ, other)?.let { offer ->
            val terms = describeOffer(offer, civ.gameInfo)
            val logic = TradeLogic(civ, other)
            logic.currentTrade.set(offer.trade)
            civ.tradeRequests.remove(offer); peaceOfferedOn.forget("${civ.civName}<${other.civName}")
            settle(offer, civ, "accepted")
            logic.acceptTrade()
            other.addNotification("[${civ.civName}] has accepted your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, com.unciv.logic.civilization.NotificationIcon.Trade, civ.civName)
            log(civ, "By your order: we accepted ${other.civName}'s offer of peace ($terms).")
            return "Peace made with $name on their terms: $terms."
        }
        val diplomacy = civ.getDiplomacyManager(other)!!
        if (diplomacy.hasFlag(DiplomacyFlags.DeclaredWar)) throw IllegalArgumentException("Too soon: ${diplomacy.getFlag(DiplomacyFlags.DeclaredWar)} more turns of war before peace can be discussed")
        if (diplomacy.hasFlag(DiplomacyFlags.DeclinedPeace)) throw IllegalArgumentException("$name declined peace recently; wait ${diplomacy.getFlag(DiplomacyFlags.DeclinedPeace)} turns")
        // One offer of peace at a time: a repeat replaces the one still waiting, so a ruled power never holds two of ours
        val ours = other.tradeRequests.filter { request -> request.requestingCiv == civ.civID && isPeace(request) }
        val advised = other.civID in RulerOverrides.ruledCivs
        if (ours.isNotEmpty() && advised) return "Our offer of peace is already before ${other.civName}'s court; they have not answered yet. Wait, or sweeten it."
        other.tradeRequests.removeAll(ours.toSet())
        val trade = Trade()
        val treaty = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed)
        trade.ourOffers.add(treaty)
        trade.theirOffers.add(treaty)
        val acceptable = TradeEvaluation().isTradeAcceptable(trade.reverse(), other, civ)
        other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
        val entry = Triple(civ.civName, other.civName, "peace")
        if (entry !in pending) pending += entry // the replaced offer's entry carries on: one proposal of peace, however often it is repeated
        log(civ, "We proposed peace to ${other.civName}.")
        // What the other side would take: their own reckoning of the treaty, in gold (TradeEvaluation.evaluatePeaceCostForThem) - a figure, not advice
        val balance = TradeEvaluation().getTradeAcceptability(trade.reverse(), other, civ, true)
        return "Peace proposed to $name; ${whoAnswers(other)}" + if (advised) "." else ", and " + when {
            acceptable -> "by their own reckoning they would accept it as it stands."
            balance < -100_000 -> "by their own reckoning no price would buy peace from them now."
            else -> "by their own reckoning they would make peace for about ${-balance} gold from us; our treasury holds ${civ.gold}."
        }
    }

    fun proposeFriendship(civ: Civilization, name: String): String {
        val other = civ.known(name)
        // Theirs already waits on our desk: proposing it back is accepting it, as a trade proposed back is
        if (friendshipFrom(civ, other)) return answerFriendship(civ, other, "accept")
        if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)) throw IllegalArgumentException("A declaration of friendship with $name is not possible now (at war, already friends, or recently declined)")
        if (other.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID }) throw IllegalArgumentException("Already proposed; ${whoAnswers(other)}")
        other.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civ.civID))
        friendshipsAsked += "${other.civName}<${civ.civName}" // ours, not the officials': officialsDealings leaves it be
        log(civ, "We proposed a declaration of friendship to ${other.civName}.")
        return "Friendship proposed to $name; ${whoAnswers(other)}."
    }

    /** Does a proposal of friendship from [other] wait on [civ]'s desk? (an engine popup alert, which the officials leave alone at a ruled court) */
    private fun friendshipFrom(civ: Civilization, other: Civilization) = civ.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }
    /** turn on which each proposal of friendship on our desk was first seen, keyed "us<them" */
    private val friendshipOfferedOn = Desk()

    /**
     *  Proposals of a Declaration of Friendship made to this court: they wait for the ruler's answer (answerOffers, terms "Declaration of Friendship")
     *  as deals do, and lapse as refused if none comes. Only the turn's bookkeeping ([announce]) enters one in the log and starts its clock.
     */
    fun friendshipOffers(civ: Civilization, announce: Boolean = false): List<String> {
        val turn = civ.gameInfo.turns
        val waiting = civ.popupAlerts.filter { it.type == AlertType.DeclarationOfFriendship }.mapNotNull { alert -> civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value } }.distinct()
        if (announce) friendshipOfferedOn.keepOnly(civ, waiting.map { "${civ.civName}<${it.civName}" })
        return waiting.mapNotNull { other ->
            val key = "${civ.civName}<${other.civName}"
            if (!other.isAlive() || !civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)) {
                // War, a denouncement or a friendship made meanwhile: nothing left to answer
                if (announce) { civ.popupAlerts.removeAll { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }; friendshipOfferedOn.forget(key) }
                return@mapNotNull null
            }
            friendshipOfferedOn.item(key, turn, announce,
                fresh = { "${other.civName}: a Declaration of Friendship (just arrived; answerOffers accept or refuse, terms \"Declaration of Friendship\")" + nearVictoryNote(other) },
                arrive = { log(civ, "${other.civName} proposes a Declaration of Friendship. It waits for your answer - accept or refuse it with answerOffers (terms \"Declaration of Friendship\"); unanswered, it lapses as refused in $DESK_WAITS turns.") },
                lapse = {
                    refuseFriendship(civ, other)
                    log(civ, "${other.civName}'s proposal of a Declaration of Friendship went unanswered and lapsed as refused.") },
                told = { left -> "${other.civName}: a Declaration of Friendship (${Desk.waits(left, "lapses at the next turn")}; answerOffers accept or refuse, terms \"Declaration of Friendship\")" + nearVictoryNote(other) })
        }
    }

    private fun refuseFriendship(civ: Civilization, other: Civilization) {
        civ.popupAlerts.removeAll { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }
        // The engine's own answer for a refusal: the proposer's officials wait before they ask again
        other.getDiplomacyManager(civ)?.let { if (it.getFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship) < FRIENDSHIP_REFUSED_WAITS) it.setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, FRIENDSHIP_REFUSED_WAITS) }
        other.addNotification("[${civ.civName}] has denied our Declaration of Friendship!", com.unciv.logic.civilization.NotificationCategory.Diplomacy, com.unciv.logic.civilization.NotificationIcon.Diplomacy, civ.civName)
    }

    /** The ruler's answer to a proposal of friendship on the desk; each side's log hears it (the proposer's through the engine's notice) */
    private fun answerFriendship(civ: Civilization, other: Civilization, answer: String, byRenewal: Boolean = false): String {
        friendshipOfferedOn.forget("${civ.civName}<${other.civName}")
        return when (answer.lowercase().trim()) {
            "accept" -> {
                if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)) throw IllegalArgumentException("A Declaration of Friendship with ${other.civName} cannot be made now (at war, denounced, or already friends)")
                civ.popupAlerts.removeAll { it.type == AlertType.DeclarationOfFriendship && it.value == other.civID }
                civ.getDiplomacyManager(other)!!.signDeclarationOfFriendship()
                other.addNotification("We have signed a Declaration of Friendship with [${civ.civName}]!", com.unciv.logic.civilization.NotificationCategory.Diplomacy, com.unciv.logic.civilization.NotificationIcon.Diplomacy, civ.civName)
                val turns = civ.getDiplomacyManager(other)!!.getFlag(DiplomacyFlags.DeclarationOfFriendship)
                log(civ, if (byRenewal) "By your order to renew it: the Declaration of Friendship with ${other.civName} lapsed, ${other.civName} had proposed it again, and we accepted theirs; it stands for $turns turns."
                    else "By your order: we accepted ${other.civName}'s Declaration of Friendship; it stands for $turns turns.")
                "Accepted ${other.civName}'s Declaration of Friendship: it stands for $turns turns"
            }
            "refuse" -> {
                refuseFriendship(civ, other)
                log(civ, "By your order: we refused ${other.civName}'s Declaration of Friendship.")
                "Refused ${other.civName}'s Declaration of Friendship. Their officials do not propose it again for $FRIENDSHIP_REFUSED_WAITS turns; you still may"
            }
            else -> throw IllegalArgumentException("answer must be accept or refuse")
        }
    }

    fun giftGold(civ: Civilization, name: String, gold: Int): String {
        val other = civ.known(name)
        if (!other.isCityState) throw IllegalArgumentException("$name is not a city-state; trade with them instead")
        if (gold <= 0) throw IllegalArgumentException("Gift must be positive")
        if (civ.gold < gold) throw IllegalArgumentException("Treasury has only ${civ.gold} gold")
        val influence = other.cityStateFunctions.influenceGainedByGift(civ, gold)
        other.cityStateFunctions.receiveGoldGift(civ, gold)
        val now = other.getDiplomacyManager(civ)!!.getInfluence().toInt()
        log(civ, "We gave $gold gold to ${other.civName} (+$influence influence, now $now).")
        return "Gave $gold gold to $name: +$influence influence, now $now" + (other.allyCiv?.let { ally -> "; their ally is ${ally.civName}" } ?: "")
    }

    /** A marriage into a city-state's ruling family, as the rules stand now: open with its price, or what stands in the way */
    fun marriageState(civ: Civilization, other: Civilization): String {
        val cost = other.cityStateFunctions.getDiplomaticMarriageCost()
        if (other.cityStateFunctions.canBeMarriedBy(civ)) return "open: $cost gold - the marry order brings ${other.civName} under us, held as a puppet"
        return "not open: " + listOfNotNull(
            if (other.getDiplomacyManager(civ)?.isRelationshipLevelEQ(com.unciv.logic.civilization.diplomacy.RelationshipLevel.Ally) != true) "we must be its ally" else null,
            civ.getDiplomacyManager(other)?.takeIf { it.hasFlag(DiplomacyFlags.MarriageCooldown) }?.let { cooldown -> "not for ${cooldown.getFlag(DiplomacyFlags.MarriageCooldown)} more turn${plural(cooldown.getFlag(DiplomacyFlags.MarriageCooldown))}" },
            if (civ.gold < cost) "it costs $cost gold, and we have ${civ.gold}" else null).joinToString(", ").ifEmpty { "the rules do not allow it now" }
    }

    /** {marry: city-state}: our people marry into its ruling family, for gold - the city-state's city becomes ours, a puppet. Never the officials' choice */
    fun marry(civ: Civilization, name: String, perCityCost: String): String {
        val other = civ.getKnownCivs().firstOrNull { it.isCityState && it.isAlive() && it.civName.equals(name.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("marry takes a city-state we know: ${civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.joinToString { it.civName }}; not '$name'")
        if (!civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) throw IllegalArgumentException("Our people cannot marry into a city-state's ruling family: the rules give that only to a people with the ability")
        if (!other.cityStateFunctions.canBeMarriedBy(civ)) throw IllegalArgumentException("A marriage with ${other.civName} is ${marriageState(civ, other)}")
        val cost = other.cityStateFunctions.getDiplomaticMarriageCost()
        val city = other.getCapital()!!.name
        other.cityStateFunctions.diplomaticMarriage(civ)
        // The engine ends the city-state as if it fell ("has been destroyed!", which wakes every court); the world is told of the marriage in its own line
        for (anyone in civ.gameInfo.civilizations) anyone.notifications.removeAll { it.text == "The City-State of [${other.civName}] has been destroyed!" }
        civ.notifications.removeAll { it.text.startsWith("We have married into the ruling family of [${other.civName}]") } // ours below says more
        married += city
        val line = "$city joined us by marriage into ${if (city == other.civName) "its" else "the ${other.civName}"} ruling family, for $cost gold: it is ours, held as a puppet (it builds and pays for itself, gives no orders). " +
            "Annexing it with a cities order makes it a city of our own, and adds $perCityCost to the culture cost of every policy; held as a puppet it adds none"
        log(civ, "By your order: $line.")
        // The world's history hears of a marriage, not of a conquest or nothing; the other courts that know us likewise
        event("$city joined ${civ.civName} by marriage into its ruling family.")
        for (court in civ.gameInfo.civilizations) if (court != civ && court.civID in RulerOverrides.ruledCivs && court.isAlive() && court.knows(civ))
            log(court, "$city joined ${civ.civName} by marriage into its ruling family.")
        return line
    }
    /** Cities that came by marriage this turn: the capture line does not call them taken */
    val married = HashSet<String>()

    /** {protect / endProtection: city-state}: our pledge to protect it, given or withdrawn by the ruler's word - never the officials' for a ruled realm */
    fun protect(civ: Civilization, name: String, pledge: Boolean): String {
        val other = civ.getKnownCivs().firstOrNull { it.isCityState && it.isAlive() && it.civName.equals(name.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("${if (pledge) "protect" else "endProtection"} takes a city-state we know: ${civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.joinToString { it.civName }}; not '$name'")
        val theirs = other.getDiplomacyManager(civ)!!
        if (pledge) {
            if (!other.cityStateFunctions.otherCivCanPledgeProtection(civ)) throw IllegalArgumentException("We cannot pledge to protect ${other.civName} now: " + when {
                theirs.diplomaticStatus == DiplomaticStatus.Protector -> "we protect it already"
                theirs.hasFlag(DiplomacyFlags.RecentlyWithdrewProtection) -> "we withdrew our pledge lately; not for ${theirs.getFlag(DiplomacyFlags.RecentlyWithdrewProtection)} more turns"
                civ.isAtWarWith(other) -> "we are at war with it"
                theirs.getInfluence() < 0 -> "its regard for us is below nothing"
                else -> "the rules do not allow it" })
            other.cityStateFunctions.addProtectorCiv(civ)
            log(civ, "By your order: we pledged to protect ${other.civName}. Its regard for us rests higher, others find it harder to take tribute from it, and we think the worse of any power that bullies, attacks or destroys it; the pledge cannot be withdrawn for 10 turns.")
            return "Pledged to protect ${other.civName}; it cannot be withdrawn for 10 turns"
        }
        if (!other.cityStateFunctions.otherCivCanWithdrawProtection(civ)) throw IllegalArgumentException("We cannot withdraw our pledge to ${other.civName} now: " +
            (if (theirs.diplomaticStatus != DiplomaticStatus.Protector) "we have not pledged it" else "it was given lately; not for ${theirs.getFlag(DiplomacyFlags.RecentlyPledgedProtection)} more turns"))
        other.cityStateFunctions.removeProtectorCiv(civ)
        log(civ, "By your order: we withdrew our pledge to protect ${other.civName}; it cannot be given again for 20 turns.")
        return "Withdrew our pledge to protect ${other.civName}; it cannot be given again for 20 turns"
    }

    /** {denounce: power}: a public denouncement, for 30 turns - never the officials' choice for a ruled realm */
    fun denounce(civ: Civilization, name: String): String {
        val other = civ.getKnownCivs().firstOrNull { it.isMajorCiv() && it.isAlive() && it.civName.equals(name.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("denounce takes a great power we know: ${civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.joinToString { it.civName }}; not '$name'")
        val manager = civ.getDiplomacyManager(other)!!
        when {
            civ.isAtWarWith(other) -> throw IllegalArgumentException("We are at war with ${other.civName}: there is nothing left to denounce")
            manager.hasFlag(DiplomacyFlags.Denunciation) -> throw IllegalArgumentException("We denounced ${other.civName} already; it stands ${manager.getFlag(DiplomacyFlags.Denunciation)} more turns")
            manager.hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> throw IllegalArgumentException("We have a Declaration of Friendship with ${other.civName}: a friend cannot be denounced while it stands")
        }
        manager.denounce()
        log(civ, "By your order: we denounced ${other.civName} before the world, for 30 turns. They think the worse of us for it, and the embassies between us are closed; the powers that hate them think better of us, and their friends worse.")
        return "Denounced ${other.civName} for 30 turns: their regard for us falls, the embassies between us close, and the other powers judge us by how they stand with ${other.civName}"
    }

    /** Standing relations for the briefing */
    fun relations(civ: Civilization): List<Map<String, Any?>> =
        civ.getKnownCivs().filter { !it.isBarbarian && !it.isSpectator() && it.isAlive() }.map { other ->
            val ours = civ.getDiplomacyManager(other)!!
            val theirs = other.getDiplomacyManager(civ)
            val entry = linkedMapOf<String, Any?>(
                "civ" to other.civName,
                // The engine's enum in words: "Peace", "War", "a defensive pact" - not "DefensivePact"
                "status" to when (ours.diplomaticStatus) { DiplomaticStatus.DefensivePact -> "a defensive pact"; else -> ours.diplomaticStatus.name.lowercase() },
                "theirAttitude" to theirs?.relationshipLevel()?.name,
            )
            if (other.isCityState) {
                entry["cityState"] = other.cityStateType.name
                entry["influence"] = theirs?.getInfluence()?.toInt()
                // A city-state fights its ally's wars: a peace with it lasts only while its ally is at peace with us too (Manila's collapsed in two turns, Run 28)
                entry["ally"] = other.allyCiv?.let { ally -> if (ally == civ) "us - it fights our wars" else "${ally.civName}, with ${other.getDiplomacyManager(ally)?.getInfluence()?.toInt() ?: 0} influence - it fights ${ally.civName}'s wars" +
                    (if (civ.isAtWarWith(ally)) ", and ${ally.civName} is at war with us" else "") }
                // What a gift of ours would add now (the city-state screen's own sum), and how much our standing falls each turn
                entry["ourGiftWouldAdd"] = listOf(250, 500, 1000).filter { civ.gold >= it }.associate { "$it gold" to other.cityStateFunctions.influenceGainedByGift(civ, it) }.ifEmpty { null }
                entry["ourInfluenceFallsPerTurn"] = theirs?.getCityStateInfluenceDegrade()?.takeIf { it > 0f }?.let { (it * 10).toInt() / 10f }
                // The pledge is kept on the city-state's side (CityStateFunctions.addProtectorCiv): ours toward it never says so
                entry["protectedByUs"] = theirs?.diplomaticStatus == DiplomaticStatus.Protector
                // Where our people may marry into a city-state's ruling family (Austria's ability): open or not, and why
                if (civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) entry["marriage"] = marriageState(civ, other)
            } else {
                entry["cities"] = other.cities.size
                // The engine's score is no measure a reader can hold (87723 against 47825 read as "twice their strength" while the crossing bled): against ours, in words
                entry["militaryStrength"] = armsAgainstOurs(civ, other)
                // What the bare figure hides: the age of their arms (how hard each city hits back is in warEstimate)
                entry["era"] = other.getEra().name
                entry["techs"] = other.tech.techsResearched.size
                // The turns it has left: pacts between friends need it, and a renewal of one must not outlive it unseen
                entry["friendship"] = if (ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) "in force, ${ours.getFlag(DiplomacyFlags.DeclarationOfFriendship)} turns left" +
                    (if (renewals.any { it.civ == civ.civName && it.partner == other.civName && it.friendship }) " - by your order, proposed again the turn it lapses" else "") else false
                entry["openBordersForUs"] = theirs?.hasOpenBorders ?: false
                entry["denounced"] = listOfNotNull(ours.takeIf { it.hasFlag(DiplomacyFlags.Denunciation) }?.let { "we denounced them (${it.getFlag(DiplomacyFlags.Denunciation)} turns left)" },
                    theirs?.takeIf { it.hasFlag(DiplomacyFlags.Denunciation) }?.let { "they denounced us (${it.getFlag(DiplomacyFlags.Denunciation)} turns left)" }).joinToString("; ").ifEmpty { null }
                // The engine's own words for what they hold against us, which speak from their side ("You took the alliance we had...")
                entry["theirGrievancesInTheirWords"] = theirs?.diplomaticModifiers?.filter { it.value < 0 }?.keys?.map { key ->
                    val grievance = com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.safeValueOf(key)
                    (grievance?.text?.removeSuffix("!")?.removeSuffix(".") ?: key) + when (grievance) {
                        com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.UnacceptableDemands -> " (a demand between us, ours of them or our answer to theirs)"
                        else -> "" } }?.toList()
                entry["atWarWith"] = other.getKnownCivs().filter { it.isMajorCiv() && other.isAtWarWith(it) }.map { it.civName }.toList()
                entry["dealsInForce"] = ours.trades.map { it.brief(civ.gameInfo) }
                // What each side could actually hand over (a luxury that came by a deal or from a city-state cannot be passed on), with how many:
                // a power holding a single copy seldom parts with it
                val ourLuxuries = luxuries(civ); val theirLuxuries = luxuries(other)
                val logic = TradeLogic(civ, other)
                fun spare(offers: List<TradeOffer>, lacking: Set<String>) = offers.filter { it.type == TradeOfferType.Luxury_Resource && it.amount > 0 && it.name !in lacking }.map { "${it.name} x${it.amount}" }
                entry["luxuriesTheyLack"] = spare(logic.ourAvailableOffers, theirLuxuries.keys)
                entry["luxuriesWeLack"] = spare(logic.theirAvailableOffers, ourLuxuries.keys)
            }
            entry
        }.toList()

    /** A deal's identity at its three levels, one copy each. Its terms: the wording without the turns left, which tick down while the deal stays the same */
    fun termsOf(trade: Trade, game: GameInfo): String = sansTurns(trade.brief(game))
    fun sansTurns(words: String): String = words.replace(DURATION, "")
    /** A deal in force as "Civ: terms" - the key the turn's compare, the renewals and the swept deals all use */
    private fun dealKey(partner: String, trade: Trade, game: GameInfo) = "$partner: ${termsOf(trade, game)}"
    /** The same partner and goods with every amount struck out: a deal that ends the turn its renewal begins on new figures was replaced, not lost */
    fun sameGoods(deal: String): String = deal.replace(Regex(" x\\d+"), "").replace(Regex("\\d+"), "#")

    /** Every deal in force as dealsInForce words it, with the turns it has left: a deal renewed within the turn on the very same terms reads the same, and only its turns tell it */
    fun dealTurnsLeft(civ: Civilization): Map<String, Int> = civ.diplomacy.values.flatMap { manager -> manager.trades.map { trade ->
        dealKey(manager.otherCivName, trade, civ.gameInfo) to (trade.ourOffers + trade.theirOffers).maxOfOrNull { it.duration }.let { it ?: 0 } } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }

    /** Every deal in force, as "Civ: terms" - compared turn to turn so the log can say exactly what began and ended */
    fun dealsInForce(civ: Civilization): Set<String> =
        civ.diplomacy.values.flatMap { manager -> manager.trades.map { dealKey(manager.otherCivName, it, civ.gameInfo) } }.toSet() // durations tick down, the deal is the same

    /** The partners whose swept deals our own line has named, by realm, until the next bookkeeping: the engine's "X from Y has ended" notices for them are not told again */
    val sweptTold = HashMap<String, MutableSet<String>>()

    /** The deals a declaration of war swept away - the engine clears them without a word, and between turns the turn's compare cannot see it - by partner,
     *  a Research Agreement marked for the gold already paid. Defensive pacts are left out: the declaration's own line names those (audit 2026-10-03) */
    fun lostToWar(civ: Civilization, before: Set<String>): String? {
        val gone = (before - dealsInForce(civ)).filter { Constants.defensivePact !in it }
        if (gone.isEmpty()) return null
        sweptTold.getOrPut(civ.civName) { HashSet() } += gone.map { it.substringBefore(": ") }
        return gone.groupBy({ it.substringBefore(": ") }, { it.substringAfter(": ") }).entries.joinToString("; ") { (partner, terms) ->
            "with $partner - " + terms.joinToString("; ") { it.replace(RESEARCH_PAID, "a Research Agreement ($1 gold from each side, paid and now lost)") } }
    }

    private fun heldPeaceOffer(civ: Civilization, other: Civilization) = civ.tradeRequests.firstOrNull { request -> request.requestingCiv == other.civID && isPeace(request) }

    /** A held request is stored as we, the receiver, see it */
    private fun describeOffer(request: TradeRequest, game: GameInfo): String {
        val extras = { offers: List<TradeOffer> -> offers.filter { it.name != Constants.peaceTreaty }.joinToString { it.brief(game) } }
        val give = extras(request.trade.ourOffers); val get = extras(request.trade.theirOffers)
        return when { give.isEmpty() && get.isEmpty() -> "a plain peace, nothing paid either way"; give.isEmpty() -> "peace, and they give us $get"; get.isEmpty() -> "peace, for which we give them $give"; else -> "peace; we give $give, they give $get" }
    }

    /** turn on which each waiting offer of peace was first seen, keyed "us<them" */
    private val peaceOfferedOn = Desk()
    /** Each waiting offer of a deal, keyed "us<them: terms" */
    private val dealOfferedOn = Desk()

    private fun isPeace(request: TradeRequest) = (request.trade.ourOffers + request.trade.theirOffers).any { it.name == Constants.peaceTreaty }

    /**
     *  Deals other powers have laid before this court: they wait for the ruler's answer (answerOffers), and lapse as refused if none comes.
     *  Only the turn's bookkeeping ([announce]) enters an offer in the log and starts its clock; a briefing shows what waits and changes nothing.
     */
    fun tradeOffers(civ: Civilization, announce: Boolean = false): List<String> {
        val game = civ.gameInfo
        val turn = game.turns
        val waiting = civ.tradeRequests.filter { !isPeace(it) }
        val keyOf = { request: TradeRequest -> "${civ.civName}<${game.getCivilization(request.requestingCiv).civName}: ${request.trade.brief(game)}" }
        if (announce) dealOfferedOn.keepOnly(civ, waiting.map(keyOf))
        return waiting.mapNotNull { request ->
            val other = game.getCivilization(request.requestingCiv)
            val key = keyOf(request)
            val terms = request.trade.brief(game)
            val notes = { warnGivingAway(civ, request) + nearVictoryNote(other, request.trade.ourOffers + request.trade.theirOffers) }
            dealOfferedOn.item(key, turn, announce,
                fresh = { "${other.civName}: $terms (just arrived - it may have replaced an offer you saw before; answer it with these terms, as written here)" + notes() },
                arrive = {
                    val advised = other.civID in RulerOverrides.ruledCivs
                    val inAnswer = counters.remove("${civ.civName}<${other.civName}")
                    log(civ, "${other.civName}${if (advised) " (an advised court)" else ""} offers a deal${if (inAnswer) " in answer to ours" else ""}: $terms. It waits for your answer - accept or refuse it with answerOffers (a counter is a refusal and a new proposal); unanswered, it lapses as refused in $DESK_WAITS turns.") },
                lapse = {
                    civ.tradeRequests.remove(request); request.decline(civ); holdOff(civ, request)
                    noteGone(civ, other, terms, "went unanswered and lapsed as refused")
                    settle(request, civ, "lapsed")
                    other.addNotification("[${civ.civName}] has denied your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, civ.civName, com.unciv.logic.civilization.NotificationIcon.Trade)
                    log(civ, "${other.civName}'s offer ($terms) went unanswered and lapsed as refused.") },
                told = { left -> "${other.civName}: $terms (${Desk.waits(left, "lapses at the next turn")}; answerOffers accept or refuse)" + notes() })
        }
    }

    /** A warning, never a block: accepting would give away a strategic resource that a queued item or our own units need */
    private fun warnGivingAway(civ: Civilization, request: TradeRequest): String {
        val game = civ.gameInfo
        val warnings = request.trade.ourOffers.filter { it.type == TradeOfferType.Strategic_Resource }.mapNotNull { offer ->
            val have = civ.getCivResourcesByName()[offer.name] ?: 0 // what is left after our units' own use
            val left = have - offer.amount
            val queued = civ.cities.flatMap { city -> city.cityConstructions.constructionQueue.mapNotNull { name ->
                val needs = (game.ruleset.units[name]?.getResourceRequirementsPerTurn(city.state) ?: game.ruleset.buildings[name]?.getResourceRequirementsPerTurn(city.state))?.get(offer.name) ?: 0
                if (needs > 0) "${city.name}'s queued $name" to needs else null } }
            when {
                left < queued.sumOf { it.second } -> " WARNING: accepting " + (if (have <= offer.amount) "gives away ${if (have == 1) "our only" else "all our"} ${offer.name}, which" else "leaves too little ${offer.name} for") +
                    " ${queued.joinToString(" and ") { it.first }}${if (have <= offer.amount) (if (queued.size == 1) " needs" else " need") else ""}"
                left < 0 -> " WARNING: accepting gives away ${offer.name} our own units are using"
                else -> null
            }
        }
        return warnings.joinToString("")
    }

    /**
     *  The ruler's answer to a deal on the desk: accept it as offered, or refuse it (a counter is a refusal followed by a proposal of our own).
     *  [terms] names which offer, as the briefing lists it, when one power has laid several.
     */
    fun answerOffer(civ: Civilization, name: String, answer: String, terms: String? = null, leave: Boolean = false): String {
        val other = civ.known(name)
        val game = civ.gameInfo
        val waiting = civ.tradeRequests.filter { it.requestingCiv == other.civID && !isPeace(it) }
        // A proposal of friendship waits on the same desk: named by its terms, or the only thing from that power there is to answer
        if (friendshipFrom(civ, other) && (terms?.let { "friendship" in loosely(it) } ?: waiting.isEmpty())) return answerFriendship(civ, other, answer)
        if (waiting.isEmpty()) {
            goneFrom(civ, other, terms)?.let { return "Nothing to answer: $it." }
            throw IllegalArgumentException("No deal from $name is waiting (peace is answered with the peace order)")
        }
        // An offer not yet entered in the log came after the briefing the advisor read (another court's look-in runs beside its own and may replace
        // an offer): it is taken only by its full terms, never by a bare answer or a short label that may have meant the one it replaced
        val entered = { request: TradeRequest -> "${civ.civName}<${other.civName}: ${request.trade.brief(game)}" in dealOfferedOn }
        val changed = { request: TradeRequest -> IllegalArgumentException("${other.civName}'s offer changed since your briefing: it now reads '${request.trade.brief(game)}'. Answer it with these terms if you mean it" + (goneFrom(civ, other)?.let { " (what you read before: $it)" } ?: "")) }
        val request = when {
            // Matched as listed, with the durations left off, or by a short label ("Defensive Pact", "Spices") that names exactly one of the offers waiting
            terms != null -> waiting.firstOrNull { it.trade.brief(game).equals(terms.trim(), ignoreCase = true) } ?: waiting.singleOrNull { loosely(it.trade.brief(game)) == loosely(terms) }
                ?: waiting.singleOrNull { loosely(it.trade.brief(game)).contains(loosely(terms)) }?.also { if (!entered(it)) throw changed(it) }
                // a label naming an offer that has left the desk is answered as such, even while another offer from that power waits
                ?: goneFrom(civ, other, terms)?.let { return "Nothing to answer: $it. Waiting from ${other.civName} now: ${waiting.joinToString("; ") { it.trade.brief(game) }}." }
                ?: throw IllegalArgumentException("No offer from $name reads '$terms'; waiting: ${waiting.joinToString("; ") { it.trade.brief(game) }}")
            waiting.size == 1 && !friendshipFrom(civ, other) -> waiting.single().also { if (!entered(it)) throw changed(it) }
            waiting.size == 1 -> throw IllegalArgumentException("$name has laid two things before us: a deal (${waiting.single().trade.brief(game)}) and a proposal of friendship; say which with terms - the deal as listed, or \"Declaration of Friendship\"")
            else -> throw IllegalArgumentException("$name has laid ${waiting.size} offers before us; say which with terms, exactly as listed: ${waiting.joinToString("; ") { it.trade.brief(game) }}" + (if (friendshipFrom(civ, other)) "; or \"Declaration of Friendship\" for their proposal of friendship" else ""))
        }
        val summary = request.trade.brief(game)
        val key = "${civ.civName}<${other.civName}: $summary"
        return when (answer.lowercase().trim()) {
            "accept" -> {
                // A deal that has us declare war is a declaration by other means: it takes the same leave as the declareWar order, and with it may be accepted
                if (!leave) request.trade.ourOffers.firstOrNull { it.type == TradeOfferType.WarDeclaration }?.let {
                    throw IllegalArgumentException("${other.civName}'s offer ($summary) would have us declare war on ${it.brief(game).substringAfter("war on ")}: accepting it is a declaration of war, and $WAR_LEAVE") }
                // Checked before anything is touched: an offer that cannot be honoured stays on the desk, to be refused or to lapse
                if (!TradeEvaluation().isTradeValid(request.trade, civ, other)) {
                    val logic = TradeLogic(civ, other)
                    val short = request.trade.ourOffers.filter { offer -> logic.ourAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "our ${it.brief(game)}" } +
                        request.trade.theirOffers.filter { offer -> logic.theirAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "their ${it.brief(game)}" }
                    throw IllegalArgumentException("${other.civName}'s offer ($summary) can no longer be honoured: ${short.ifEmpty { listOf("a resource or the treasury") }.joinToString()} ${if (short.size == 1) "is" else "are"} no longer there to give. It stays on the desk: refuse it, or let it lapse")
                }
                civ.tradeRequests.remove(request); dealOfferedOn.forget(key)
                settle(request, civ, "accepted")
                val logic = TradeLogic(civ, other)
                logic.currentTrade.set(request.trade)
                logic.acceptTrade()
                other.addNotification("[${civ.civName}] has accepted your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, com.unciv.logic.civilization.NotificationIcon.Trade, civ.civName)
                log(civ, "By your order: we accepted ${other.civName}'s offer ($summary). The deal is in force.")
                // Our own proposal of the same swap, still on their desk, would be accepted too and the deal made twice
                val mirror = other.tradeRequests.firstOrNull { it.requestingCiv == civ.civID && !isPeace(it) && sameSwap(it.trade.reverse(), request.trade) }
                if (mirror != null) { other.tradeRequests.remove(mirror); noteGone(other, civ, mirror.trade.brief(game), "was withdrawn: ${civ.civName} accepted our own proposal of the same swap instead, and the deal is in force"); settle(mirror, other, "withdrawn") }
                "Accepted ${other.civName}'s offer: $summary" + (if (mirror != null) " (our own proposal of the same swap to them is withdrawn)" else "")
            }
            "refuse" -> {
                civ.tradeRequests.remove(request); dealOfferedOn.forget(key)
                request.decline(civ); val held = holdOff(civ, request)
                settle(request, civ, "declined")
                other.addNotification("[${civ.civName}] has denied your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, civ.civName, com.unciv.logic.civilization.NotificationIcon.Trade)
                log(civ, "By your order: we refused ${other.civName}'s offer ($summary).")
                "Refused ${other.civName}'s offer: $summary. " + (if (held > 0) "Neither side's officials offer these terms again for $held turns" else "Their officials may offer them again when they choose") + "; you still may"
            }
            else -> throw IllegalArgumentException("answer must be accept or refuse")
        }
    }

    /** Terms saved as "we give A for B" before both sides were named: "for" otherwise only ever begins a duration, so the first other "for" is the divide */
    private fun reworded(saved: String) = Regex("^(.*?we give .*?) for (?!\\d+ turns?\\b)(.*)$").find(saved)?.takeIf { "; they give " !in saved }?.let { "${it.groupValues[1]}; they give ${it.groupValues[2]}" } ?: saved

    /** turn on which each demand on our desk was first seen, keyed "us<them: kind" */
    private val demandOfferedOn = Desk()

    /** What a demand asks of us, in plain words */
    private fun asked(demand: Demand) = when (demand) {
        Demand.DontSpyOnUs -> "stop spying on them"
        Demand.DoNotSpreadReligion -> "stop spreading our religion in their cities"
        Demand.DoNotSettleNearUs -> "stop settling cities near them"
        Demand.DoNotAttackUs -> "keep our troops from their borders - that is, promise not to attack them"
    }
    private fun refusing(demand: Demand, from: Civilization) = if (demand == Demand.DoNotAttackUs) "refusing this one IS a declaration of war on ${from.civName} (the game's rule)" else "refusing it, they hold it against us"

    /** The demands other powers have laid on this court, each once, with who made it: they live in the engine's popup alerts, which the officials leave alone for a ruled civ */
    private fun demandsOnDesk(civ: Civilization) = civ.popupAlerts.mapNotNull { alert ->
        Demand.entries.firstOrNull { it.demandAlert == alert.type }?.let { demand -> civ.gameInfo.civilizations.firstOrNull { it.civID == alert.value }?.let { demand to it } } }.distinct()

    /**
     *  Demands made of this court: they wait for the ruler's answer (answerDemands) as offers do, and lapse as AGREED if none comes - agreeing binds
     *  a promise, refusing can mean war, and a lapse must never start one. Only the turn's bookkeeping ([announce]) enters one in the log and starts its clock.
     */
    fun demands(civ: Civilization, announce: Boolean = false): List<String> {
        val turn = civ.gameInfo.turns
        val waiting = demandsOnDesk(civ)
        val keyOf = { demand: Demand, from: Civilization -> "${civ.civName}<${from.civName}: ${demand.name}" }
        if (announce) demandOfferedOn.keepOnly(civ, waiting.map { (demand, from) -> keyOf(demand, from) })
        return waiting.mapNotNull { (demand, from) ->
            val key = keyOf(demand, from)
            if (!from.isAlive()) { if (announce) { civ.popupAlerts.removeAll { it.type == demand.demandAlert && it.value == from.civID }; demandOfferedOn.forget(key) }; return@mapNotNull null }
            val terms = "${from.civName} demands that we ${asked(demand)}. Agreeing binds a promise for 100 turns, and breaking it later is remembered as betrayal; ${refusing(demand, from)}"
            demandOfferedOn.item(key, turn, announce,
                fresh = { "$terms (just arrived; answerDemands agree or refuse)" },
                arrive = { log(civ, "${from.civName} demands that we ${asked(demand)}. It waits for your answer - agree or refuse it with answerDemands" +
                    (if (demand == Demand.DoNotAttackUs) " (to refuse it is to declare war on ${from.civName})" else "") + "; unanswered, it lapses as agreed in $DESK_WAITS turns.") },
                lapse = {
                    civ.popupAlerts.removeAll { it.type == demand.demandAlert && it.value == from.civID }
                    civ.getDiplomacyManager(from)?.agreeToDemand(demand)
                    log(civ, "${from.civName}'s demand that we ${asked(demand)} went unanswered and lapsed as agreed: we are bound by that promise for 100 turns.") },
                told = { left -> "$terms (${Desk.waits(left, "lapses as agreed at the next turn", ", then lapses as agreed")}; answerDemands agree or refuse)" })
        }
    }

    /** The ruler's answer to a power's demand(s): agree binds the promise; refuse is resented - and refusing "do not attack us" declares war on them.
     *  Returns what to tell, and the power we are now at war with when the refusal was a declaration */
    fun answerDemand(civ: Civilization, name: String, answer: String, leave: Boolean = true): Pair<String, Civilization?> {
        val from = civ.known(name)
        val waiting = demandsOnDesk(civ).filter { it.second == from }
        if (waiting.isEmpty()) throw IllegalArgumentException("No demand from $name is waiting")
        val agree = when (answer.lowercase().trim()) { "agree", "accept" -> true; "refuse" -> false; else -> throw IllegalArgumentException("answer must be agree or refuse") }
        val manager = civ.getDiplomacyManager(from)!!
        val wasAtWar = civ.isAtWarWith(from)
        // Refusing a demand not to attack them is a declaration of war in the game's rules: the same leave as declareWar
        if (!agree && !leave && !wasAtWar && waiting.any { it.first == Demand.DoNotAttackUs })
            throw IllegalArgumentException("Refusing ${from.civName}'s demand not to attack them is a declaration of war, and $WAR_LEAVE. Agree, let it lapse (it lapses as agreed), or bring it to the ruler")
        val told = waiting.map { (demand, _) ->
            civ.popupAlerts.removeAll { it.type == demand.demandAlert && it.value == from.civID }
            demandOfferedOn.forget("${civ.civName}<${from.civName}: ${demand.name}")
            val dealsBefore = dealsInForce(civ)
            if (agree) manager.agreeToDemand(demand) else manager.refuseDemand(demand)
            // A refusal that declared war cleared our deals with them in the same breath: named here, where the turn's compare cannot see them
            val swept = if (agree) "" else lostToWar(civ, dealsBefore)?.let { " It swept away our deals: $it." } ?: ""
            log(civ, "By your order: we ${if (agree) "agreed to" else "refused"} ${from.civName}'s demand that we ${asked(demand)}" +
                (if (agree) " - a promise now binds us for 100 turns." else if (demand == Demand.DoNotAttackUs) ": that refusal is a declaration of war, and we are at war with ${from.civName}.$swept" else ". They will hold it against us."))
            "${if (agree) "Agreed to" else "Refused"} ${from.civName}'s demand that we ${asked(demand)}$swept"
        }
        return Pair(told.joinToString("; "), from.takeIf { !wasAtWar && civ.isAtWarWith(it) })
    }

    /** Strategic resources the ruler's own proposals still waiting on other desks offer, by name and amount: the officials' re-arming leaves them alone */
    fun promised(civ: Civilization): Map<String, Int> {
        val game = civ.gameInfo
        val ours = pending.filter { it.first == civ.civName && it.third != "peace" }
        if (ours.isEmpty()) return emptyMap()
        return game.civilizations.flatMap { other -> other.tradeRequests.filter { request -> request.requestingCiv == civ.civID && ours.any { it.second == other.civName && it.third == request.trade.reverse().brief(game) } } }
            .flatMap { it.trade.theirOffers }.filter { it.type == TradeOfferType.Strategic_Resource } // the request holds the trade as its receiver sees it: their offers are ours
            .groupingBy { it.name }.fold(0) { sum, offer -> sum + offer.amount }
    }

    /** A deal the ruler ordered offered again the turn it ends, once: the terms as dealsInForce words them, and the goods each side gives (name, amount).
     *  A Declaration of Friendship is renewed the same way, under the terms [FRIENDSHIP] with no goods. [afterFriendship]: the deal ended with the friendship
     *  it needs, and waits for the friendship proposed again that turn before it goes out */
    class Renewal(val civ: String, val partner: String, val terms: String, val give: List<Pair<String, Int>>, val want: List<Pair<String, Int>>, var afterFriendship: Boolean = false) {
        fun toMap() = mapOf("civ" to civ, "partner" to partner, "terms" to terms, "give" to give.map { mapOf("item" to it.first, "amount" to it.second) }, "want" to want.map { mapOf("item" to it.first, "amount" to it.second) }, "afterFriendship" to afterFriendship)
        val friendship get() = terms == FRIENDSHIP
        /** "Open Borders", "Horses and Copper": the goods, gold left out unless it is all there is */
        val goods get() = if (friendship) FRIENDSHIP else (give + want).map { it.first }.filterNot { it == "Gold" || it == "Gold per turn" }.ifEmpty { (give + want).map { it.first } }.distinct().joinToString(" and ")
        /** Pacts that can be made only between friends */
        val needsFriendship get() = (give + want).any { it.first == Constants.researchAgreement || it.first == Constants.defensivePact }
    }
    val renewals = ArrayList<Renewal>()

    /** The renew order: a deal now in force with [name] is proposed again, on the same terms and as our own proposal, the turn it ends - once. [terms] picks it as answerOffers picks an offer;
     *  "Declaration of Friendship" renews the friendship instead, proposed again the turn it lapses */
    fun orderRenewal(civ: Civilization, name: String, terms: String?): String {
        val other = civ.known(name)
        val game = civ.gameInfo
        val manager = civ.getDiplomacyManager(other)!!
        val friendsFor = manager.getFlag(DiplomacyFlags.DeclarationOfFriendship).takeIf { manager.hasFlag(DiplomacyFlags.DeclarationOfFriendship) }
        if (terms != null && "friendship" in loosely(terms)) {
            if (friendsFor == null) throw IllegalArgumentException("No Declaration of Friendship with $name is in force to renew; propose one with the friendship order")
            renewals.removeAll { it.civ == civ.civName && it.partner == other.civName && it.friendship }
            renewals += Renewal(civ.civName, other.civName, FRIENDSHIP, emptyList(), emptyList())
            log(civ, "By your order: the Declaration of Friendship with ${other.civName} is to be proposed again the turn it lapses.")
            return "The Declaration of Friendship with ${other.civName} lapses in $friendsFor turn${plural(friendsFor)}; the turn it does it is proposed again, once - they answer it as any proposal of friendship" + nearVictoryNote(other)
        }
        val running = manager.trades.filter { trade -> (trade.ourOffers + trade.theirOffers).let { offers -> offers.none { it.name == Constants.peaceTreaty } && offers.any { it.duration > 0 } } }
        if (running.isEmpty()) throw IllegalArgumentException("No deal with $name is in force that could be renewed (a peace treaty needs no renewing)" + (if (friendsFor != null) "; the Declaration of Friendship is renewed with terms \"$FRIENDSHIP\"" else ""))
        val trade = when {
            terms != null -> running.firstOrNull { it.brief(game).equals(terms.trim(), ignoreCase = true) } ?: running.singleOrNull { loosely(it.brief(game)) == loosely(terms) }
                ?: running.singleOrNull { loosely(it.brief(game)).contains(loosely(terms)) }
                ?: throw IllegalArgumentException("No deal with $name in force reads '$terms'; in force: ${running.joinToString("; ") { it.brief(game) }}" + (if (friendsFor != null) "; or \"$FRIENDSHIP\"" else ""))
            running.size == 1 -> running.single()
            else -> throw IllegalArgumentException("$name and we have ${running.size} deals in force; say which with terms, as listed: ${running.joinToString("; ") { it.brief(game) }}")
        }
        val words = termsOf(trade, game)
        val renewal = Renewal(civ.civName, other.civName, words, trade.ourOffers.map { it.name to it.amount }, trade.theirOffers.map { it.name to it.amount })
        renewals.removeAll { it.civ == civ.civName && it.partner == other.civName && it.terms == words }
        renewals += renewal
        val turns = (trade.ourOffers + trade.theirOffers).maxOf { it.duration }
        // What the deal will lack when it ends, said now while there is time to see to it: a pact between friends outliving the friendship
        val pact = (trade.ourOffers + trade.theirOffers).firstOrNull { it.name == Constants.researchAgreement || it.name == Constants.defensivePact }?.name
        val friendshipRenewed = renewals.any { it.civ == civ.civName && it.partner == other.civName && it.friendship }
        val lacking = if (pact == null) "" else when {
            friendsFor == null -> " Note: the $pact needs a Declaration of Friendship, and none is in force with ${other.civName} - unless one is made before it ends, it cannot be offered again."
            friendsFor > turns -> ""
            friendshipRenewed -> " The $pact needs the Declaration of Friendship, which ends ${if (friendsFor == turns) "the same turn" else "$friendsFor turn${plural(friendsFor)} from now, before it"}; that is ordered renewed too, and the $pact goes out once ${other.civName} has taken the friendship again."
            else -> " Note: the $pact needs the Declaration of Friendship, which ends ${if (friendsFor == turns) "the same turn" else "$friendsFor turn${plural(friendsFor)} from now, before it"} - order the friendship renewed too (renew with terms \"$FRIENDSHIP\"), or it cannot be offered again."
        }
        log(civ, "By your order: the ${renewal.goods} deal with ${other.civName} ($words) is to be offered again, on the same terms, the turn it ends.")
        return "The ${renewal.goods} deal with ${other.civName} ($words) ends in $turns turn${plural(turns)}; the turn it ends it is proposed again on the same terms, as our own proposal, once.$lacking" + nearVictoryNote(other, trade.ourOffers + trade.theirOffers)
    }

    /** After the turn: each renewal ordered for a deal that has now ended is proposed again, as the ruler's own proposal; the order is spent either way.
     *  Friendships go first, then the deals that need them. [ended] and [replaced] are the turn's deals as dealsInForce words them ("Partner: terms") */
    fun renewEnded(civ: Civilization, ended: Collection<String>, replaced: Collection<String>) {
        val game = civ.gameInfo
        val inForce = dealsInForce(civ)
        for (renewal in renewals.filter { it.civ == civ.civName && it.friendship }) {
            val other = game.civilizations.firstOrNull { it.civName == renewal.partner }
            val manager = other?.let { civ.getDiplomacyManager(it) }
            if (manager?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true) continue // still in force
            renewals.remove(renewal)
            if (other == null || !other.isAlive() || civ.isAtWarWith(other) || manager?.hasFlag(DiplomacyFlags.Denunciation) == true) {
                log(civ, "The order to propose the Declaration of Friendship with ${renewal.partner} again is dropped: " + when {
                    other == null || !other.isAlive() -> "${renewal.partner} is gone"
                    civ.isAtWarWith(other) -> "the friendship was ended by war"
                    else -> "the friendship was ended by a denouncement" } + ".")
                continue
            }
            try {
                // Their own proposal of it already waits on our desk: it is accepted, and that one line tells it (Denmark heard "accepted" and "proposed again" both, Run 21)
                if (friendshipFrom(civ, other)) { answerFriendship(civ, other, "accept", byRenewal = true); continue }
                proposeFriendship(civ, renewal.partner)
                log(civ, "By your order: the Declaration of Friendship with ${renewal.partner} lapsed and was proposed again; they answer it as any proposal of friendship.")
            } catch (ex: IllegalArgumentException) {
                log(civ, "By your order the Declaration of Friendship with ${renewal.partner} was to be proposed again when it lapsed, but it cannot be: ${ex.message?.removeSuffix(".")}. The order is spent.")
            }
        }
        for (renewal in renewals.filter { it.civ == civ.civName && !it.friendship }) {
            val key = "${renewal.partner}: ${renewal.terms}"
            val other = game.civilizations.firstOrNull { it.civName == renewal.partner }
            val friendshipAsked = other?.popupAlerts?.any { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID } == true
            if (renewal.afterFriendship) {
                val friends = other != null && civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
                if (!friends && friendshipAsked) continue // their answer to the friendship is still to come
                renewals.remove(renewal)
                if (!friends) { log(civ, "The ${renewal.goods} deal with ${renewal.partner} was to be offered again once the Declaration of Friendship was renewed, but ${renewal.partner} did not take the friendship. The order is spent."); continue }
                offerAgain(civ, renewal, "was offered again now the Declaration of Friendship is renewed")
                continue
            }
            if (key in replaced) {
                renewals.remove(renewal)
                log(civ, "The ${renewal.goods} deal with ${renewal.partner} was renewed the turn it ended without your order's help, so the order to offer it again is spent.")
                continue
            }
            if (key !in ended) {
                // A deal cut short - by war, or by its partner's fall - never ends in the turn's reckoning: its order would wait for ever
                if (key !in inForce) {
                    renewals.remove(renewal)
                    log(civ, "The order to offer the ${renewal.goods} deal with ${renewal.partner} again is dropped: the deal is gone, " + when {
                        other == null || !other.isAlive() -> "with ${renewal.partner}"
                        civ.isAtWarWith(other) -> "cut short by the war"
                        else -> "ended before its time" } + ".")
                }
                continue
            }
            // A pact between friends that ended with the friendship: it waits for the friendship proposed again this turn
            if (renewal.needsFriendship && friendshipAsked && other != null && civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) != true) {
                renewal.afterFriendship = true
                log(civ, "The ${renewal.goods} deal with ${renewal.partner} ended with the Declaration of Friendship it needs: it is offered again once ${renewal.partner} has taken the friendship proposed again.")
                continue
            }
            renewals.remove(renewal)
            offerAgain(civ, renewal, "ended and was offered again")
        }
    }

    /** One renewal proposed, and the log told what the proposal did: taken as their own offer of the same, sent, or impossible */
    private fun offerAgain(civ: Civilization, renewal: Renewal, sent: String) {
        val json = JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("with", JsonValue(renewal.partner))
            for ((side, offers) in listOf("give" to renewal.give, "want" to renewal.want)) addChild(side, JsonValue(JsonValue.ValueType.array).also { array ->
                for ((item, amount) in offers) array.addChild(JsonValue(JsonValue.ValueType.`object`).apply { addChild("item", JsonValue(item)); addChild("amount", JsonValue(amount.toLong())) }) })
        }
        // A power a step from victory whose own offer of the same waits on our desk: the standing order does not take it on its own (Japan's renewal
        // took the Aztecs' Silver at T295, Run 21) - the offer waits for the advisor, with the note beside it
        val partner = civ.gameInfo.civilizations.firstOrNull { it.civName == renewal.partner }
        val near = partner?.let { nearVictory(it) }
        if (partner != null && near != null && civ.tradeRequests.any { request -> request.requestingCiv == partner.civID && !isPeace(request)
                && (request.trade.ourOffers + request.trade.theirOffers).map { it.name }.toSet() == (renewal.give + renewal.want).map { it.first }.toSet() }) {
            renewals.remove(renewal)
            log(civ, "The ${renewal.goods} deal with ${renewal.partner} ended. ${renewal.partner} has offered it again, and the standing order to renew did not take that offer, because $near: it waits on your desk - accept or refuse it with answerOffers.")
            return
        }
        try {
            val told = proposeTrade(civ, json)
            renewals.remove(renewal)
            // Their offer of the same already waited on our desk: proposeTrade took it, and said so in its own line
            log(civ, if (told.contains(TAKEN_THEIRS)) "By your order: the ${renewal.goods} deal with ${renewal.partner} ended; ${renewal.partner} had offered the same again, and we accepted their offer of the same."
                else "By your order: the ${renewal.goods} deal with ${renewal.partner} $sent.")
        } catch (ex: IllegalArgumentException) {
            renewals.remove(renewal)
            log(civ, "By your order the ${renewal.goods} deal with ${renewal.partner} was to be offered again when it ended, but it cannot be: ${ex.message?.removeSuffix(".")}. The order is spent.")
        }
    }

    /** Forget everything of one game */
    fun reset() { renewals.clear(); pending.clear(); officialsPending.clear(); peaceOfferedOn.clear(); dealOfferedOn.clear(); demandOfferedOn.clear(); friendshipOfferedOn.clear(); friendshipsAsked.clear(); oursOut.clear(); counters.clear(); gone.clear(); sweptTold.clear() }

    /** What a restart would otherwise lose: which proposals are ours to settle, and when each waiting offer was first seen */
    fun toMap() = mapOf(
        "pending" to pending.map { mapOf("from" to it.first, "to" to it.second, "terms" to it.third) },
        "officialsPending" to officialsPending.map { mapOf("from" to it.first, "to" to it.second, "terms" to it.third) },
        "peaceOfferedOn" to peaceOfferedOn.toMap(),
        "dealOfferedOn" to dealOfferedOn.toMap(),
        "demandOfferedOn" to demandOfferedOn.toMap(),
        "friendshipOfferedOn" to friendshipOfferedOn.toMap(),
        "renewals" to renewals.map { it.toMap() })

    fun load(json: JsonValue?) {
        reset()
        json?.get("pending")?.forEach { pending += Triple(it.getString("from"), it.getString("to"), reworded(it.getString("terms"))) }
        json?.get("officialsPending")?.forEach { officialsPending += Triple(it.getString("from"), it.getString("to"), it.getString("terms")) }
        peaceOfferedOn.load(json?.get("peaceOfferedOn"))
        dealOfferedOn.load(json?.get("dealOfferedOn"), ::reworded)
        demandOfferedOn.load(json?.get("demandOfferedOn"))
        friendshipOfferedOn.load(json?.get("friendshipOfferedOn"))
        json?.get("renewals")?.forEach { entry -> renewals += Renewal(entry.getString("civ"), entry.getString("partner"), entry.getString("terms"),
            entry.get("give").map { it.getString("item") to it.getInt("amount") }, entry.get("want").map { it.getString("item") to it.getInt("amount") }, entry.getBoolean("afterFriendship", false)) }
    }

    /**
     *  Offers of peace wait for the ruler - the officials neither take nor refuse them. Entered in the log when they arrive and withdrawn after
     *  some turns unanswered, both only by the turn's bookkeeping ([announce]); a briefing shows what waits and changes nothing.
     */
    fun peaceOffers(civ: Civilization, announce: Boolean = false): List<String> {
        val game = civ.gameInfo
        val turn = game.turns
        val waiting = civ.tradeRequests.filter { isPeace(it) }
        if (announce) peaceOfferedOn.keepOnly(civ, waiting.map { "${civ.civName}<${game.getCivilization(it.requestingCiv).civName}" })
        return waiting.mapNotNull { request ->
            val other = game.getCivilization(request.requestingCiv)
            val terms = describeOffer(request, game)
            peaceOfferedOn.item("${civ.civName}<${other.civName}", turn, announce,
                fresh = { "${other.civName} offers $terms (just arrived, not yet entered in the log; order peace with ${other.civName} to accept)" },
                arrive = { log(civ, "${other.civName} offers peace: $terms. The offer waits for your answer - order peace with ${other.civName} to accept it; left unanswered it is withdrawn in $DESK_WAITS turns, which is how a peace is refused.") },
                lapse = {
                    civ.tradeRequests.remove(request); request.decline(civ)
                    settle(request, civ, "lapsed")
                    log(civ, "${other.civName}'s offer of peace went unanswered and has been withdrawn.") },
                told = { left -> "${other.civName} offers $terms (${Desk.waits(left, "withdrawn at the next turn")}; order peace with ${other.civName} to accept)" })
        }
    }

    /** The running account of all deals: what goes out and comes in each turn, and which deals are about to end */
    /** [soon]: the turns that make a deal "ending soon" - a look-in away */
    fun ledger(civ: Civilization, soon: Int): Map<String, Any?> {
        val trades = civ.diplomacy.values.flatMap { manager -> manager.trades.map { manager.otherCivName to it } }
        fun goldPerTurn(offers: List<TradeOffer>) = offers.filter { it.type == TradeOfferType.Gold_Per_Turn }.sumOf { it.amount }
        return mapOf(
            "goldPerTurnWePay" to trades.sumOf { goldPerTurn(it.second.ourOffers) },
            "goldPerTurnWeReceive" to trades.sumOf { goldPerTurn(it.second.theirOffers) },
            "endingSoon" to endingSoon(civ, soon).map { deal -> deal + if (renewals.any { it.civ == civ.civName && sansTurns(deal) == "${it.partner}: ${it.terms}" }) " - by your order, offered again the turn it ends"
                else " - order renew to have it offered again the turn it ends" },
            "renewOrdered" to renewals.filter { it.civ == civ.civName }.map { "${it.partner}: ${it.terms}" }.ifEmpty { null })
    }

    /** Deals with at most this many turns left - the time to decide whether they are worth renewing */
    fun endingSoon(civ: Civilization, within: Int): List<String> =
        civ.diplomacy.values.flatMap { manager -> manager.trades
            // A peace treaty that runs out does not restart a war: nothing to renew there
            .filter { trade -> (trade.ourOffers + trade.theirOffers).none { it.name == Constants.peaceTreaty } }
            .filter { trade -> (trade.ourOffers + trade.theirOffers).any { it.duration in 1..within } }
            .map { "${manager.otherCivName}: ${it.brief(civ.gameInfo)}" } }

    /** A pact that cannot be offered because it already runs: say so, with the turns left, instead of "not tradeable" */
    private fun inForce(civ: Civilization, other: Civilization, item: String): String? {
        val running = civ.getDiplomacyManager(other)?.trades?.flatMap { it.ourOffers + it.theirOffers }?.firstOrNull { it.name.equals(item, ignoreCase = true) } ?: return null
        return "'$item' is already in force with ${other.civName}" + (if (running.duration > 0) ", ${running.duration} more turns; it can be offered again once it ends - order renew to have it offered again the turn it does (the officials often renew such pacts on their own)" else "")
    }

    private fun luxuries(civ: Civilization) = civ.getCivResourcesByName()
        .filter { it.value > 0 && civ.gameInfo.ruleset.tileResources[it.key]?.resourceType?.name == "Luxury" }

    /** What could be traded with each major civ, for the options */
    fun tradeOptions(civ: Civilization, leave: Boolean = true): Map<String, Any?> {
        val resources = civ.getCivResourcesByName()
        return mapOf(
            "ourLuxuries" to luxuries(civ),
            "ourSpareLuxuries" to resources.filter { it.value > 1 && civ.gameInfo.ruleset.tileResources[it.key]?.resourceType?.name == "Luxury" }.keys.toList(),
            "ourGold" to civ.gold,
            "with" to civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() && !civ.isAtWarWith(it) }.map { other ->
                val logic = TradeLogic(civ, other)
                mapOf("civ" to other.civName,
                    "weCanOffer" to logic.ourAvailableOffers.filter { it.type !in hiddenOffers && it.amount > 0 && (leave || it.type != TradeOfferType.WarDeclaration) }.map { it.word(civ.gameInfo) + if (it.amount > 1) " x${it.amount}" else "" },
                    "theyCanOffer" to logic.theirAvailableOffers.filter { it.type !in hiddenOffers && it.amount > 0 }.map { it.word(civ.gameInfo) + if (it.amount > 1) " x${it.amount}" else "" })
            }.toList(),
            "cityStates" to civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.map { state ->
                mapOf("civ" to state.civName, "type" to state.cityStateType.name,
                    "influence" to state.getDiplomacyManager(civ)?.getInfluence()?.toInt(),
                    "influenceFor250Gold" to state.cityStateFunctions.influenceGainedByGift(civ, 250))
            }.toList(),
            "note" to "Offers: luxury/strategic resource names, 'Gold' (amount), 'Gold per turn' (amount), '${Constants.openBorders}', '${Constants.researchAgreement}', '${Constants.defensivePact}', '${Constants.acceptEmbassy}', 'declaration of war on <power>' (a call to war, where the lists carry it: in want, they declare it on accepting; in give, we do, and it needs the same leave as declareWar). A proposal to an unadvised power is answered by its officials on their turn; one to an advised court waits for that court's advisor, who answers at their next look-in. An answer does not send for you - watch the log - but an offer from an advised court does, a counteroffer among them."
        )
    }
}
