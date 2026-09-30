package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.RulerOverrides
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest

/**
 *  Diplomacy a ruler can order. A proposal to an unadvised power is weighed by its officials on their turn; one to an advised court waits
 *  for that court's advisor. Either way the engine tells the host what became of it ([RulerOverrides.tradeAnswered]) and the chancery log
 *  says so, with the terms. Every offer made to an advised court waits for its ruler in the same way (answerOffers).
 */
private const val PEACE_OFFER_WAITS = 8

class RulerDiplomacy(private val log: (Civilization, String) -> Unit) {

    private val hiddenOffers = setOf(TradeOfferType.City, TradeOfferType.Introduction, TradeOfferType.WarDeclaration, TradeOfferType.Technology)

    /** Our open proposals: (our civ, their civ, terms) - each settled by its own record when the engine reports its answer */
    val pending = ArrayList<Triple<String, String, String>>()
    /** Offers that left a desk by another hand than its owner's answer - withdrawn, replaced, lapsed - keyed "desk<from": (turn, what became of it).
     *  An advisor answering from a briefing read before it left (another court's look-in runs beside its own) is told what happened, not "nothing waits" */
    private val gone = HashMap<String, Triple<Int, String, String>>()
    private fun noteGone(desk: Civilization, from: Civilization, terms: String, what: String) { gone["${desk.civName}<${from.civName}"] = Triple(desk.gameInfo.turns, terms, what) }
    /** What became of the offer that left this desk lately - only if [label], when given, names it */
    private fun goneFrom(desk: Civilization, from: Civilization, label: String? = null) = gone["${desk.civName}<${from.civName}"]
        ?.takeIf { desk.gameInfo.turns - it.first <= 2 && (label == null || it.second.lowercase().contains(label.trim().lowercase().replace(Regex(" for \\d+ turns"), ""))) }
        ?.let { "${from.civName}'s offer (${it.second}) ${it.third}" }
    private fun isLuxury(game: GameInfo, name: String) = game.ruleset.tileResources[name]?.resourceType?.name == "Luxury"

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

    fun TradeOffer.brief(game: GameInfo): String = when (type) {
        // These three carry an id where every other offer carries its name
        TradeOfferType.WarDeclaration -> "a declaration of war on ${game.civilizations.firstOrNull { it.civID == name }?.civName ?: name}"
        TradeOfferType.Introduction -> "an introduction to ${game.civilizations.firstOrNull { it.civID == name }?.civName ?: name}"
        TradeOfferType.City -> "the city of ${game.getCities().firstOrNull { it.id == name }?.name ?: name}"
        else -> name + (if (type.numberType != TradeOfferType.TradeTypeNumberType.None) " x$amount" else if (name == Constants.researchAgreement && amount > 0) " ($amount gold from each side)" else "") + (if (duration > 0) " for $duration turns" else "")
    }
    fun Trade.brief(game: GameInfo) = "we give ${ourOffers.joinToString { it.brief(game) }.ifEmpty { "nothing" }} for ${theirOffers.joinToString { it.brief(game) }.ifEmpty { "nothing" }}"

    /** Offers of ours out in the world, by partner and terms, so a new one the officials made can be told from the ruler's */
    private val oursOut = HashMap<String, Set<String>>()
    private fun oursOutNow(civ: Civilization) = civ.gameInfo.civilizations.flatMap { other -> other.tradeRequests.filter { it.requestingCiv == civ.civID }.map { "${other.civName}: ${it.trade.reverse().brief(civ.gameInfo)}" } }.toSet()
    fun noteRequests(civ: Civilization) { oursOut[civ.civName] = oursOutNow(civ) }
    /** After the turn: what the officials proposed on their own (what was asked of us waits for the ruler, so that half is not theirs) */
    fun officialsDealings(civ: Civilization) {
        for (offer in oursOutNow(civ) - (oursOut[civ.civName] ?: emptySet())) {
            if (pending.any { it.first == civ.civName && "${it.second}: ${it.third}" == offer }) continue
            val partner = offer.substringBefore(":")
            log(civ, "The officials proposed a deal to $partner: ${offer.substringAfter(": ")}. ${if (isAdvised(civ, partner)) "$partner is an advised court: its advisor answers at their next look-in" else "The other side answers on its turn"}.")
        }
    }

