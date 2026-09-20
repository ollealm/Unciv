package com.unciv.app.desktop.ruler

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.app.desktop.DesktopLogBackend
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.RulerOverrides
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.skins.SkinCache
import com.unciv.models.stats.Stat
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.utils.Log
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 *  Headless game host for "ruler" play: AI civs play themselves, steered by standing orders
 *  that an outside orchestrator sets over a localhost JSON API.
 *
 *  Run with working dir android/assets. Args: <dataDir> [port]
 */
object RulerHost {
    private lateinit var dataDir: File
    private var gameInfo: GameInfo? = null
    /** civName -> standing orders */
    private val orders = HashMap<String, StandingOrders>()
    private val publicEvents = ArrayList<Map<String, Any?>>()
    /** civName -> what happened to that civ, turn by turn - the "chancery log" its advisor reads */
    private val chancery = HashMap<String, ArrayList<Map<String, Any?>>>()
    private val units = RulerUnits { civ, text -> log(civ, text) }
    private val diplomacy = RulerDiplomacy { civ, text -> log(civ, text) }

    class StandingOrders {
        var personality = LinkedHashMap<String, Float>()
        var research = ArrayList<String>()
        var policyBranches = ArrayList<String>()
        /** Buildings and units the officials must never queue on their own */
        var neverBuild = ArrayList<String>()
        /** Gold the officials must leave untouched; null = they spend as they see fit */
        var goldReserve: Int? = null
        fun toMap(civ: Civilization? = null) = mapOf(
            "goldReserve" to goldReserve,
            "personality" to personality,
            "neverBuild" to neverBuild,
            "research" to research.filter { civ == null || !civ.tech.isResearched(it) },
            "policyBranches" to policyBranches)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        dataDir = File(args.getOrNull(0) ?: System.getenv("RULER_DATA") ?: "ruler-data").absoluteFile.apply { mkdirs() }
        val port = (args.getOrNull(1) ?: System.getenv("RULER_PORT"))?.toIntOrNull() ?: 8765

        Log.backend = DesktopLogBackend()
        val game = UncivGame(true)
        UncivGame.Current = game
        game.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
            notificationsLogMaxTurns = 30
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        load()
        RulerOverrides.unitHandler = { unit -> unit.civ.civID in RulerOverrides.ruledCivs && units.handle(unit) }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newSingleThreadExecutor() // game state is not thread-safe
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        println("RulerHost listening on http://127.0.0.1:$port, data in $dataDir")
    }

    //region HTTP

    private class ApiError(val status: Int, message: String) : Exception(message)

    private class Png(val bytes: ByteArray)

    private fun handle(exchange: HttpExchange) {
        var status = 200
        val response: Any? = try {
            val raw = exchange.requestBody.readBytes()
            if (exchange.requestURI.path == "/map/overlay") {
                // body: a repaint of the reference map; answer: the same with borders, units, cities and names on top
                val civName = java.net.URLDecoder.decode((exchange.requestURI.rawQuery ?: "").substringAfter("civ=").substringBefore("&"), "UTF-8")
                val base = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(raw)) ?: throw ApiError(400, "Body is not an image")
                val png = MapRenderer.render(ruledCiv(civName), "overlay", base)
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
                return
            }
            val body = raw.toString(Charsets.UTF_8)
            val json = if (body.isBlank()) JsonValue(JsonValue.ValueType.`object`) else JsonReader().parse(body)
            val query = (exchange.requestURI.rawQuery ?: "").split("&").filter { "=" in it }
                .associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            route(exchange.requestMethod, exchange.requestURI.path, query, json)
        } catch (ex: ApiError) {
            status = ex.status
            mapOf("error" to ex.message)
        } catch (ex: Throwable) {
            ex.printStackTrace()
            status = 500
            mapOf("error" to ex.toString())
        }
        val bytes = if (response is Png) response.bytes else Json.write(response).toByteArray()
        exchange.responseHeaders.add("Content-Type", if (response is Png) "image/png" else "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun route(method: String, path: String, query: Map<String, String>, json: JsonValue): Any? {
        fun civ() = ruledCiv(query["civ"] ?: throw ApiError(400, "Missing ?civ="))
        val since = query["since"]?.toIntOrNull() ?: -1
        return when ("$method $path") {
            "GET /state" -> state()
            "GET /nations" -> RulesetCache[com.unciv.models.metadata.BaseRuleset.Civ_V_GnK.fullName]!!.nations.values
                .filter { it.isMajorCiv }.map { mapOf("name" to it.name, "leader" to it.leaderName) }
            "POST /new" -> newGame(json)
            "POST /advance" -> advance(json.getInt("turns", 10), json.getBoolean("wake", false))
            "GET /briefing" -> briefing(civ(), query["since"]?.toIntOrNull())
            "GET /options" -> options(civ())
            "GET /log" -> chanceryLog(civ(), since)
            "GET /lookup" -> lookup(civ(), query["name"] ?: throw ApiError(400, "Missing name"))
            "GET /map.png" -> Png(MapRenderer.render(civ(), query["style"] ?: "atlas"))
            "GET /orders" -> civ().let { orders.getOrPut(it.civName) { StandingOrders() }.toMap(it) }
            "POST /orders" -> setOrders(civ(), json)
            "GET /events" -> publicEvents.filter { (it["turn"] as Int) > since }
            else -> throw ApiError(404, "No route $method $path")
        }
    }

