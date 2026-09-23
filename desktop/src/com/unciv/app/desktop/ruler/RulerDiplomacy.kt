package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonValue
import com.unciv.Constants
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
 *  Diplomacy a ruler can order. Proposals to other civs are answered by that civ on its own turn
 *  (its automation evaluates them), and the answer shows up in the chancery log.
 */
private const val PEACE_OFFER_WAITS = 8

class RulerDiplomacy(private val log: (Civilization, String) -> Unit) {

    private val hiddenOffers = setOf(TradeOfferType.City, TradeOfferType.Introduction, TradeOfferType.WarDeclaration, TradeOfferType.Technology)

    /** Our open proposals: (our civ, their civ, terms) - checked each turn to report how they ended */
    val pending = ArrayList<Triple<String, String, String>>()

    fun TradeOffer.brief() = name + (if (type.numberType != TradeOfferType.TradeTypeNumberType.None) " x$amount" else "") + (if (duration > 0) " for $duration turns" else "")
    fun Trade.brief() = "we give ${ourOffers.joinToString { it.brief() }.ifEmpty { "nothing" }} for ${theirOffers.joinToString { it.brief() }.ifEmpty { "nothing" }}"

    /** Called after each turn: proposals that are no longer waiting were answered - say how, with the terms */
    fun settleAnswers(civ: Civilization, notifications: List<String>) {
        for (entry in pending.filter { it.first == civ.civName }.toList()) {
            val other = civ.gameInfo.civilizations.firstOrNull { it.civName == entry.second } ?: continue
            if (other.tradeRequests.any { it.requestingCiv == civ.civID }) continue // still waiting
            pending.remove(entry)
            val answer = when {
                notifications.any { it.contains("[${other.civName}] has accepted your trade request") } -> "was accepted - the deal is in force"
                notifications.any { it.contains("[${other.civName}] has denied your trade request") } -> "was refused"
                notifications.any { it.contains("[${other.civName}] has made a counteroffer") } -> "got a counteroffer, which the officials will weigh"
                notifications.any { it.contains("no longer relevant") } -> "lapsed: one side could no longer supply what was offered (a resource ran out, or a treaty state changed)"
                else -> "ended without a recorded answer"
            }
            log(civ, "Our proposal to ${other.civName} (${entry.third}) $answer.")
        }
    }

    private fun Civilization.known(name: String): Civilization =
        getKnownCivs().firstOrNull { it.civName == name && it.isAlive() } ?: throw IllegalArgumentException("We have not met $name, or they are gone")