    private fun isAdvised(civ: Civilization, name: String) = civ.gameInfo.civilizations.firstOrNull { it.civName == name }?.civID in RulerOverrides.ruledCivs

    /** Partners whose last word on a proposal of ours was a counteroffer, keyed "us<them": the offer now on our desk is announced as an answer, not a fresh approach */
    private val counters = HashSet<String>()

    private fun entryFor(civ: Civilization, receiver: Civilization, request: TradeRequest) = pending.firstOrNull {
        it.first == civ.civName && it.second == receiver.civName && (if (it.third == "peace") isPeace(request) else it.third == request.trade.reverse().brief(civ.gameInfo)) }

    /**
     *  The word on one request of ours - from the engine ([RulerOverrides.tradeAnswered]) or from our own dealings with a held offer.
     *  The proposal is settled by its record and the log says how it ended; a request nobody proposed by order (the officials' own) passes in silence.
     */
    fun settle(request: TradeRequest, receiver: Civilization, outcome: String) {
        val civ = receiver.gameInfo.civilizations.firstOrNull { it.civID == request.requestingCiv } ?: return
        if (outcome == "accepted") {
            withdrawDuplicates(civ, request.trade.ourOffers.map { it.name }, request)
            withdrawDuplicates(receiver, request.trade.theirOffers.map { it.name }, request)
        }
        val entry = entryFor(civ, receiver, request) ?: return
        pending.remove(entry)
        val answer = when (outcome) {
            "accepted" -> "was accepted - the deal is in force"
            "declined" -> "was refused"
            "countered" -> { counters += "${civ.civName}<${receiver.civName}"; "got a counteroffer, which now waits on our desk for your answer (answerOffers accept or refuse)" }
            "lapsed" -> "waited $PEACE_OFFER_WAITS turns at their court unanswered and lapsed as refused"
            "invalid" -> "could not be honoured when their officials weighed it (a resource or the treasury had changed) and was dropped"
            "voided" -> "lapsed: one side could no longer supply what was offered (a resource ran out, or a treaty state changed)"
            "withdrawn" -> "was withdrawn: it asked the same swap as the offer of theirs we accepted, and a deal is made once"
            "duplicate" -> "was withdrawn: what it asked for now comes to us by another deal, and a second supply of a luxury adds nothing"
            else -> "ended without a recorded answer"
        }
        log(civ, "Our proposal to ${receiver.civName} (${entry.third}) $answer.")
    }