    private fun game() = gameInfo ?: throw ApiError(409, "No game running - POST /new first")
    /** "1250 BC" / "AD 400" for a turn of the running game */
    private fun yearText(turn: Int): String {
        val year = game().getYear(turn - game().turns)
        return if (year < 0) "${-year} BC" else "AD $year"
    }
    private fun ruledCiv(name: String): Civilization {
        val civ = game().civilizations.firstOrNull { it.civName == name } ?: throw ApiError(404, "No civ named $name")
        if (civ.civID !in RulerOverrides.ruledCivs) throw ApiError(403, "$name has no ruler")
        return civ
    }
    private fun GameInfo.majorCivs() = civilizations.filter { it.isMajorCiv() && !it.isSpectator() }

    //endregion
    //region Game lifecycle

    private fun newGame(json: JsonValue): Any? {
        val ruled = json.get("ruled")?.asStringArray()?.toList() ?: throw ApiError(400, "Missing ruled: [nation names]")
        val others = json.get("others")?.asStringArray()?.toList() ?: listOf()
        val newGame = GameStarter.startNewGame(GameSetupInfo(
            GameParameters().apply {
                speed = json.getString("speed", "Quick")
                difficulty = json.getString("difficulty", "Prince")
                numberOfCityStates = json.getInt("cityStates", 4)
                players = ArrayList<Player>().apply {
                    for (nation in ruled + others) add(Player(nation))
                    add(Player(Constants.spectator, PlayerType.Human))
                }
            },
            MapParameters().apply { mapSize = MapSize(json.getString("mapSize", "Small")) }
        ))
        gameInfo = newGame
        UncivGame.Current.gameInfo = newGame
        orders.clear()
        publicEvents.clear()
        chancery.clear()
        units.orders.clear()
        RulerOverrides.lockedCities.clear()
        for (name in ruled) orders[name] = StandingOrders()
        registerRuledCivs()
        save()
        return state()
    }

    private fun registerRuledCivs() {
        RulerOverrides.ruledCivs.clear()
        RulerOverrides.personalities.clear()
        for ((civName, standing) in orders) {
            val civ = game().civilizations.first { it.civName == civName }
            RulerOverrides.ruledCivs += civ.civID
            applyPersonality(civ, standing)
        }
    }

    private fun save() {
        val game = gameInfo ?: return
        val saveData = UncivFiles.gameInfoToString(game, forceZip = true)
        File(dataDir, "save.unciv").writeText(saveData)
        // Also into the dev GUI's save list (working dir is android/assets), so `gradlew desktop:run` can watch the world as spectator
        File("SaveFiles").mkdirs()
        File("SaveFiles/Ruler game").writeText(saveData)
        File(dataDir, "host.json").writeText(Json.write(mapOf(
            "orders" to orders.mapValues { it.value.toMap() },
            "publicEvents" to publicEvents,
            "chancery" to chancery,
            "unitOrders" to units.toMap(),
            "lockedCities" to RulerOverrides.lockedCities.toList(),
            "focusedCities" to RulerOverrides.focusedCities.toList()
        )))
    }

    private fun load() {
        val saveFile = File(dataDir, "save.unciv")
        val hostFile = File(dataDir, "host.json")
        if (!saveFile.exists() || !hostFile.exists()) return
        val loaded = UncivFiles.gameInfoFromString(saveFile.readText())
        gameInfo = loaded
        UncivGame.Current.gameInfo = loaded
        val host = JsonReader().parse(hostFile.readText())
        for (entry in host.get("orders")) orders[entry.name] = StandingOrders().apply { readFrom(entry) }
        for (event in host.get("publicEvents"))
            publicEvents += mapOf("turn" to event.getInt("turn"), "year" to event.getString("year", ""), "text" to event.getString("text"))
        host.get("chancery")?.forEach { civLog ->
            chancery[civLog.name] = ArrayList(civLog.map { mapOf("turn" to it.getInt("turn"), "text" to it.getString("text")) })
        }
        units.load(host.get("unitOrders"))
        RulerOverrides.lockedCities.clear()
        host.get("lockedCities")?.asStringArray()?.let { RulerOverrides.lockedCities += it }
        RulerOverrides.focusedCities.clear()
        host.get("focusedCities")?.asStringArray()?.let { RulerOverrides.focusedCities += it }
        registerRuledCivs()
        println("Loaded game at turn ${loaded.turns}, ruled: ${orders.keys}")
    }

    private fun state(): Map<String, Any?> {
        val game = gameInfo ?: return mapOf("running" to false)
        val winner = game.majorCivs().firstOrNull { it.victoryManager.hasWon() }
        return mapOf(
            "running" to true,
            "turn" to game.turns,
            "year" to yearText(game.turns),
            "ruled" to orders.keys.toList(),
            "majorCivs" to game.majorCivs().map { mapOf("civ" to it.civName, "alive" to it.isAlive()) },
            "winner" to winner?.civName,
            "victoryType" to winner?.victoryManager?.getVictoryTypeAchieved()
        )
    }

    //endregion
    //region Orders

    private fun StandingOrders.readFrom(json: JsonValue) {
        json.get("personality")?.let { values ->
            for (entry in values) {
                if (entry.isNull) continue
                if (PersonalityValue.entries.none { it.name == entry.name })
                    throw ApiError(400, "Unknown personality value ${entry.name}, valid: ${PersonalityValue.entries.joinToString { it.name }}")
                personality[entry.name] = entry.asFloat().coerceIn(0f, 10f)
            }
        }
        json.get("research")?.let { research = ArrayList(it.asStringArray().toList()) }
        json.get("policyBranches")?.let { policyBranches = ArrayList(it.asStringArray().toList()) }
        json.get("neverBuild")?.let { neverBuild = ArrayList(it.asStringArray().toList()) }
        json.get("goldReserve")?.let { goldReserve = if (it.isNull) null else it.asInt().coerceAtLeast(0) }
    }