    /** {with, give: [offers], want: [offers]}; an offer is {item, amount?} - see options.diplomacy for what can be traded */
    fun proposeTrade(civ: Civilization, json: JsonValue): String {
        val other = civ.known(json.getString("with"))
        if (other.isCityState) throw IllegalArgumentException("City-states do not trade; send them a gift instead")
        val logic = TradeLogic(civ, other)
        val trade = Trade()
        fun add(list: JsonValue?, available: List<TradeOffer>, into: MutableList<TradeOffer>, side: String) {
            list?.forEach { entry ->
                val item = entry.getString("item")
                val offer = available.firstOrNull { it.name.equals(item, ignoreCase = true) && it.amount > 0 }
                    ?: throw IllegalArgumentException(inForce(civ, other, item) ?: "$side cannot offer '$item' right now; tradeable: ${available.filter { it.amount > 0 && it.type !in hiddenOffers }.joinToString { it.name + " x" + it.amount }}")
                val copy = offer.copy()
                val wanted = if (entry.has("amount")) entry.getInt("amount") else if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.Gold) throw IllegalArgumentException("'$item' needs an amount") else 1
                if (wanted < 1 || wanted > offer.amount) throw IllegalArgumentException("$side can offer at most ${offer.amount} of '$item', not $wanted")
                copy.amount = wanted
                into.add(copy)
            }
        }
        add(json.get("give"), logic.ourAvailableOffers, trade.ourOffers, "We")
        add(json.get("want"), logic.theirAvailableOffers, trade.theirOffers, other.civName)
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) throw IllegalArgumentException("An empty trade")
        if (!TradeEvaluation().isTradeValid(trade, civ, other)) throw IllegalArgumentException("That trade is not valid right now")
        // A TradeRequest holds the trade as the receiver sees it, so it goes out reversed
        val acceptable = TradeEvaluation().isTradeAcceptable(trade.reverse(), other, civ)
        // A second offer for the same goods replaces the first - otherwise both can be accepted and paid for
        // Keyed on the goods asked for; gold is a sweetener, not a good, so two offers that both carry gold are not the same offer
        val isGold = { offer: com.unciv.logic.trade.TradeOffer -> offer.type == com.unciv.logic.trade.TradeOfferType.Gold || offer.type == com.unciv.logic.trade.TradeOfferType.Gold_Per_Turn }
        val asked = trade.theirOffers.filterNot(isGold).map { it.name }.toSet().ifEmpty { trade.theirOffers.map { it.name }.toSet() }
        val superseded = other.tradeRequests.filter { request -> request.requestingCiv == civ.civID && request.trade.ourOffers.filterNot(isGold).ifEmpty { request.trade.ourOffers }.any { it.name in asked } }
        other.tradeRequests.removeAll(superseded.toSet())
        if (superseded.isNotEmpty()) pending.removeAll { it.first == civ.civName && it.second == other.civName && it.third != "peace" } // a withdrawn offer cannot be "accepted" later
        other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
        val summary = trade.brief()
        if (superseded.isNotEmpty()) log(civ, "Our earlier offer to ${other.civName} (${superseded.joinToString("; ") { it.trade.reverse().brief() }}) was withdrawn in favour of the new one, which asks for the same goods.")
        pending += Triple(civ.civName, other.civName, summary)
        log(civ, "We proposed a trade to ${other.civName}: $summary.")
        val balance = TradeEvaluation().getTradeAcceptability(trade.reverse(), other, civ, true)
        return (if (superseded.isNotEmpty()) "Your earlier pending offer for the same goods was withdrawn. " else "") + "Proposed to ${other.civName}: $summary. They answer on their turn; by their own reckoning this deal is worth $balance to them (they accept at 0 or more), so they are ${if (acceptable) "likely to accept" else "likely to refuse - sweeten it or ask for less"}."
    }

    fun proposePeace(civ: Civilization, name: String): String {
        val other = civ.known(name)
        if (!civ.isAtWarWith(other)) throw IllegalArgumentException("We are not at war with $name")
        // Their envoy is already waiting with terms: the ruler's "peace" is the answer to it
        heldPeaceOffer(civ, other)?.let { offer ->
            val terms = describeOffer(offer)
            val logic = TradeLogic(civ, other)
            logic.currentTrade.set(offer.trade)
            civ.tradeRequests.remove(offer)
            logic.acceptTrade()
            other.addNotification("[${civ.civName}] has accepted your trade request", com.unciv.logic.civilization.NotificationCategory.Trade, com.unciv.logic.civilization.NotificationIcon.Trade, civ.civName)
            log(civ, "By your order: we accepted ${other.civName}'s offer of peace ($terms).")
            return "Peace made with $name on their terms: $terms."
        }
        val diplomacy = civ.getDiplomacyManager(other)!!
        if (diplomacy.hasFlag(DiplomacyFlags.DeclaredWar)) throw IllegalArgumentException("Too soon: ${diplomacy.getFlag(DiplomacyFlags.DeclaredWar)} more turns of war before peace can be discussed")
        if (diplomacy.hasFlag(DiplomacyFlags.DeclinedPeace)) throw IllegalArgumentException("$name declined peace recently; wait ${diplomacy.getFlag(DiplomacyFlags.DeclinedPeace)} turns")
        // One offer of peace at a time: a repeat replaces the one still waiting, so a ruled power never holds two of ours
        val ours = other.tradeRequests.filter { request -> request.requestingCiv == civ.civID && (request.trade.ourOffers + request.trade.theirOffers).any { it.name == Constants.peaceTreaty } }
        if (ours.isNotEmpty() && other.civID in com.unciv.logic.automation.RulerOverrides.ruledCivs) return "Our offer of peace is already before ${other.civName}'s court; they have not answered yet. Wait, or sweeten it."
        other.tradeRequests.removeAll(ours.toSet())
        val trade = Trade()
        val treaty = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed)
        trade.ourOffers.add(treaty)
        trade.theirOffers.add(treaty)
        val acceptable = TradeEvaluation().isTradeAcceptable(trade.reverse(), other, civ)
        other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
        pending += Triple(civ.civName, other.civName, "peace")
        log(civ, "We proposed peace to ${other.civName}.")
        return "Peace proposed to $name; they answer on their turn and seem ${if (acceptable) "willing" else "unwilling"}."
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
                entry["dealsInForce"] = ours.trades.map { it.brief() }
                val ourLuxuries = luxuries(civ); val theirLuxuries = luxuries(other)
                entry["luxuriesTheyLack"] = (ourLuxuries.keys - theirLuxuries.keys).toList()
                entry["luxuriesWeLack"] = (theirLuxuries.keys - ourLuxuries.keys).toList()
            }
            entry
        }.toList()

    /** Every deal in force, as "Civ: terms" - compared turn to turn so the log can say exactly what began and ended */
    fun dealsInForce(civ: Civilization): Set<String> =
        civ.diplomacy.values.flatMap { manager -> manager.trades.map { "${manager.otherCivName}: ${it.brief().replace(Regex(" for \\d+ turns"), "")}" } }.toSet() // durations tick down, the deal is the same

    private fun heldPeaceOffer(civ: Civilization, other: Civilization) = civ.tradeRequests.firstOrNull { request ->
        request.requestingCiv == other.civID && (request.trade.ourOffers + request.trade.theirOffers).any { it.name == Constants.peaceTreaty } }

    /** A held request is stored as we, the receiver, see it */
    private fun describeOffer(request: TradeRequest): String {
        val extras = { offers: List<TradeOffer> -> offers.filter { it.name != Constants.peaceTreaty }.joinToString { it.brief() } }
        val give = extras(request.trade.ourOffers); val get = extras(request.trade.theirOffers)
        return when { give.isEmpty() && get.isEmpty() -> "a plain peace, nothing paid either way"; give.isEmpty() -> "peace, and they give us $get"; get.isEmpty() -> "peace, for which we give them $give"; else -> "peace; we give $give, they give $get" }
    }

    /** turn on which each waiting offer of peace was first seen, keyed "us<them" */
    private val peaceOfferedOn = HashMap<String, Int>()

    /** Forget everything of one game */
    fun reset() { pending.clear(); peaceOfferedOn.clear() }

    /** Offers of peace wait for the ruler - the officials neither take nor refuse them. Logged when they arrive, withdrawn after some turns unanswered. */
    fun peaceOffers(civ: Civilization, announce: Boolean = false): List<String> {
        val turn = civ.gameInfo.turns
        val waiting = civ.tradeRequests.filter { request -> (request.trade.ourOffers + request.trade.theirOffers).any { it.name == Constants.peaceTreaty } }
        peaceOfferedOn.keys.removeAll { key -> key.startsWith("${civ.civName}<") && waiting.none { key == "${civ.civName}<${civ.gameInfo.getCivilization(it.requestingCiv).civName}" } }
        return waiting.mapNotNull { request ->
            val other = civ.gameInfo.getCivilization(request.requestingCiv)
            val key = "${civ.civName}<${other.civName}"
            val since = peaceOfferedOn.getOrPut(key) { if (announce) log(civ, "${other.civName} offers peace: ${describeOffer(request)}. The offer waits for your answer - order peace with ${other.civName} to accept it; left unanswered it is withdrawn in $PEACE_OFFER_WAITS turns."); turn }
            if (turn - since >= PEACE_OFFER_WAITS) {
                civ.tradeRequests.remove(request); request.decline(civ); peaceOfferedOn.remove(key)
                if (announce) log(civ, "${other.civName}'s offer of peace went unanswered and has been withdrawn.")
                null
            } else "${other.civName} offers ${describeOffer(request)} (waits ${PEACE_OFFER_WAITS - (turn - since)} more turns; order peace with ${other.civName} to accept)"
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
            .filter { trade -> (trade.ourOffers + trade.theirOffers).any { it.duration in 1..within } }
            .map { "${manager.otherCivName}: ${it.brief()}" } }

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
            "note" to "Offers: luxury/strategic resource names, 'Gold' (amount), 'Gold per turn' (amount), 'Open Borders', 'Research Agreement', 'Defensive Pact', 'Embassy'. Friendship, peace and trades are answered by the other civ on its turn - watch the log."
        )
    }
}