    /** Called after each turn: a proposal whose request left the partner's desk with no word from the engine (war sweeps a desk clean, a fallen realm has none) is closed here, so nothing waits for ever */
    fun sweepLost(civ: Civilization) {
        for (entry in pending.filter { it.first == civ.civName }.toList()) {
            val other = civ.gameInfo.civilizations.firstOrNull { it.civName == entry.second }
            if (other != null && other.tradeRequests.any { it.requestingCiv == civ.civID && (if (entry.third == "peace") isPeace(it) else it.trade.reverse().brief(civ.gameInfo) == entry.third) }) continue // still waiting
            pending.remove(entry)
            log(civ, "Our proposal to ${entry.second} (${entry.third}) " + when {
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
    fun proposeTrade(civ: Civilization, json: JsonValue): String {
        val other = civ.known(json.getString("with"))
        val game = civ.gameInfo
        if (other.isCityState) throw IllegalArgumentException("City-states do not trade; send them a gift instead")
        val logic = TradeLogic(civ, other)
        val trade = Trade()
        fun add(list: JsonValue?, available: List<TradeOffer>, into: MutableList<TradeOffer>, side: String) {
            list?.forEach { entry ->
                val item = entry.getString("item")
                val offer = available.firstOrNull { it.name.equals(item, ignoreCase = true) && it.amount > 0 }
                    ?: throw IllegalArgumentException(inForce(civ, other, item) ?: "$side cannot offer '$item' right now; tradeable: ${available.filter { it.amount > 0 && it.type !in hiddenOffers }.joinToString { it.name + " x" + it.amount }}")
                val copy = offer.copy()
                // A treaty runs a fixed length: an amount given for one (the duration, usually) is ignored
                val wanted = if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.None) offer.amount else if (entry.has("amount")) entry.getInt("amount") else if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.Gold) throw IllegalArgumentException("'$item' needs an amount") else 1
                if (wanted < 1 || wanted > offer.amount) throw IllegalArgumentException("$side can offer at most ${offer.amount} of '$item', not $wanted")
                copy.amount = wanted
                into.add(copy)
            }
        }
        add(json.get("give"), logic.ourAvailableOffers, trade.ourOffers, "We")
        add(json.get("want"), logic.theirAvailableOffers, trade.theirOffers, other.civName)
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) throw IllegalArgumentException("An empty trade")
        if (!TradeEvaluation().isTradeValid(trade, civ, other)) throw IllegalArgumentException("That trade is not valid right now")
        // The same swap proposed back at a power whose offer of it already waits on our desk would be accepted on both sides and made twice.
        // Word for word the same is what the advisor wants: their offer is taken. Different terms for the same goods are the advisor's to weigh
        civ.tradeRequests.firstOrNull { it.requestingCiv == other.civID && !isPeace(it) && sameSwap(it.trade, trade) }?.let { held ->
            if (held.trade.brief(game) == trade.brief(game))
                return "${other.civName} had already offered exactly this, so their offer was taken instead of proposing it back. " + answerOffer(civ, other.civName, "accept", held.trade.brief(game))
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
        other.tradeRequests.removeAll(superseded.toSet())
        for (old in superseded) noteGone(other, civ, old.trade.brief(game), "was replaced by ${civ.civName} with a newer offer for the same goods")
        // a withdrawn offer cannot be "accepted" later - and only the withdrawn ones go, another offer to the same power for other goods still waits
        for (old in superseded) pending.remove(Triple(civ.civName, other.civName, old.trade.reverse().brief(game)))
        other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
        val summary = trade.brief(game)
        if (superseded.isNotEmpty()) log(civ, "Our earlier offer to ${other.civName} (${superseded.joinToString("; ") { it.trade.reverse().brief(game) }}) was withdrawn in favour of the new one, which asks for the same goods.")
        pending += Triple(civ.civName, other.civName, summary)
        log(civ, "We proposed a trade to ${other.civName}: $summary.")
        val balance = TradeEvaluation().getTradeAcceptability(trade.reverse(), other, civ, true)
        if (other.civID in RulerOverrides.ruledCivs)
            return (if (superseded.isNotEmpty()) "Your earlier pending offer for the same goods was withdrawn. " else "") + "Proposed to ${other.civName}: $summary. ${other.civName} is an advised court: their advisor decides at their next look-in, within a few turns, and may accept, refuse or send a counteroffer. (Their officials would reckon it worth $balance to them, but the choice is not theirs.)"
        return (if (superseded.isNotEmpty()) "Your earlier pending offer for the same goods was withdrawn. " else "") + "Proposed to ${other.civName}: $summary. They answer on their turn; by their own reckoning ${if (balance < -100_000) "they will not part with this at any price" else "this deal is worth $balance to them (they accept at 0 or more)"}, so they are ${if (acceptable) "likely to accept" else if (balance < -100_000) "certain to refuse" else "likely to refuse - sweeten it or ask for less"}."
    }

    fun proposePeace(civ: Civilization, name: String): String {
        val other = civ.known(name)
        if (!civ.isAtWarWith(other)) throw IllegalArgumentException("We are not at war with $name")
        // Their envoy is already waiting with terms: the ruler's "peace" is the answer to it
        heldPeaceOffer(civ, other)?.let { offer ->
            val terms = describeOffer(offer, civ.gameInfo)
            val logic = TradeLogic(civ, other)
            logic.currentTrade.set(offer.trade)
            civ.tradeRequests.remove(offer); peaceOfferedOn.remove("${civ.civName}<${other.civName}")
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
        return "Peace proposed to $name; " + if (advised) "$name is an advised court: their advisor answers at their next look-in." else "they answer on their turn and seem ${if (acceptable) "willing" else "unwilling"}."
    }

    fun proposeFriendship(civ: Civilization, name: String): String {
        val other = civ.known(name)
        if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)) throw IllegalArgumentException("A declaration of friendship with $name is not possible now (at war, already friends, or recently declined)")
        if (other.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == civ.civID }) throw IllegalArgumentException("Already proposed; $name answers on their turn")
        other.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civ.civID))
        log(civ, "We proposed a declaration of friendship to ${other.civName}.")
        return "Friendship proposed to $name; they answer on their turn."
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

    /** Standing relations for the briefing */
    fun relations(civ: Civilization): List<Map<String, Any?>> =
        civ.getKnownCivs().filter { !it.isBarbarian && !it.isSpectator() && it.isAlive() }.map { other ->
            val ours = civ.getDiplomacyManager(other)!!
            val theirs = other.getDiplomacyManager(civ)
            val entry = linkedMapOf<String, Any?>(
                "civ" to other.civName,
                "status" to ours.diplomaticStatus.name,
                "theirAttitude" to theirs?.relationshipLevel()?.name,
            )
            if (other.isCityState) {
                entry["cityState"] = other.cityStateType.name
                entry["influence"] = theirs?.getInfluence()?.toInt()
                entry["ally"] = other.allyCiv?.civName
                entry["protectedByUs"] = ours.diplomaticStatus == DiplomaticStatus.Protector
            } else {
                entry["cities"] = other.cities.size
                entry["score"] = other.calculateTotalScore().toInt()
                entry["militaryStrength"] = other.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force)
                // What the bare figure hides: the age of their arms, and how hard their cities hit back
                entry["era"] = other.getEra().name
                val seen = other.cities.filter { it.getCenterTile().isExplored(civ) }
                if (seen.isNotEmpty()) entry["cityDefence"] = seen.sortedByDescending { com.unciv.logic.battle.CityCombatant(it).getCityStrength() }.take(3)
                    .joinToString { "${it.name} ${com.unciv.logic.battle.CityCombatant(it).getCityStrength()}" }
                val soldiersSeen = other.units.getCivUnits().filter { it.isMilitary() && it.getTile().isVisible(civ) }.groupingBy { it.name }.eachCount()
                if (soldiersSeen.isNotEmpty()) entry["soldiersInSight"] = soldiersSeen.entries.joinToString { "${it.value} ${it.key}" }
                entry["techs"] = other.tech.techsResearched.size
                entry["friendship"] = ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
                entry["openBordersForUs"] = theirs?.hasOpenBorders ?: false
                entry["theirGrievances"] = theirs?.diplomaticModifiers?.filter { it.value < 0 }?.keys?.toList()
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

    /** Every deal in force, as "Civ: terms" - compared turn to turn so the log can say exactly what began and ended */
    fun dealsInForce(civ: Civilization): Set<String> =
        civ.diplomacy.values.flatMap { manager -> manager.trades.map { "${manager.otherCivName}: ${it.brief(civ.gameInfo).replace(Regex(" for \\d+ turns"), "")}" } }.toSet() // durations tick down, the deal is the same

    private fun heldPeaceOffer(civ: Civilization, other: Civilization) = civ.tradeRequests.firstOrNull { request -> request.requestingCiv == other.civID && isPeace(request) }

    /** A held request is stored as we, the receiver, see it */
    private fun describeOffer(request: TradeRequest, game: GameInfo): String {
        val extras = { offers: List<TradeOffer> -> offers.filter { it.name != Constants.peaceTreaty }.joinToString { it.brief(game) } }
        val give = extras(request.trade.ourOffers); val get = extras(request.trade.theirOffers)
        return when { give.isEmpty() && get.isEmpty() -> "a plain peace, nothing paid either way"; give.isEmpty() -> "peace, and they give us $get"; get.isEmpty() -> "peace, for which we give them $give"; else -> "peace; we give $give, they give $get" }
    }

    /** turn on which each waiting offer of peace was first seen, keyed "us<them" */
    private val peaceOfferedOn = HashMap<String, Int>()
    /** turn on which each waiting offer of a deal was first seen, keyed "us<them: terms" */
    private val dealOfferedOn = HashMap<String, Int>()

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
        if (announce) dealOfferedOn.keys.removeAll { key -> key.startsWith("${civ.civName}<") && waiting.none { keyOf(it) == key } }
        return waiting.mapNotNull { request ->
            val other = game.getCivilization(request.requestingCiv)
            val key = keyOf(request)
            val terms = request.trade.brief(game)
            val since = dealOfferedOn[key]
                ?: if (!announce) return@mapNotNull "${other.civName}: $terms (just arrived, not yet entered in the log; answerOffers accept or refuse)"
                else {
                    val advised = other.civID in RulerOverrides.ruledCivs
                    val inAnswer = counters.remove("${civ.civName}<${other.civName}")
                    log(civ, "${other.civName}${if (advised) " (an advised court)" else ""} offers a deal${if (inAnswer) " in answer to ours" else ""}: $terms. It waits for your answer - accept or refuse it with answerOffers (a counter is a refusal and a new proposal); unanswered, it lapses as refused in $PEACE_OFFER_WAITS turns.")
                    dealOfferedOn[key] = turn
                    turn
                }
            if (announce && turn - since >= PEACE_OFFER_WAITS) {
                civ.tradeRequests.remove(request); request.decline(civ); dealOfferedOn.remove(key)
                noteGone(civ, other, terms, "went unanswered and lapsed as refused")
                settle(request, civ, "lapsed")
                other.addNotification("[${civ.civName}] has denied your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, civ.civName, com.unciv.logic.civilization.NotificationIcon.Trade)
                log(civ, "${other.civName}'s offer ($terms) went unanswered and lapsed as refused.")
                null
            } else "${other.civName}: $terms (${(PEACE_OFFER_WAITS - (turn - since)).coerceAtLeast(0).let { if (it == 0) "lapses at the next turn" else "waits $it more turns" }}; answerOffers accept or refuse)"
        }
    }

    /**
     *  The ruler's answer to a deal on the desk: accept it as offered, or refuse it (a counter is a refusal followed by a proposal of our own).
     *  [terms] names which offer, as the briefing lists it, when one power has laid several.
     */
    fun answerOffer(civ: Civilization, name: String, answer: String, terms: String? = null): String {
        val other = civ.known(name)
        val game = civ.gameInfo
        val waiting = civ.tradeRequests.filter { it.requestingCiv == other.civID && !isPeace(it) }
        if (waiting.isEmpty()) {
            goneFrom(civ, other, terms)?.let { return "Nothing to answer: $it." }
            throw IllegalArgumentException("No deal from $name is waiting (peace is answered with the peace order)")
        }
        // Matched as listed, or with the durations left off - the one part of the terms an advisor tends to drop when copying them
        val loosely = { text: String -> text.replace(Regex(" for \\d+ turns"), "").replace(Regex("\\s+"), " ").trim().lowercase() }
        val request = when {
            // Matched as listed, with the durations left off, or by a short label ("Defensive Pact", "Spices") that names exactly one of the offers waiting
            terms != null -> waiting.firstOrNull { it.trade.brief(game).equals(terms.trim(), ignoreCase = true) } ?: waiting.singleOrNull { loosely(it.trade.brief(game)) == loosely(terms) }
                ?: waiting.singleOrNull { loosely(it.trade.brief(game)).contains(loosely(terms)) }
                ?: throw IllegalArgumentException("No offer from $name reads '$terms'; waiting: ${waiting.joinToString("; ") { it.trade.brief(game) }}" + (goneFrom(civ, other, terms)?.let { ". Lately gone from the desk: $it" } ?: ""))
            waiting.size == 1 -> waiting.single()
            else -> throw IllegalArgumentException("$name has laid ${waiting.size} offers before us; say which with terms, exactly as listed: ${waiting.joinToString("; ") { it.trade.brief(game) }}")
        }
        val summary = request.trade.brief(game)
        val key = "${civ.civName}<${other.civName}: $summary"
        return when (answer.lowercase().trim()) {
            "accept" -> {
                // War is declared by the declareWar order alone, where the ruler's leave is checked; a deal that has us declare it would go round that
                request.trade.ourOffers.firstOrNull { it.type == TradeOfferType.WarDeclaration }?.let {
                    throw IllegalArgumentException("${other.civName}'s offer ($summary) would have us declare war on ${it.brief(game).substringAfter("war on ")}: war is declared only by the declareWar order, never by accepting a deal. Refuse this offer; if you mean to fight, declare the war yourself.") }
                // Checked before anything is touched: an offer that cannot be honoured stays on the desk, to be refused or to lapse
                if (!TradeEvaluation().isTradeValid(request.trade, civ, other)) {
                    val logic = TradeLogic(civ, other)
                    val short = request.trade.ourOffers.filter { offer -> logic.ourAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "our ${it.brief(game)}" } +
                        request.trade.theirOffers.filter { offer -> logic.theirAvailableOffers.none { it.name == offer.name && it.amount >= offer.amount } }.map { "their ${it.brief(game)}" }
                    throw IllegalArgumentException("${other.civName}'s offer ($summary) can no longer be honoured: ${short.ifEmpty { listOf("a resource or the treasury") }.joinToString()} ${if (short.size == 1) "is" else "are"} no longer there to give. It stays on the desk: refuse it, or let it lapse")
                }
                civ.tradeRequests.remove(request); dealOfferedOn.remove(key)
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
                civ.tradeRequests.remove(request); dealOfferedOn.remove(key)
                request.decline(civ)
                settle(request, civ, "declined")
                other.addNotification("[${civ.civName}] has denied your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, civ.civName, com.unciv.logic.civilization.NotificationIcon.Trade)
                log(civ, "By your order: we refused ${other.civName}'s offer ($summary).")
                "Refused ${other.civName}'s offer: $summary"
            }
            else -> throw IllegalArgumentException("answer must be accept or refuse")
        }
    }

    /** Forget everything of one game */
    fun reset() { pending.clear(); peaceOfferedOn.clear(); dealOfferedOn.clear(); oursOut.clear(); counters.clear() }

    /** What a restart would otherwise lose: which proposals are ours to settle, and when each waiting offer was first seen */
    fun toMap() = mapOf(
        "pending" to pending.map { mapOf("from" to it.first, "to" to it.second, "terms" to it.third) },
        "peaceOfferedOn" to peaceOfferedOn,
        "dealOfferedOn" to dealOfferedOn)

    fun load(json: JsonValue?) {
        reset()
        json?.get("pending")?.forEach { pending += Triple(it.getString("from"), it.getString("to"), it.getString("terms")) }
        json?.get("peaceOfferedOn")?.forEach { peaceOfferedOn[it.name] = it.asInt() }
        json?.get("dealOfferedOn")?.forEach { dealOfferedOn[it.name] = it.asInt() }
    }

    /**
     *  Offers of peace wait for the ruler - the officials neither take nor refuse them. Entered in the log when they arrive and withdrawn after
     *  some turns unanswered, both only by the turn's bookkeeping ([announce]); a briefing shows what waits and changes nothing.
     */
    fun peaceOffers(civ: Civilization, announce: Boolean = false): List<String> {
        val game = civ.gameInfo
        val turn = game.turns
        val waiting = civ.tradeRequests.filter { isPeace(it) }
        if (announce) peaceOfferedOn.keys.removeAll { key -> key.startsWith("${civ.civName}<") && waiting.none { key == "${civ.civName}<${game.getCivilization(it.requestingCiv).civName}" } }
        return waiting.mapNotNull { request ->
            val other = game.getCivilization(request.requestingCiv)
            val key = "${civ.civName}<${other.civName}"
            val terms = describeOffer(request, game)
            val since = peaceOfferedOn[key]
                ?: if (!announce) return@mapNotNull "${other.civName} offers $terms (just arrived, not yet entered in the log; order peace with ${other.civName} to accept)"
                else {
                    log(civ, "${other.civName} offers peace: $terms. The offer waits for your answer - order peace with ${other.civName} to accept it; left unanswered it is withdrawn in $PEACE_OFFER_WAITS turns.")
                    peaceOfferedOn[key] = turn
                    turn
                }
            if (announce && turn - since >= PEACE_OFFER_WAITS) {
                civ.tradeRequests.remove(request); request.decline(civ); peaceOfferedOn.remove(key)
                settle(request, civ, "lapsed")
                log(civ, "${other.civName}'s offer of peace went unanswered and has been withdrawn.")
                null
            } else "${other.civName} offers $terms (${(PEACE_OFFER_WAITS - (turn - since)).coerceAtLeast(0).let { if (it == 0) "withdrawn at the next turn" else "waits $it more turns" }}; order peace with ${other.civName} to accept)"
        }
    }

    /** The running account of all deals: what goes out and comes in each turn, and which deals are about to end */
    fun ledger(civ: Civilization): Map<String, Any?> {
        val trades = civ.diplomacy.values.flatMap { manager -> manager.trades.map { manager.otherCivName to it } }
        fun goldPerTurn(offers: List<TradeOffer>) = offers.filter { it.type == TradeOfferType.Gold_Per_Turn }.sumOf { it.amount }
        return mapOf(
            "goldPerTurnWePay" to trades.sumOf { goldPerTurn(it.second.ourOffers) },
            "goldPerTurnWeReceive" to trades.sumOf { goldPerTurn(it.second.theirOffers) },
            "endingSoon" to endingSoon(civ, 5))
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
        return "'$item' is already in force with ${other.civName}" + (if (running.duration > 0) ", ${running.duration} more turns; it can be offered again once it ends (the officials often renew such pacts on their own)" else "")
    }

    private fun luxuries(civ: Civilization) = civ.getCivResourcesByName()
        .filter { it.value > 0 && civ.gameInfo.ruleset.tileResources[it.key]?.resourceType?.name == "Luxury" }

    /** What could be traded with each major civ, for the options */
    fun tradeOptions(civ: Civilization): Map<String, Any?> {
        val resources = civ.getCivResourcesByName()
        return mapOf(
            "ourLuxuries" to luxuries(civ),
            "ourSpareLuxuries" to resources.filter { it.value > 1 && civ.gameInfo.ruleset.tileResources[it.key]?.resourceType?.name == "Luxury" }.keys.toList(),
            "ourGold" to civ.gold,
            "with" to civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() && !civ.isAtWarWith(it) }.map { other ->
                val logic = TradeLogic(civ, other)
                mapOf("civ" to other.civName,
                    "weCanOffer" to logic.ourAvailableOffers.filter { it.type !in hiddenOffers && it.amount > 0 }.map { it.name + if (it.amount > 1) " x${it.amount}" else "" },
                    "theyCanOffer" to logic.theirAvailableOffers.filter { it.type !in hiddenOffers && it.amount > 0 }.map { it.name + if (it.amount > 1) " x${it.amount}" else "" })
            }.toList(),
            "cityStates" to civ.getKnownCivs().filter { it.isCityState && it.isAlive() }.map { state ->
                mapOf("civ" to state.civName, "type" to state.cityStateType.name,
                    "influence" to state.getDiplomacyManager(civ)?.getInfluence()?.toInt(),
                    "influenceFor250Gold" to state.cityStateFunctions.influenceGainedByGift(civ, 250))
            }.toList(),
            "note" to "Offers: luxury/strategic resource names, 'Gold' (amount), 'Gold per turn' (amount), '${Constants.openBorders}', '${Constants.researchAgreement}', '${Constants.defensivePact}', '${Constants.acceptEmbassy}'. A proposal to an unadvised power is answered by its officials on their turn; one to an advised court waits for that court's advisor, who answers at their next look-in. Nothing wakes you for the answer - watch the log."
        )
    }
}