    private fun applyPersonality(civ: Civilization, standing: StandingOrders) {
        // Rulers decide on war themselves, so by default the automation never declares it
        val personality = Personality().apply { this[PersonalityValue.DeclareWar] = 0f }
        for ((name, value) in standing.personality) personality[PersonalityValue.valueOf(name)] = value
        RulerOverrides.personalities[civ.civID] = personality
        standing.goldReserve?.let { RulerOverrides.goldReserve[civ.civID] = it } ?: RulerOverrides.goldReserve.remove(civ.civID)
    }

    private fun Civilization.city(name: String): City = cities.firstOrNull { it.name == name } ?: throw ApiError(400, "No city named $name")
    private fun Civilization.construction(name: String): INonPerpetualConstruction =
        (gameInfo.ruleset.buildings[name] ?: gameInfo.ruleset.units[name]) ?: throw ApiError(400, "Unknown building or unit $name")
    private fun whyNot(city: City, construction: INonPerpetualConstruction) =
        construction.getRejectionReasons(city.cityConstructions).filter { it.type.shouldShow }.map { it.errorMessage }.toList()

    private fun setOrders(civ: Civilization, json: JsonValue): Any? {
        val ruleset = civ.gameInfo.ruleset
        val standing = orders.getOrPut(civ.civName) { StandingOrders() }
        val alreadyKnown = ArrayList<String>()
        val rejected = ArrayList<String>()
        json.get("research")?.let { goals ->
            val kept = goals.asStringArray().filter { goal ->
                if (goal !in ruleset.technologies) rejected += "No technology is called '$goal'" + (if (goal in ruleset.policies) " - that is a policy; put it in policyBranches" else "; see get_options.futureTechs") + ". The other research goals were kept."
                if (goal in ruleset.technologies && civ.tech.isResearched(goal)) alreadyKnown += goal
                goal in ruleset.technologies
            }
            goals.remove(); json.addChild("research", JsonValue(JsonValue.ValueType.array).also { array -> kept.forEach { array.addChild(JsonValue(it)) } })
        }
        json.get("policyBranches")?.let { wishes ->
            val kept = wishes.asStringArray().filter { wish ->
                val known = wish in ruleset.policyBranches || wish in ruleset.policies
                if (!known) rejected += "No policy or policy branch is called '$wish'; branches: ${ruleset.policyBranches.keys.joinToString()}. The others were kept."
                known
            }
            wishes.remove(); json.addChild("policyBranches", JsonValue(JsonValue.ValueType.array).also { array -> kept.forEach { array.addChild(JsonValue(it)) } })
        }
        standing.readFrom(json)
        applyPersonality(civ, standing)

        val done = ArrayList<String>()
        val failed = ArrayList<String>(rejected)
        /** Each item stands alone: a bad one is reported under "failed" and the rest still go through */
        fun tryEach(list: JsonValue?, action: (JsonValue) -> String) {
            list?.forEach { entry ->
                try { done += action(entry) }
                catch (ex: IllegalArgumentException) { failed += ex.message ?: "failed" }
                catch (ex: ApiError) { failed += ex.message ?: "failed" }
            }
        }

        // Replace a city's whole build queue; the officials then leave it alone until it runs out
        tryEach(json.get("queue")) { order ->
            val city = civ.city(order.getString("city"))
            val items = order.get("items").asStringArray().toList()
            val constructions = items.map { civ.construction(it) }
            for ((index, construction) in constructions.withIndex()) {
                val reasons = whyNot(city, construction)
                if (reasons.isNotEmpty()) throw ApiError(400, "${city.name} cannot build ${items[index]}: ${reasons.joinToString()}")
            }
            city.cityConstructions.constructionQueue.clear()
            for (construction in constructions) city.cityConstructions.addToQueue(construction)
            city.cityConstructions.currentConstructionIsUserSet = true
            RulerOverrides.lockedCities += city.id
            "${city.name} queue is now: ${city.cityConstructions.constructionQueue.joinToString()}"
        }
        // Put something at the front of a city's build queue
        tryEach(json.get("build")) { order ->
            val city = civ.city(order.getString("city"))
            val item = order.getString("item")
            val construction = civ.construction(item)
            val reasons = whyNot(city, construction)
            if (reasons.isNotEmpty()) throw ApiError(400, "${city.name} cannot build $item: ${reasons.joinToString()}")
            city.cityConstructions.constructionQueue.remove(item)
            city.cityConstructions.addToQueue(construction, addToTop = true)
            city.cityConstructions.currentConstructionIsUserSet = true
            RulerOverrides.lockedCities += city.id
            "${city.name} queue is now: ${city.cityConstructions.constructionQueue.joinToString()}"
        }
        // Buy outright with gold
        tryEach(json.get("purchase")) { order ->
            val city = civ.city(order.getString("city"))
            val item = order.getString("item")
            val construction = civ.construction(item)
            val reasons = whyNot(city, construction)
            val cost = construction.getStatBuyCost(city, Stat.Gold)
            val problem = when {
                reasons.isNotEmpty() -> reasons.joinToString()
                !construction.canBePurchasedWithStat(city, Stat.Gold) || cost == null -> "$item cannot be bought with gold"
                civ.gold < cost -> "$item costs $cost gold, treasury has ${civ.gold}"
                else -> null
            }
            if (problem != null) throw ApiError(400, "Could not buy $item in ${city.name}: $problem")
            if (!city.cityConstructions.purchaseConstruction(construction, -1, false)) throw ApiError(400, "Could not buy $item in ${city.name}: no room to place it")
            log(civ, "By your order: bought $item in ${city.name} for $cost gold.")
            "Bought $item in ${city.name} for $cost gold (${civ.gold} left)"
        }
        // Unit orders, diplomacy
        tryEach(json.get("units")) { units.give(civ, it) }
        tryEach(json.get("trade")) { diplomacy.proposeTrade(civ, it) }
        tryEach(json.get("peace")) { diplomacy.proposePeace(civ, it.asString()) }
        tryEach(json.get("friendship")) { diplomacy.proposeFriendship(civ, it.asString()) }
        tryEach(json.get("gift")) { diplomacy.giftGold(civ, it.getString("cityState"), it.getInt("gold")) }
        // Declare war
        tryEach(json.get("declareWar")) { entry ->
            val targetName = entry.asString()
            val target = civ.getKnownCivs().firstOrNull { it.civName == targetName } ?: throw ApiError(400, "We have not met $targetName")
            val diplomacy = civ.getDiplomacyManager(target)!!
            if (diplomacy.diplomaticStatus == DiplomaticStatus.War) throw ApiError(400, "Already at war with $targetName")
            if (!diplomacy.canDeclareWar()) throw ApiError(400, "Cannot declare war on $targetName now")
            diplomacy.declareWar()
            addPublicEvent("${civ.civName} has declared war on $targetName!")
            log(civ, "By your order: we declared war on $targetName.")
            "Declared war on $targetName"
        }
        if (alreadyKnown.isNotEmpty()) failed += "Already known, dropped from the research goals: ${alreadyKnown.joinToString()}"
        standing.research.removeAll { civ.tech.isResearched(it) }
        // City focus: which yields the citizens work for
        tryEach(json.get("cityFocus")) { order ->
            val city = civ.city(order.getString("city"))
            val focus = com.unciv.logic.city.CityFocus.entries.firstOrNull { it.name.equals(order.getString("focus"), ignoreCase = true) || it.name.equals(order.getString("focus") + "Focus", ignoreCase = true) }
                ?: throw ApiError(400, "Unknown focus; use ${com.unciv.logic.city.CityFocus.entries.joinToString { it.name }}")
            city.setCityFocus(focus)
            if (focus == com.unciv.logic.city.CityFocus.NoFocus) RulerOverrides.focusedCities -= city.id else RulerOverrides.focusedCities += city.id
            city.reassignAllPopulation()
            "${city.name} now works its tiles for ${focus.name}"
        }
        applyStandingOrders(civ)
        save()
        return mapOf("orders" to standing.toMap(civ), "done" to done, "failed" to failed,
            "researchQueue" to civ.tech.techsToResearch, "gold" to civ.gold)
    }

    /** Called before every turn so the automation finds research and policies already chosen the ruler's way */
    private fun applyStandingOrders(civ: Civilization) {
        val standing = orders[civ.civName] ?: return
        val ruleset = civ.gameInfo.ruleset

        val goal = standing.research.firstOrNull { !civ.tech.isResearched(it) }
        if (goal != null) {
            val path = civ.tech.getRequiredTechsToDestination(ruleset.technologies[goal]!!).map { it.name }
            if (path.isNotEmpty() && civ.tech.techsToResearch != path) {
                civ.tech.techsToResearch.clear()
                civ.tech.techsToResearch.addAll(path)
            }
        }

        while (civ.policies.canAdoptPolicy()) {
            val policy = standing.policyBranches.asSequence().firstNotNullOfOrNull { wish ->
                val branch = ruleset.policyBranches[wish]
                val named = ruleset.policies[wish]
                when {
                    branch != null && civ.policies.isAdoptable(branch) -> branch
                    branch != null -> branch.policies.firstOrNull { civ.policies.isAdoptable(it) }
                    named != null && civ.policies.isAdoptable(named) -> named   // a single policy the ruler named, e.g. Monarchy
                    else -> null
                }
            } ?: break // nothing preferred is available - the automation picks
            civ.policies.adopt(policy)
            log(civ, "Adopted the policy ${policy.name}.")
        }
    }

    //endregion
    //region Turns, chancery log and public events

    private fun log(civ: Civilization, text: String) {
        chancery.getOrPut(civ.civName) { ArrayList() } += mapOf("turn" to civ.gameInfo.turns, "text" to text)
    }

    private fun chanceryLog(civ: Civilization, since: Int) =
        (chancery[civ.civName] ?: emptyList()).filter { (it["turn"] as Int) > since }

    private val noise = listOf("has no work to do", "will end in", "will end next turn", "can be promoted", "assigned you a new quest", "no longer needs your help")

    /** What one ruled civ looked like before a turn, to explain afterwards what the officials did */
    private class CivSnapshot(civ: Civilization) {
        val researching = civ.tech.currentTechnologyName()
        val queues = civ.cities.associate { it.name to it.cityConstructions.constructionQueue.toList() }
        val units = civ.units.getCivUnits().associate { it.id to Pair(it.name, it.getTile()) }
        val gold = civ.gold
        val happiness = civ.getHappiness()
        val deals = RulerHost.dealsOf(civ)
    }

    internal fun dealsOf(civ: Civilization) = diplomacy.dealsInForce(civ)

    private fun recordChancery(civ: Civilization, before: CivSnapshot) {
        val turn = civ.gameInfo.turns
        // The game's own notifications first - they cover research, buildings, combat, diplomacy
        val notifications = civ.notificationsLog.lastOrNull()?.takeIf { it.turn == turn - 1 }?.notifications ?: emptyList()
        // Other civs answer on their own turn, after ours - those notices are still in the current list
        diplomacy.settleAnswers(civ, notifications.map { it.text } + civ.notifications.map { it.text })
        for (notification in notifications) {
            if (noise.any { it in notification.text }) continue
            log(civ, notification.text.replace("[", "").replace("]", ""))
        }
        // A ruler's queue binds until it has run out
        // (a queue that ran out mid-turn is refilled by the officials at once, so "empty" is not enough of a test:
        //  a front item the ruler never ordered means the ruler's queue is spent)
        for (city in civ.cities) {
            val queue = city.cityConstructions.constructionQueue
            val ordered = before.queues[city.name] ?: emptyList()
            if (queue.isEmpty() || city.cityConstructions.getCurrentConstruction() is com.unciv.models.ruleset.PerpetualConstruction
                || queue.first() !in ordered) RulerOverrides.lockedCities -= city.id
        }
        // Then what the officials changed on their own
        for (city in civ.cities) {
            val was = before.queues[city.name] ?: continue
            val now = city.cityConstructions.constructionQueue
            val built = notifications.filter { "has been built in [${city.name}]" in it.text }.map { it.text.substringAfter("[").substringBefore("]") }
            for (item in was) if (item !in now && item !in built) {
                val construction = civ.gameInfo.ruleset.buildings[item] ?: civ.gameInfo.ruleset.units[item]
                val reasons = construction?.let { whyNot(city, it) } ?: emptyList()
                log(civ, "The officials dropped $item from ${city.name}'s queue" + (if (reasons.isNotEmpty()) " because ${reasons.joinToString()}." else ", replacing it with their own choice."))
            }
            for (item in now) if (item !in was)
                log(civ, "The officials queued $item in ${city.name}.")
        }
        for (city in civ.cities) if (city.name !in before.queues) log(civ, "Founded ${city.name}.")
        val alive = civ.units.getCivUnits().map { it.id }.toSet()
        for ((id, unit) in before.units) if (id !in alive && unit.first != "Settler") // settlers vanish by founding
        {
            val fell = (notifications.map { it.text } + civ.notifications.map { it.text })
                .any { unit.first in it && (("destroyed" in it) || ("captured" in it) || ("was killed" in it)) }
            log(civ, if (fell) "Our ${unit.first} was lost in action ${describeWhere(civ, unit.second)}."
                else "Our ${unit.first} ${describeWhere(civ, unit.second)} is no longer in service.")
        }
        applyStandingOrders(civ) // a finished goal leaves the queue empty until the next goal's path is set - not idleness
        val researching = civ.tech.currentTechnologyName()
        if (researching == null) {
            if (before.researching != null) log(civ, "No research goal remains; the officials choose the next technology themselves until goals are given.")
        } else if (before.researching != researching && researching !in (orders[civ.civName]?.research ?: emptyList()))
            log(civ, "Research is now on $researching.")
        // Deals: say exactly what came into force and what ended
        val dealsNow = diplomacy.dealsInForce(civ)
        for (deal in dealsNow - before.deals) log(civ, "A deal came into force with $deal.")
        for (deal in before.deals - dealsNow) log(civ, "A deal ended with $deal.")
        // The ruler's never-build list beats the officials' own choices
        val forbidden = orders[civ.civName]?.neverBuild ?: emptyList<String>()
        val noWonders = forbidden.any { it.equals("all wonders", ignoreCase = true) || it.equals("wonders", ignoreCase = true) }
        val noSoldiers = forbidden.any { it.equals("all military units", ignoreCase = true) || it.equals("military units", ignoreCase = true) }
        if (forbidden.isNotEmpty()) for (city in civ.cities) repeat(3) {
            val current = city.cityConstructions.currentConstructionName()
            val banned = current in forbidden
                || noWonders && civ.gameInfo.ruleset.buildings[current]?.isAnyWonder() == true
                || noSoldiers && civ.gameInfo.ruleset.units[current]?.isMilitary == true
            if (banned && city.id !in RulerOverrides.lockedCities) { // the user-set flag outlives the ruler's queue, the lock does not
                city.cityConstructions.constructionQueue.remove(current)
                log(civ, "The officials wanted $current in ${city.name}; it is on the never-build list, so it was struck.")
                if (city.cityConstructions.constructionQueue.isEmpty()) city.cityConstructions.chooseNextConstruction()
            }
        }
        if (civ.gold < before.gold - 40) log(civ, "The officials spent ${before.gold - civ.gold} gold (treasury now ${civ.gold}).")
        if (before.happiness >= 0 && civ.getHappiness() < 0) log(civ, "The people have become unhappy (${civ.getHappiness()}).")
    }

    private fun describeWhere(civ: Civilization, tile: Tile): String {
        val nearest = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return "far from home"
        val distance = nearest.getCenterTile().aerialDistanceTo(tile)
        return if (distance == 0) "in ${nearest.name}" else "$distance tiles from ${nearest.name}"
    }

    private class WorldSnapshot(game: GameInfo) {
        val wars = HashSet<Set<String>>()
        val cityOwners = HashMap<String, String>()
        val wonders = HashMap<String, String>()
        val eras = HashMap<String, String>()
        val alive = HashSet<String>()
        init {
            for (civ in game.civilizations) {
                if (civ.isBarbarian || civ.isSpectator()) continue
                if (civ.isAlive()) alive += civ.civName
                if (civ.isMajorCiv()) eras[civ.civName] = civ.getEra().name
                for (city in civ.cities) {
                    cityOwners[city.name] = civ.civName
                    for (building in city.cityConstructions.getBuiltBuildings())
                        if (building.isWonder) wonders[building.name] = civ.civName
                }
                for (diplomacy in civ.diplomacy.values)
                    if (diplomacy.diplomaticStatus == DiplomaticStatus.War && !diplomacy.otherCiv.isBarbarian)
                        wars += setOf(civ.civName, diplomacy.otherCivName)
            }
        }
    }

    private fun addPublicEvent(text: String) {
        publicEvents += mapOf("turn" to game().turns, "year" to yearText(game().turns), "text" to text)
    }

    private fun recordPublicEvents(before: WorldSnapshot, after: WorldSnapshot) {
        for (war in after.wars - before.wars) addPublicEvent("War has broken out between ${war.joinToString(" and ")}.")
        for (war in before.wars - after.wars)
            if (war.all { it in after.alive }) addPublicEvent("${war.joinToString(" and ")} have made peace.")
        for ((city, owner) in after.cityOwners) {
            val previousOwner = before.cityOwners[city]
            if (previousOwner == null) {
                if (owner in after.eras) addPublicEvent("$owner founded the city of $city.") // city-states founding themselves is not news
            }
            else if (previousOwner != owner) addPublicEvent("$owner captured $city from $previousOwner!")
        }
        for (city in before.cityOwners.keys - after.cityOwners.keys) addPublicEvent("The city of $city has been destroyed.")
        for ((wonder, owner) in after.wonders)
            if (wonder !in before.wonders) addPublicEvent("$owner completed the wonder $wonder.")
        for ((civ, era) in after.eras)
            if (before.eras[civ] != era) addPublicEvent("$civ entered the $era.")
        for (civ in before.alive - after.alive) addPublicEvent("$civ has been destroyed.")
    }

    /** Log lines an advisor would want to see at once, not at the next scheduled look-in */
    // Not answers to our own proposals: acting on one sends another, and the advisor would be woken by his own echo every turn.
    // Not "demands": that is a city asking for a luxury, not a neighbour asking for tribute.
    private val wakeWorthy = listOf("dropped .* because", "declared war", "denounced", "lost in action",
        "awaits orders", "captured", "has been destroyed", "has been stuck").map { Regex(it, RegexOption.IGNORE_CASE) }

    private val warningsWoken = HashMap<String, HashSet<String>>()

    private fun advance(turns: Int, wakeOnEvents: Boolean = false): Any? {
        val game = game()
        if (game.majorCivs().any { it.victoryManager.hasWon() }) throw ApiError(409, "The game is over")
        val startTurn = game.turns
        val roundStart = WorldSnapshot(game)
        var stopReason: String? = null
        val ruledCivs = orders.keys.map { name -> game.civilizations.first { it.civName == name } }.filter { it.isAlive() }
        // What each advisor has already been shown; anything new below is worth waking them for
        val warningsSeen = ruledCivs.associate { civ -> civ.civName to warnings(civ).map { it.replace(Regex("-?\\d+"), "#") }.toSet() }
        val wake = LinkedHashMap<String, List<String>>()

        for (i in 1..turns) {
            val before = WorldSnapshot(game)
            val civSnapshots = ruledCivs.associateWith { CivSnapshot(it) }
            for (civ in ruledCivs) applyStandingOrders(civ)
            game.nextTurn()
            val after = WorldSnapshot(game)
            recordPublicEvents(before, after)
            for (civ in ruledCivs) recordChancery(civ, civSnapshots[civ]!!)

            for (civ in ruledCivs) {
                val news = chanceryLog(civ, game.turns - 1).map { it["text"] as String }.filter { line -> wakeWorthy.any { it.containsMatchIn(line) } }
                // A warning wakes an advisor once per game: many of them flicker on and off between turns, and every look-in shows them all anyway
                val everSeen = warningsWoken.getOrPut(civ.civName) { HashSet(warningsSeen[civ.civName] ?: emptySet()) }
                val fresh = warnings(civ).map { it.replace(Regex("-?\\d+"), "#") }.filter { everSeen.add(it) }
                    .filter { "no defender" in it || "unhappy" in it || "treasury" in it.lowercase() || "no military" in it }
                if (news.isNotEmpty() || fresh.isNotEmpty()) wake[civ.civName] = news + fresh
            }
            val winner = game.majorCivs().firstOrNull { it.victoryManager.hasWon() }
            stopReason = when {
                winner != null -> "${winner.civName} has won a ${winner.victoryManager.getVictoryTypeAchieved()} victory"
                else -> orders.keys.firstNotNullOfOrNull { ruled -> councilEvent(ruled, roundStart, after) }
            }
            if (stopReason != null || (wakeOnEvents && wake.isNotEmpty())) break
        }
        save()
        return mapOf(
            "fromTurn" to startTurn,
            "fromYear" to yearText(startTurn),
            "turn" to game.turns,
            "year" to yearText(game.turns),
            "stoppedEarly" to stopReason,
            "wake" to wake,
            "publicEvents" to publicEvents.filter { (it["turn"] as Int) > startTurn },
            "rankings" to rankings()
        )
    }

    /** Something a ruler must hear about right away, ending the round early */
    private fun councilEvent(ruled: String, roundStart: WorldSnapshot, now: WorldSnapshot): String? {
        if (ruled !in now.alive) return if (ruled in roundStart.alive) "$ruled has been destroyed" else null // a realm falls only once
        val newEnemies = (now.wars - roundStart.wars).filter { ruled in it }.flatten().filter { it != ruled }
            .filter { enemy -> game().civilizations.first { it.civName == enemy }.isMajorCiv() }
        if (newEnemies.isNotEmpty()) return "$ruled is now at war with ${newEnemies.joinToString()}"
        val lost = roundStart.cityOwners.filter { it.value == ruled && now.cityOwners[it.key] != ruled }.keys
        if (lost.isNotEmpty()) return "$ruled lost ${lost.joinToString()}"
        return null
    }

    private fun rankings() = game().majorCivs().filter { it.isAlive() }
        .sortedByDescending { it.calculateTotalScore() }
        .map { mapOf("civ" to it.civName, "score" to it.calculateTotalScore().toInt(), "cities" to it.cities.size) }

    //endregion
    //region Briefing

    private fun briefing(civ: Civilization, logSince: Int? = null): Map<String, Any?> {
        for (city in civ.cities) city.cityStats.update() // yields are cached per turn; stale ones showed food 0 and "0 turns left"
        val next = civ.stats.statsForNextTurn
        val turn = civ.gameInfo.turns
        return mapOf(
            "civ" to civ.civName,
            "turn" to turn,
            "year" to yearText(turn),
            "era" to civ.getEra().name,
            "score" to civ.calculateTotalScore().toInt(),
            "gold" to civ.gold,
            "perTurn" to mapOf("gold" to next.gold.toInt(), "science" to next.science.toInt(), "culture" to next.culture.toInt()),
            "happiness" to civ.getHappiness(),
            "happinessBreakdown" to civ.stats.getHappinessBreakdown().filter { it.value != 0f }.mapValues { it.value.toInt() },
            "warnings" to warnings(civ),
            "researching" to civ.tech.currentTechnologyName(),
            "researchQueue" to civ.tech.techsToResearch,
            "techsResearched" to civ.tech.techsResearched.size,
            "policies" to civ.policies.getAdoptedPolicies().toList(),
            "culture" to mapOf("stored" to civ.policies.storedCulture, "neededForNextPolicy" to civ.policies.getCultureNeededForNextPolicy()),
            "militaryStrength" to civ.getStatForRanking(RankingType.Force),
            "units" to civ.units.getCivUnits().map { units.describeUnit(civ, it) }.toList(),
            "citySites" to units.citySites(civ),
            "cities" to civ.cities.map { city ->
                val stats = city.cityStats.currentCityStats
                mapOf("name" to city.name, "pop" to city.population.population, "capital" to city.isCapital(),
                    "buildQueue" to city.cityConstructions.constructionQueue,
                    "turnsLeft" to city.cityConstructions.currentConstructionName().let { current ->
                        if (current.isEmpty()) null else city.cityConstructions.turnsToConstruction(current) },
                    "production" to stats.production.toInt(), "foodSurplus" to stats.food.toInt(),
                    "unhappiness" to city.cityStats.happinessList.values.filter { it < 0 }.sum().toInt(),
                    "focus" to city.getCityFocus().name,
                    "health" to city.health, "garrison" to city.getCenterTile().militaryUnit?.name)
            },
            "threats" to threats(civ),
            "knownCivs" to diplomacy.relations(civ),
            "log" to chanceryLog(civ, logSince ?: (turn - 12)).map { "T${it["turn"]}: ${it["text"]}" },
            "standingOrders" to orders[civ.civName]?.toMap(civ)
        )
    }

    private fun warnings(civ: Civilization): List<String> {
        val warnings = ArrayList<String>()
        if (civ.getHappiness() < 0) warnings += "The people are unhappy (${civ.getHappiness()}): growth and production suffer. Luxuries, happiness buildings or fewer new cities help."
        if (civ.tech.currentTechnologyName() == null) warnings += "Research is idle - give research goals."
        val standing = orders[civ.civName]
        val goalsLeft = standing?.research?.count { !civ.tech.isResearched(it) } ?: 0
        if (standing != null && goalsLeft == 0) warnings += "All research goals are reached - the officials pick techs themselves until new goals are given."
        else if (goalsLeft == 1) warnings += "Only one research goal left - add more."
        val bestStrength = civ.units.getCivUnits().maxOfOrNull { it.baseUnit.strength.coerceAtLeast(it.baseUnit.rangedStrength) } ?: 0
        for (city in civ.cities) {
            val defender = city.getCenterTile().militaryUnit
            if (defender == null) warnings += "${city.name} has no defender in it."
            else if (defender.baseUnit.strength.coerceAtLeast(defender.baseUnit.rangedStrength) * 2 < bestStrength || defender.name == "Scout")
                warnings += "${city.name} is only held by a ${defender.name} - weak for this age."
        }
        for (unit in civ.units.getCivUnits()) if (unit.name.startsWith("Great ") && units.orders[unit.id]?.action == "hold")
            warnings += "${unit.name} #${unit.id} ${describeWhere(civ, unit.getTile())} awaits orders."
        if (civ.policies.canAdoptPolicy()) warnings += "A policy can be adopted but none of the preferred branches has one available - add a branch to the policy order."
        if (civ.units.getCivUnits().any { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity) } && units.citySites(civ).isEmpty())
            warnings += "We have a Settler but no free land for a city within reach - every site is too close to an existing city or unexplored. Explore further, or disband/keep the Settler."
        for (city in civ.cities) {
            if (city.cityConstructions.constructionQueue.isEmpty()) warnings += "${city.name} has an empty build queue."
            if (city.cityStats.currentCityStats.food < 0) warnings += "${city.name} is starving."
        }
        if (civ.gold < 0 || civ.gold + civ.stats.statsForNextTurn.gold * 5 < 0) warnings += "The treasury is running dry (${civ.gold} gold, ${civ.stats.statsForNextTurn.gold.toInt()} per turn)."
        if (civ.units.getCivUnits().none { !it.isCivilian() }) warnings += "We have no military units at all."
        return warnings
    }

    /** Barbarian camps we can see and foreign soldiers close to our cities */
    private fun threats(civ: Civilization): List<String> {
        val threats = ArrayList<String>()
        val tileMap = civ.gameInfo.tileMap
        for (camp in civ.gameInfo.barbarians.encampments) {
            if (camp.destroyed) continue
            val tile = tileMap[camp.position]
            if (tile !in civ.viewableTiles) continue
            threats += "Barbarian camp at ${RulerUnits.where(civ, tile)}"
        }
        for (tile in civ.viewableTiles) {
            val unit = tile.militaryUnit ?: continue
            if (unit.civ == civ || unit.civ.isCityState) continue
            val nearest = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: continue
            if (nearest.getCenterTile().aerialDistanceTo(tile) > 4) continue
            val stance = when {
                unit.civ.isBarbarian -> "hostile"
                civ.isAtWarWith(unit.civ) -> "AT WAR"
                civ.getDiplomacyManager(unit.civ)?.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship) == true -> "friend, harmless"
                else -> "at peace"
            }
            threats += "${unit.civ.civName} ${unit.name} (strength ${unit.baseUnit.strength.coerceAtLeast(unit.baseUnit.rangedStrength)}, $stance) at ${RulerUnits.where(civ, tile)}"
        }
        return threats
    }

    /** What the rulebook says about a building, wonder, unit or technology - so nobody has to advise from memory */
    private fun lookup(civ: Civilization, name: String): Map<String, Any?> {
        val ruleset = civ.gameInfo.ruleset
        fun <T> find(map: Map<String, T>) = map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        find(ruleset.buildings)?.let { b ->
            return mapOf("kind" to if (b.isWonder) "wonder" else if (b.isNationalWonder) "national wonder" else "building", "name" to b.name,
                "cost" to b.cost, "maintenance" to b.maintenance, "requiresTech" to b.requiredTech, "requiresBuilding" to b.requiredBuilding,
                "yields" to b.cloneStats().toString(), "happiness" to b.happiness.toInt(),
                "effects" to b.uniques, "alreadyBuiltSomewhere" to (b.isWonder && civ.gameInfo.getCities().any { it.cityConstructions.isBuilt(b.name) }),
                "weHaveTheTech" to (b.requiredTech == null || civ.tech.isResearched(b.requiredTech!!)))
        }
        find(ruleset.units)?.let { u ->
            return mapOf("kind" to "unit", "name" to u.name, "cost" to u.cost, "strength" to u.strength, "rangedStrength" to u.rangedStrength, "range" to u.range,
                "movement" to u.movement, "requiresTech" to u.requiredTech, "requiresResource" to u.requiredResource, "upgradesTo" to u.upgradesTo,
                "obsoleteWith" to u.obsoleteTech, "abilities" to u.uniques, "weHaveTheTech" to (u.requiredTech == null || civ.tech.isResearched(u.requiredTech!!)))
        }
        find(ruleset.technologies)?.let { t ->
            return mapOf("kind" to "technology", "name" to t.name, "era" to t.era(), "researched" to civ.tech.isResearched(t.name),
                "turnsFromHere" to civ.tech.getRequiredTechsToDestination(t).sumOf { civ.tech.turnsToTech(it.name).toIntOrNull() ?: 0 },
                "prerequisites" to t.prerequisites.toList(),
                "unlocks" to (ruleset.buildings.values.filter { it.requiredTech == t.name }.map { it.name } + ruleset.units.values.filter { it.requiredTech == t.name }.map { it.name }),
                "effects" to t.uniques)
        }
        find(ruleset.policies)?.let { p -> return mapOf("kind" to "policy", "name" to p.name, "branch" to p.branch.name, "effects" to p.uniques, "adopted" to civ.policies.isAdopted(p.name)) }
        throw ApiError(404, "The rulebook has nothing called '$name'")
    }

    private fun options(civ: Civilization): Map<String, Any?> {
        val ruleset = civ.gameInfo.ruleset
        return mapOf(
            "researchableNow" to ruleset.technologies.values.filter { civ.tech.canBeResearched(it.name) }
                .map { mapOf("tech" to it.name, "turns" to civ.tech.turnsToTech(it.name)) },
            "researchGoals" to "Any technology name works as a research goal; the path to it is researched automatically.",
            "futureTechs" to ruleset.technologies.values.filter { !civ.tech.isResearched(it.name) }.map { it.name },
            "policyBranches" to ruleset.policyBranches.values.map {
                mapOf("branch" to it.name, "era" to it.era,
                    "adopted" to civ.policies.isAdopted(it.name),
                    "complete" to civ.policies.isAdopted("${it.name} Complete"))
            },
            "personality" to mapOf(
                "current" to RulerOverrides.personalities[civ.civID]?.let { p -> PersonalityValue.entries.associate { it.name to p[it] } },
                "meaning" to mapOf(
                    "Production/Food/Gold/Science/Culture/Happiness/Faith" to "how much cities favour buildings and tiles that yield that stat when the officials choose",
                    "Military" to "how large an army the officials keep and how often cities train units on their own",
                    "Aggressive" to "whether soldiers push out to attack or stay home and defend",
                    "Expansion" to "how eagerly cities train settlers on their own; 10 means settlers whenever affordable",
                    "Commerce" to "willingness to trade luxuries and make deals",
                    "Diplomacy" to "effort spent on city-states and friendships",
                    "Loyal" to "how much they honour friendships",
                    "DeclareWar" to "0 = never on their own; higher = they may start wars they think they can win")),
            "diplomacy" to diplomacy.tradeOptions(civ),
            "buildable" to civ.cities.associate { city ->
                city.name to (ruleset.buildings.values.asSequence() + ruleset.units.values.asSequence())
                    .filter { it.isBuildable(city.cityConstructions) }
                    .map { construction ->
                        val buy = construction.getStatBuyCost(city, Stat.Gold)?.takeIf { construction.canBePurchasedWithStat(city, Stat.Gold) }
                        "${construction.name} (${city.cityConstructions.turnsToConstruction(construction.name)}t" + (buy?.let { ", buy $it gold" } ?: "") + ")"
                    }.toList()
            },
            "gold" to civ.gold,
            "canDeclareWarOn" to civ.getKnownCivs()
                .filter { it.isAlive() && !it.isBarbarian && !civ.isAtWarWith(it) && civ.getDiplomacyManager(it)!!.canDeclareWar() }
                .map { it.civName }.toList()
        )
    }

    //endregion
}
