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
import com.unciv.logic.civilization.diplomacy.WarType
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
    private val diplomacy = RulerDiplomacy({ civ, text -> log(civ, text) }, { text -> addPublicEvent(text) })
    private val trail = RulerTrail()
    private val ages = RulerAges(trail, yearText = { turn -> yearText(turn) }, stillFor = { unit -> units.turnsStill(unit) })

    class StandingOrders {
        var personality = LinkedHashMap<String, Float>()
        var research = ArrayList<String>()
        var policyBranches = ArrayList<String>()
        /** Buildings and units the officials must never queue on their own */
        var neverBuild = ArrayList<String>()
        /** Kinds of deal the officials must never propose on their own: Open Borders, Research Agreement, Defensive Pact, Declaration of Friendship, luxuries, calls to war, all */
        var neverPropose = ArrayList<String>()
        /** Gold the officials must leave untouched; null = they spend as they see fit */
        var goldReserve: Int? = null
        /** Faith the officials leave untouched; the ruler's own purchases with faith are not bound by it */
        var faithReserve: Int? = null
        /** The officials may not give gold to city-states on their own */
        var noGifts = false
        /** When the officials re-arm units with gold on their own: always, war (only while at war with a major power), never */
        var officialsRearm = "war"
        /** Keep a soldier inside every city: when one stands empty, the nearest free soldier is sent in under a garrison order */
        var garrisonEveryCity = false
        /** The great power our vote for world leader is pledged to, when the world votes; null = we abstain */
        var worldVote: String? = null
        /** The posture of arms: warAim (an enemy city the host converges on), stance attack|defend|raid, reserve (soldiers kept home) */
        var warAim: String? = null
        var stance: String = "attack"
        var reserve: Int = 0
        /** Warnings the advisor has weighed and let stand (acknowledge), numbers struck out: shown folded while they stand, forgotten once they clear */
        var acknowledged = ArrayList<String>()
        fun toMap(civ: Civilization? = null) = (if (civ == null) mapOf("acknowledged" to acknowledged) else emptyMap()) + mapOf(
            "goldReserve" to goldReserve,
            "faithReserve" to faithReserve,
            "noGifts" to noGifts,
            "officialsRearm" to officialsRearm,
            "garrisonEveryCity" to garrisonEveryCity,
            "worldVote" to (worldVote ?: "not pledged: we abstain when the world votes"),
            "posture" to (if (civ?.isAtWar() == true || warAim != null || stance != "attack" || reserve != 0) mapOf("warAim" to warAim, "stance" to stance, "reserve" to reserve)
                else "the officials' own (attack when at war); set it when a war comes"),
            "personality" to personality,
            "neverBuild" to neverBuild,
            "neverPropose" to neverPropose,
            "research" to research.filter { civ == null || !civ.tech.isResearched(it) || civ.gameInfo.ruleset.technologies[it]?.isContinuallyResearchable() == true },
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
        RulerOverrides.forbidden = { civ, construction -> civ.civID in RulerOverrides.ruledCivs && isForbidden(civ, construction) }
        RulerOverrides.spendingNoted = { civ, gold, what -> if (civ.civID in RulerOverrides.ruledCivs) spending.getOrPut(civ.civName) { ArrayList() } += Pair(gold, what) }
        // Whatever goes wrong with one unit's order costs that order, never the turn: Run 20 stopped dead at T123 on an unreachable tile
        RulerOverrides.unitHandler = { unit -> unit.civ.civID in RulerOverrides.ruledCivs && try { units.handle(unit) } catch (e: Exception) {
            System.err.println("Order of ${unit.civ.civName}'s ${unit.name} #${unit.id} failed: $e")
            units.dropAfterFailure(unit)
            false
        } }
        RulerOverrides.hasOrder = { unit -> unit.id in units.orders }
        RulerOverrides.blockFailed = { civ, what, why -> log(civ, "The officials could not finish $what this turn: $why. The units it moved stand where they were left, and the officials try again next turn.") }
        RulerOverrides.unitNotMoved = { civ, unit, why -> log(civ, "Our ${unit.name} could not be moved this turn: $why. It stands ${describeWhere(civ, unit.getTile())}, and the officials try again next turn.") }
        units.researchRefill = { civ -> applyResearchGoals(civ) }
        // A city's defender warning, acknowledged while a garrison march was on its way, shows in full again when that march fails (Grand River stood empty 13 turns under an acknowledged one, Run 23)
        units.garrisonMissed = { civ, city -> city.getCity()?.name?.lowercase()?.let { name -> orders[civ.civName]?.acknowledged?.removeIf { it.startsWith("$name has no defender") || it.startsWith("$name is only held by") } } }
        RulerOverrides.rulersFirst = { civ -> units.rearmDue(civ) }
        RulerOverrides.worldVoteOf = { civ -> castWorldVote(civ) }
        RulerOverrides.promised = { civ -> diplomacy.promised(civ) }
        // The officials make demands of their own, and the chancery hears of each (a demand made of us waits on the desk instead - RulerDiplomacy.demands)
        // The demand not to attack us is never theirs for a ruled civ (a refusal of it is a war, the ruler's alone to bring - NextTurnAutomation), so no line warns of one
        RulerOverrides.demandMade = { civ, other, demand -> log(civ, "The officials made a demand of ${other.civName}: \"${demand.demandText}\" " +
            (if (other.civID in RulerOverrides.ruledCivs) "${other.civName} is an advised court: its advisor answers at its next look-in" else "They answer on their turn") + "; a demand is resented whatever the answer.") }
        // Every war the engine declares, whoever's: the world's events say who drew the sword and who was pulled in, and the council is framed from it
        RulerOverrides.warDeclared = { war -> noteWar(war) }
        RulerOverrides.underOrders = { unit -> unit.civ.civID in RulerOverrides.ruledCivs && units.underOrders(unit) }
        RulerOverrides.tradeAnswered = { request, receiver, outcome -> diplomacy.settle(request, receiver, outcome) }
        RulerOverrides.nearWinner = { other -> diplomacy.nearVictory(other) != null }
        RulerOverrides.faithSpent = { civ, faith, what -> faithSpending.getOrPut(civ.civName) { ArrayList() } += Pair(faith, what) }
        RulerOverrides.freeTechTaken = { civ, tech, goal -> freeTechs.getOrPut(civ.civName) { ArrayList() } += Pair(tech, goal) }
        RulerOverrides.unitDisbanded = { civ, unit, why -> if (civ.civID in RulerOverrides.ruledCivs) disbandedFor.getOrPut(civ.civName) { HashMap() }[unit.id] = why }
        RulerOverrides.struck = { strike ->
            if (strike.attacker.civID in RulerOverrides.ruledCivs) strikes.getOrPut(strike.attacker.civName) { ArrayList() } += strike
            if (strike.targetCivName in orders) blows.getOrPut(strike.targetCivName) { ArrayList() } += strike
        }
        RulerOverrides.unitExpended = { civ, unit, why -> expended.getOrPut(civ.civName) { HashMap() }[unit.id] = Pair(why, unit.getTile()) }
        RulerOverrides.unitLost = { loss ->
            // The chancery's losses and the trail's fallen marks are the ruled realms' own; an oddity of arms can be anyone's
            if (loss.civ.civID in RulerOverrides.ruledCivs) { losses.getOrPut(loss.civ.civName) { ArrayList() } += loss; trail.lostInBattle[loss.unit.id] = RulerTrail.Stand(loss.where.position.x.toInt(), loss.where.position.y.toInt(), loss.civ.civName, loss.unit.isMilitary(), loss.unit.name) }
            ages.noteLoss(loss, game().turns + 1)
        }

        guardHooks()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newSingleThreadExecutor() // game state is not thread-safe
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        println("RulerHost listening on http://127.0.0.1:$port, data in $dataDir")
    }

    /** Every hook the host lends the engine runs inside the engine's turn: one that throws would take the turn with it. Each is wrapped once, here, so a
     *  failure prints and gives the engine's own default (audit 2026-10-03) */
    private fun <T> hook(name: String, fallback: T, body: () -> T): T =
        try { body() } catch (ex: Exception) { System.err.println("Host hook $name failed: $ex"); ex.printStackTrace(); fallback }

    private fun guardHooks() {
        RulerOverrides.forbidden?.let { f -> RulerOverrides.forbidden = { civ, construction -> hook("forbidden", false) { f(civ, construction) } } }
        RulerOverrides.spendingNoted?.let { f -> RulerOverrides.spendingNoted = { civ, gold, what -> hook("spendingNoted", Unit) { f(civ, gold, what) } } }
        RulerOverrides.unitHandler?.let { f -> RulerOverrides.unitHandler = { unit -> hook("unitHandler", false) { f(unit) } } }
        RulerOverrides.hasOrder?.let { f -> RulerOverrides.hasOrder = { unit -> hook("hasOrder", false) { f(unit) } } }
        RulerOverrides.blockFailed?.let { f -> RulerOverrides.blockFailed = { civ, what, why -> hook("blockFailed", Unit) { f(civ, what, why) } } }
        RulerOverrides.unitNotMoved?.let { f -> RulerOverrides.unitNotMoved = { civ, unit, why -> hook("unitNotMoved", Unit) { f(civ, unit, why) } } }
        RulerOverrides.rulersFirst?.let { f -> RulerOverrides.rulersFirst = { civ -> hook("rulersFirst", Unit) { f(civ) } } }
        RulerOverrides.worldVoteOf?.let { f -> RulerOverrides.worldVoteOf = { civ -> hook<String?>("worldVoteOf", null) { f(civ) } } }
        RulerOverrides.promised?.let { f -> RulerOverrides.promised = { civ -> hook("promised", emptyMap()) { f(civ) } } }
        RulerOverrides.demandMade?.let { f -> RulerOverrides.demandMade = { civ, other, demand -> hook("demandMade", Unit) { f(civ, other, demand) } } }
        RulerOverrides.underOrders?.let { f -> RulerOverrides.underOrders = { unit -> hook("underOrders", false) { f(unit) } } }
        RulerOverrides.tradeAnswered?.let { f -> RulerOverrides.tradeAnswered = { request, receiver, outcome -> hook("tradeAnswered", Unit) { f(request, receiver, outcome) } } }
        RulerOverrides.nearWinner?.let { f -> RulerOverrides.nearWinner = { other -> hook("nearWinner", false) { f(other) } } }
        RulerOverrides.unitDisbanded?.let { f -> RulerOverrides.unitDisbanded = { civ, unit, why -> hook("unitDisbanded", Unit) { f(civ, unit, why) } } }
        RulerOverrides.struck?.let { f -> RulerOverrides.struck = { strike -> hook("struck", Unit) { f(strike) } } }
        RulerOverrides.unitExpended?.let { f -> RulerOverrides.unitExpended = { civ, unit, why -> hook("unitExpended", Unit) { f(civ, unit, why) } } }
        RulerOverrides.faithSpent?.let { f -> RulerOverrides.faithSpent = { civ, faith, what -> hook("faithSpent", Unit) { f(civ, faith, what) } } }
        RulerOverrides.freeTechTaken?.let { f -> RulerOverrides.freeTechTaken = { civ, tech, goal -> hook("freeTechTaken", Unit) { f(civ, tech, goal) } } }
        RulerOverrides.unitLost?.let { f -> RulerOverrides.unitLost = { loss -> hook("unitLost", Unit) { f(loss) } } }
        RulerOverrides.warDeclared?.let { f -> RulerOverrides.warDeclared = { war -> hook("warDeclared", Unit) { f(war) } } }
        val refill = units.researchRefill; units.researchRefill = { civ -> hook("researchRefill", Unit) { refill(civ) } }
        val missed = units.garrisonMissed; units.garrisonMissed = { civ, tile -> hook("garrisonMissed", Unit) { missed(civ, tile) } }
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
            "GET /log" -> chanceryLog(civ(), query["sinceYear"]?.toIntOrNull()?.let { turnOfYear(it) } ?: since, query["until"]?.toIntOrNull() ?: Int.MAX_VALUE).let { lines ->
                if (lines.size <= 150) lines else {
                    val shown = lines.takeLast(150)
                    val firstShown = shown.first()["turn"] as Int
                    listOf(mapOf("turn" to firstShown, "text" to "(${lines.size - 150} earlier lines omitted. To read back, ask again with until ${firstShown - 1}" +
                        (if (lines.size - 150 > 150) " - and the same sinceTurn - and so on, a page at a time" else "") + ")")) + shown
                } }
            "GET /lookup" -> lookup(civ(), query["name"] ?: throw ApiError(400, "Missing name"))
            "GET /map.png" -> (query["since"]?.toIntOrNull() ?: (game().turns - 10)).coerceAtLeast(0).let { from ->
                val until = query["until"]?.toIntOrNull()
                Png(MapRenderer.render(civ(), query["style"] ?: "atlas", trail = trail, since = from, sinceYear = yearText(from), until = until ?: Int.MAX_VALUE, untilYear = until?.let { yearText(it) } ?: "")) }
            "GET /orders" -> civ().let { orders.getOrPut(it.civName) { StandingOrders() }.toMap(it) }
            "POST /orders" -> setOrders(civ(), json)
            "POST /grant" -> grant(civ(), json)
            "GET /events" -> publicEvents.filter { (it["turn"] as Int) > since }
            // Any people's cities and their lie of the land, for the Chronicler's pictures (not a ruled civ's secret: the world knows where cities stand)
            "GET /cities" -> game().civilizations.firstOrNull { it.civName == query["civ"] }?.cities?.sortedByDescending { it.population.population }?.map { city ->
                mapOf("name" to city.name, "capital" to city.isCapital(), "size" to city.population.population, "land" to lieOfTheLand(city)) } ?: throw ApiError(404, "No people called ${query["civ"]}")
            "GET /age" -> ages.summary(civ(), query["from"]?.toIntOrNull() ?: throw ApiError(400, "Missing ?from=<turn the age began>"))
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
        val noWonders = json.get("noWonders")?.asStringArray()?.toList() ?: listOf()
        // The shape of the world: the engine's own map types, Pangaea (one landmass) unless asked otherwise
        val mapType = json.getString("mapType", com.unciv.logic.map.MapType.pangaea).let { given ->
            com.unciv.logic.map.MapType.allValues.firstOrNull { it.equals(given.trim(), ignoreCase = true) }
                ?: throw ApiError(400, "mapType takes ${com.unciv.logic.map.MapType.allValues.joinToString()}; not '$given'") }
        // The ways this world can be won, by the ruleset's own names: every one but Time unless asked. Left empty, no victory exists at all - and no Apollo Program
        val victories = RulesetCache[GameParameters().baseRuleset]!!.victories.keys
        val victoryTypes = json.get("victoryTypes")?.takeUnless { it.isNull }?.asStringArray()?.map { given -> victories.firstOrNull { it.equals(given.trim(), ignoreCase = true) }
            ?: throw ApiError(400, "victoryTypes takes ${victories.joinToString()}; not '$given'") }?.distinct() ?: victories.filter { it != "Time" }
        val newGame = GameStarter.startNewGame(GameSetupInfo(
            GameParameters().apply {
                speed = json.getString("speed", "Quick")
                difficulty = json.getString("difficulty", "Prince")
                numberOfCityStates = json.getInt("cityStates", 4)
                this.victoryTypes = ArrayList(victoryTypes)
                players = ArrayList<Player>().apply {
                    for (nation in ruled + others) add(Player(nation))
                    add(Player(Constants.spectator, PlayerType.Human))
                }
            },
            MapParameters().apply { mapSize = MapSize(json.getString("mapSize", "Small")); type = mapType }
        ))
        gameInfo = newGame
        UncivGame.Current.gameInfo = newGame
        resetHostState() // clears the wonder bar too, so it is set after
        wonderless.clear(); wonderless += noWonders
        applyWonderless()
        trail.record(newGame)
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
        writeAtomically(File(dataDir, "save.unciv"), saveData)
        // Also into the dev GUI's save list (working dir is android/assets), so `gradlew desktop:run` can watch the world as spectator
        File("SaveFiles").mkdirs()
        File("SaveFiles/Ruler game").writeText(saveData)
        writeAtomically(File(dataDir, "host.json"), Json.write(mapOf(
            "orders" to orders.mapValues { it.value.toMap() },
            "noWonders" to wonderless.toList(),
            "publicEvents" to publicEvents,
            "chancery" to chancery,
            "unitOrders" to units.toMap(),
            "unitState" to units.stateToMap(),
            "worldVoteState" to mapOf("tallied" to voteTallied, "reminded" to voteReminded, "unHolder" to unHolder),
            "diplomacy" to diplomacy.toMap(),
            "warsDeclared" to warsDeclared.map { it.toString() },
            "trail" to trail.toMap(),
            "ages" to ages.toMap(),
            "lockedCities" to RulerOverrides.lockedCities.toList(),
            "focusedCities" to RulerOverrides.focusedCities.toList()
        )))
    }

    /** A copy of the world after every stretch of turns, so a test game can be wound back (the last dozen are kept) */
    private fun backup() {
        val folder = File(dataDir, "backups/turn-${game().turns.toString().padStart(4, '0')}").apply { mkdirs() }
        for (name in listOf("save.unciv", "host.json", "orchestrator.json")) File(dataDir, name).takeIf { it.exists() }?.copyTo(File(folder, name), overwrite = true)
        File(dataDir, "backups").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.dropLast(12)?.forEach { it.deleteRecursively() }
    }

    /** For test games only: gold from outside the world, said plainly in the log so the advisor knows where it came from */
    private fun grant(civ: Civilization, json: JsonValue): Any? {
        val gold = json.getInt("gold", 0)
        if (gold <= 0) throw ApiError(400, "gold must be positive")
        civ.addGold(gold)
        log(civ, "The treasury received $gold gold from outside the world - a grant by the keepers of this test game, not the fruit of any deal. It is ours to spend.")
        save()
        return mapOf("gold" to civ.gold)
    }

    /** Nations that may raise no world wonder in this game (a setup choice) */
    private val wonderless = HashSet<String>()
    private fun applyWonderless() {
        RulerOverrides.noWonders.clear()
        for (civ in game().civilizations) if (civ.civName in wonderless) RulerOverrides.noWonders += civ.civID
    }

    /** Everything the host remembers about a game, forgotten in one place - so a new world starts clean whatever the last one left behind */
    private fun resetHostState() {
        orders.clear(); publicEvents.clear(); chancery.clear(); spending.clear(); warningsWoken.clear(); losses.clear(); warsDeclared.clear(); notificationsSeen.clear(); shipRaids.clear()
        units.reset(); diplomacy.reset(); trail.clear(); ages.clear()
        worldAtLastAdvance = null
        RulerOverrides.lockedCities.clear(); RulerOverrides.focusedCities.clear(); RulerOverrides.postures.clear(); RulerOverrides.goldReserve.clear(); RulerOverrides.faithReserve.clear(); RulerOverrides.noProposals.clear(); RulerOverrides.noGifts.clear(); RulerOverrides.rearming.clear(); RulerOverrides.noWonders.clear()
    }

    /** Written to a sibling file and moved into place, so a crash mid-write never leaves a half file behind */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
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
        wonderless.clear(); host.get("noWonders")?.asStringArray()?.let { wonderless += it }
        applyWonderless()
        for (event in host.get("publicEvents"))
            publicEvents += mapOf("turn" to event.getInt("turn"), "year" to event.getString("year", ""), "text" to event.getString("text"))
        host.get("chancery")?.forEach { civLog ->
            chancery[civLog.name] = ArrayList(civLog.map { mapOf("turn" to it.getInt("turn"), "text" to it.getString("text")) })
        }
        units.load(host.get("unitOrders"))
        units.loadState(host.get("unitState")) // older files have none: the clocks start afresh
        voteReminded.clear()
        host.get("worldVoteState")?.let { saved ->
            voteTallied = saved.getInt("tallied", -1); unHolder = saved.getString("unHolder", null)
            saved.get("reminded")?.forEach { voteReminded[it.name] = it.asInt() } }
            // A save from before the vote was the ruler's: whoever holds the United Nations now is no news
            ?: run { voteTallied = -1; unHolder = loaded.civilizations.firstOrNull { it.isMajorCiv() }?.victoryManager?.getUNBuildingAndOwnerNames()?.second?.let { loaded.getCivilization(it).civName } }
        diplomacy.load(host.get("diplomacy"))
        warsDeclared.clear(); host.get("warsDeclared")?.asStringArray()?.forEach { warsDeclared += WarRecord.parse(it) }
        units.dropMistakenHolds(loaded, chancery.values)
        // A war declared by order before a restart is still news to the next advance: the round's start is the world before it, not the loaded world
        worldAtLastAdvance = WorldSnapshot(loaded).also { start -> start.wars.removeAll { war -> war.toList().let { (first, second) -> warsDeclared.any { it.covers(first, second) } } } }
        trail.load(host.get("trail"))
        ages.load(host.get("ages"))
        trail.record(loaded) // a save from before the trail was kept starts one here
        for ((name, standing) in orders) game().civilizations.firstOrNull { it.civName == name }?.let { RulerOverrides.postures[it.civID] = RulerOverrides.Posture(standing.warAim, standing.stance, standing.reserve) }
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
            "mapType" to game.tileMap.mapParameters.type,
            "victoryTypes" to game.gameParameters.victoryTypes.toList(),
            "majorCivs" to game.majorCivs().map { mapOf("civ" to it.civName, "alive" to it.isAlive(), "era" to it.getEra().name) },
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
        json.get("neverPropose")?.let { list ->
            val kinds = listOf(Constants.openBorders, Constants.researchAgreement, Constants.defensivePact, "Declaration of Friendship", "luxuries", "calls to war", "demands", "Embassy", "all")
            neverPropose = ArrayList(list.asStringArray().map { given -> kinds.firstOrNull { it.equals(given.trim(), ignoreCase = true) || it.equals(given.trim().removeSuffix("s"), ignoreCase = true) }
                ?: throw ApiError(400, "neverPropose takes ${kinds.joinToString()}; not '$given'") })
        }
        json.get("goldReserve")?.let { goldReserve = if (it.isNull) null else it.asInt().coerceAtLeast(0) }
        json.get("faithReserve")?.let { faithReserve = if (it.isNull) null else it.asInt().coerceAtLeast(0) }
        json.get("noGifts")?.let { noGifts = it.asBoolean() }
        json.get("officialsRearm")?.takeUnless { it.isNull }?.asString()?.trim()?.lowercase()?.let { mode ->
            if (mode !in listOf("always", "war", "never")) throw ApiError(400, "officialsRearm takes always, war or never; not '$mode'")
            officialsRearm = mode
        }
        json.get("garrisonEveryCity")?.takeUnless { it.isNull }?.let { garrisonEveryCity = it.asBoolean() }
        if (json.has("worldVote")) worldVote = json.get("worldVote").takeUnless { it.isNull }?.asString()?.takeUnless { it.startsWith("not pledged") }
        json.get("acknowledged")?.let { acknowledged = ArrayList(it.asStringArray().toList()) } // as saved; the order is "acknowledge", matched in setOrders
        json.get("posture")?.takeUnless { it.isNull }?.let { posture ->
            if (posture.has("warAim")) warAim = posture.get("warAim").takeUnless { it.isNull }?.asString()
            posture.get("stance")?.takeUnless { it.isNull }?.asString()?.let { stance = it }
            posture.get("reserve")?.takeUnless { it.isNull }?.let { reserve = it.asInt().coerceAtLeast(0) }
        }
    }

    private fun applyPersonality(civ: Civilization, standing: StandingOrders) {
        // Rulers decide on war themselves, so by default the automation never declares it
        val personality = Personality().apply { this[PersonalityValue.DeclareWar] = 0f }
        for ((name, value) in standing.personality) personality[PersonalityValue.valueOf(name)] = value
        RulerOverrides.personalities[civ.civID] = personality
        standing.goldReserve?.let { RulerOverrides.goldReserve[civ.civID] = it } ?: RulerOverrides.goldReserve.remove(civ.civID)
        standing.faithReserve?.let { RulerOverrides.faithReserve[civ.civID] = it } ?: RulerOverrides.faithReserve.remove(civ.civID)
        if (standing.noGifts) RulerOverrides.noGifts += civ.civID else RulerOverrides.noGifts -= civ.civID
        RulerOverrides.rearming[civ.civID] = standing.officialsRearm
        if (standing.neverPropose.isNotEmpty()) RulerOverrides.noProposals[civ.civID] = standing.neverPropose.toSet() else RulerOverrides.noProposals.remove(civ.civID)
    }

    /** Which of the ruler's weights most favoured an item the officials queued on their own, as their scoring leans: a building by the stat its
     *  score rests on most (food counted threefold, production and gold twofold, then each stat scaled by its weight, as the automation does),
     *  a soldier by Military, a settler by Expansion. Approximate by design; null unless the weight that bore on it was set above 5 */
    private fun favouredBy(civ: Civilization, city: City, item: String): String? {
        val weights = orders[civ.civName]?.personality ?: return null
        fun ifRaised(value: PersonalityValue) = value.name.takeIf { (weights[value.name] ?: 5f) > 5f }
        val ruleset = civ.gameInfo.ruleset
        ruleset.units[item]?.let { unit ->
            return when { unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity) -> ifRaised(PersonalityValue.Expansion); unit.isMilitary -> ifRaised(PersonalityValue.Military); else -> null }
        }
        val building = ruleset.buildings[item] ?: return null
        if (building.isWonder) {
            // The officials weigh a world wonder by its one great gift (ConstructionAutomation.getOnetimeUniqueBonuses), each gift under one weight; the little culture every
            // wonder carries besides is no favour of yours (the Pentagon read "favoured by your Culture weight", Run 21). A gift under no weight: nothing is said
            val has = { type: com.unciv.models.ruleset.unique.UniqueType -> building.hasUnique(type) }
            return ifRaised(when {
                has(com.unciv.models.ruleset.unique.UniqueType.OneTimeFreePolicy) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeAmountFreePolicies) || has(com.unciv.models.ruleset.unique.UniqueType.StatPercentBonusCities) -> PersonalityValue.Culture
                has(com.unciv.models.ruleset.unique.UniqueType.OneTimeFreeTech) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeAmountFreeTechs) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeFreeGreatPerson) -> PersonalityValue.Science
                has(com.unciv.models.ruleset.unique.UniqueType.OneTimeAmountFreeUnits) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeFreeUnit) -> PersonalityValue.Production
                has(com.unciv.models.ruleset.unique.UniqueType.OneTimeEnterGoldenAge) || has(com.unciv.models.ruleset.unique.UniqueType.GoldenAgeLength) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeEnterGoldenAgeTurns) -> PersonalityValue.Expansion
                has(com.unciv.models.ruleset.unique.UniqueType.OneTimeGainPopulation) || has(com.unciv.models.ruleset.unique.UniqueType.OneTimeGainPopulationRandomCity) -> PersonalityValue.Food
                has(com.unciv.models.ruleset.unique.UniqueType.StatPercentFromTradeRoutes) -> PersonalityValue.Gold
                has(com.unciv.models.ruleset.unique.UniqueType.EnemyUnitsSpendExtraMovement) -> PersonalityValue.DeclareWar
                has(com.unciv.models.ruleset.unique.UniqueType.Strength) || has(com.unciv.models.ruleset.unique.UniqueType.UnitStartingExperience) || has(com.unciv.models.ruleset.unique.UniqueType.EnablesNuclearWeapons) -> PersonalityValue.Military
                else -> return null
            })
        }
        val stats = building.getStats(city)
        val lean = Stat.entries.map { stat -> stat to stats[stat] * (when (stat) { Stat.Food -> 3f; Stat.Production, Stat.Gold -> 2f; else -> 1f }) * (weights[PersonalityValue[stat].name] ?: 5f) / 5f }
            .filter { it.second > 0f }.maxByOrNull { it.second }
        return if (lean != null) ifRaised(PersonalityValue[lean.first])
            else if (building.hasUnique(com.unciv.models.ruleset.unique.UniqueType.UnitStartingExperience)) ifRaised(PersonalityValue.Military) else null
    }

    /** "Railroad" -> Railroads: the names agents reach for are usually one slip away from the real one */
    /** The one thing a slightly wrong name can only mean (Railroad -> Railroads, Settlers -> Settler, Great Library -> The Great Library), or null */
    private fun onlyMatch(name: String, candidates: Collection<String>): String? {
        val wanted = name.lowercase().trim()
        val close = candidates.filter { val c = it.lowercase(); c == wanted || c == "the $wanted" || c == "${wanted}s" || c.removeSuffix("s") == wanted || wanted.removeSuffix("s") == c || c.removePrefix("the ") == wanted || c.removePrefix("the ") == wanted.removeSuffix("s") }
        return close.singleOrNull()
    }

    private fun didYouMean(name: String, candidates: Collection<String>): String {
        fun distance(a: String, b: String): Int {
            val row = IntArray(b.length + 1) { it }
            for (i in 1..a.length) { var previous = row[0]; row[0] = i
                for (j in 1..b.length) { val keep = row[j]; row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1); previous = keep } }
            return row[b.length]
        }
        val wanted = name.lowercase()
        val close = candidates.filter { val c = it.lowercase(); c.contains(wanted) || wanted.contains(c) || distance(c, wanted) <= 2 }.take(3)
        return if (close.isEmpty()) "" else " (did you mean ${close.joinToString(" or ")}?)"
    }

    private fun Civilization.city(name: String): City = cities.firstOrNull { it.name == name } ?: throw ApiError(400, "No city named $name")
    private fun Civilization.construction(name: String): INonPerpetualConstruction =
        (gameInfo.ruleset.buildings[name] ?: gameInfo.ruleset.units[name]) ?: throw ApiError(400, "Unknown building or unit $name" + didYouMean(name, gameInfo.ruleset.buildings.keys + gameInfo.ruleset.units.keys))
    /** Every reason the engine would refuse it - the ones the game hides from its own build menu included, in plain words, since a queue order that is silently dropped is worse than a refusal */
    private fun whyNot(city: City, construction: INonPerpetualConstruction, earlier: List<String> = emptyList()): List<String> {
        val ruleset = city.civ.gameInfo.ruleset
        return construction.getRejectionReasons(city.cityConstructions).mapNotNull { reason -> when (reason.type) {
            // A building it needs may stand ahead of it in the same queue
            com.unciv.models.ruleset.RejectionReasonType.RequiresBuildingInThisCity -> (construction as? com.unciv.models.ruleset.Building)?.requiredBuilding?.let { needed ->
                if (earlier.any { it == needed || ruleset.buildings[it]?.replaces == needed }) null else "it needs a ${city.civ.getEquivalentBuilding(needed).name} in ${city.name} first (queue that ahead of it)" } ?: reason.errorMessage.removeSuffix("!")
            com.unciv.models.ruleset.RejectionReasonType.RequiresTech -> "it needs ${construction.requiredTech ?: "a technology"} first"
            com.unciv.models.ruleset.RejectionReasonType.Obsoleted -> "it is obsolete for us"
            com.unciv.models.ruleset.RejectionReasonType.WaterUnitsInCoastalCities -> "${city.name} is not on the coast"
            com.unciv.models.ruleset.RejectionReasonType.RequiresNearbyResource -> "it needs a resource worked near ${city.name} that is not there"
            com.unciv.models.ruleset.RejectionReasonType.RequiresPolicy, com.unciv.models.ruleset.RejectionReasonType.MorePolicyBranches -> "it needs a policy we have not adopted"
            com.unciv.models.ruleset.RejectionReasonType.UniqueToOtherNation, com.unciv.models.ruleset.RejectionReasonType.CannotBeBuilt -> "our people do not build it"
            com.unciv.models.ruleset.RejectionReasonType.ReplacedByOurUnique -> "our people build their own kind instead" + ((ruleset.units.values.filter { it.replaces == construction.name && it.uniqueTo == city.civ.civName }.map { it.name } + ruleset.buildings.values.filter { it.replaces == construction.name && it.uniqueTo == city.civ.civName }.map { it.name }).firstOrNull()?.let { " ($it)" } ?: "")
            com.unciv.models.ruleset.RejectionReasonType.AlreadyBuilt -> "${city.name} already has it"
            com.unciv.models.ruleset.RejectionReasonType.WonderAlreadyBuilt, com.unciv.models.ruleset.RejectionReasonType.NationalWonderAlreadyBuilt -> "it is already built"
            com.unciv.models.ruleset.RejectionReasonType.MaxNumberBuildable -> "we have as many as may be built"
            com.unciv.models.ruleset.RejectionReasonType.PuppetWonder -> "a puppet city builds no wonders"
            // The engine's word for an "Only available <...>" condition not met: the condition itself is the reason
            com.unciv.models.ruleset.RejectionReasonType.ShouldNotBeDisplayed -> unmetCondition(city, construction)
            // The spaceship's own buildings in a world without the spaceship victory: useless, so nobody builds them (RulerOverrides.servesOnlyAnAbsentVictory)
            com.unciv.models.ruleset.RejectionReasonType.HiddenWithoutVictory -> if (RulerOverrides.servesOnlyAnAbsentVictory(construction, city.civ.gameInfo)) "it serves only the spaceship, and this world has no scientific victory (see waysToWin)" else "it is not part of this world"
            com.unciv.models.ruleset.RejectionReasonType.DisabledBySetting, com.unciv.models.ruleset.RejectionReasonType.WonderDisabledEra -> "it is not part of this world"
            com.unciv.models.ruleset.RejectionReasonType.CanOnlyBeBuiltInSpecificCities, com.unciv.models.ruleset.RejectionReasonType.MustBeOnTile, com.unciv.models.ruleset.RejectionReasonType.MustNotBeOnTile,
            com.unciv.models.ruleset.RejectionReasonType.MustBeNextToTile, com.unciv.models.ruleset.RejectionReasonType.MustNotBeNextToTile, com.unciv.models.ruleset.RejectionReasonType.MustOwnTile -> "${city.name} does not lie where it can be built" + (reason.errorMessage.takeIf { it != reason.type.errorMessage }?.let { " ($it)" } ?: "")
            else -> reason.errorMessage.removeSuffix("!").replace(Regex("\\[([^\\]]*)\\]"), "$1")
        } }.distinct().toList()
    }

    /** Why an "Only available" (or "Can only be built") condition holds an item back, in words: the conditions not met, brackets struck */
    private fun unmetCondition(city: City, construction: INonPerpetualConstruction): String {
        val unmet = (construction as com.unciv.models.ruleset.unique.IHasUniques).let { item ->
            (item.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.OnlyAvailable, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals) +
                item.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanOnlyBeBuiltWhen, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals))
                .flatMap { unique -> unique.modifiers.filter { !com.unciv.models.ruleset.unique.Conditionals.conditionalApplies(unique, it, city.state) } }
                .map { it.text.replace(Regex("\\[([^\\]]*)\\]"), "$1") }.distinct().toList() }
        return if (unmet.isNotEmpty()) "it can be built only ${unmet.joinToString(" and ")}" else "it is not to be had here now"
    }

    /** Why a city cannot turn its production into this yield: the world's rules may allow it never (G&K has only Gold and Science), or only with learning still to come */
    private fun conversionProblem(city: City, name: String): String {
        val ruleset = city.civ.gameInfo.ruleset
        val enabling = ruleset.allRulesetObjects().filter { source -> source.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.EnablesStatProduction, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals).any { it.params[0] == name } }.toList()
        val yields = ruleset.allUniques().filter { it.type == com.unciv.models.ruleset.unique.UniqueType.EnablesStatProduction }.map { it.params[0] }.distinct().toList()
        if (enabling.isEmpty() || (name == "Faith" && !city.civ.gameInfo.isReligionEnabled()))
            return "this world's cities cannot turn production into $name" + (if (yields.isNotEmpty()) " - only into ${yields.joinToString(" or ")}" else "")
        return "${city.name} cannot turn production into $name yet" + (enabling.filterIsInstance<com.unciv.models.ruleset.tech.Technology>().firstOrNull()?.let { " (it needs ${it.name})" } ?: "")
    }

    /** Every field an order may carry: an order with none of them is no order (a model that wrapped its fields in "arguments" sent nothing) */
    private val ORDER_FIELDS = listOf("personality", "research", "policyBranches", "neverBuild", "neverPropose", "goldReserve", "faithReserve", "noGifts", "officialsRearm", "garrisonEveryCity", "posture",
        "queue", "build", "purchase", "rearm", "cities", "units", "trade", "renew", "answerOffers", "answerDemands", "peace", "friendship", "gift", "declareWar", "cityFocus", "acknowledge", "worldVote", "marry", "denounce", "protect", "endProtection")

    private fun setOrders(civ: Civilization, json: JsonValue): Any? {
        val given = json.map { it.name }
        val unknown = given.filter { it !in ORDER_FIELDS }
        if (given.none { it in ORDER_FIELDS }) throw ApiError(400, "Nothing in this order is an order" + (if (unknown.isNotEmpty()) " (${unknown.joinToString()} is not a field)" else "") + "; the fields are ${ORDER_FIELDS.joinToString()}. Nothing was changed.")
        if (unknown.isNotEmpty()) throw ApiError(400, "${unknown.joinToString()} ${if (unknown.size == 1) "is not a field" else "are not fields"} of an order; the fields are ${ORDER_FIELDS.joinToString()}. Nothing was changed - send the order again with the fields themselves.")
        val ruleset = civ.gameInfo.ruleset
        // The pledge of our vote: a great power we know and that stands - never ourselves, which the game forbids
        json.get("worldVote")?.takeUnless { it.isNull }?.asString()?.trim()?.let { name ->
            if (name.equals(civ.civName, ignoreCase = true)) throw ApiError(400, "worldVote cannot be ourselves: no power may vote for itself. Pledge it to another great power, or null to abstain")
            val power = civ.getKnownCivs().firstOrNull { it.isMajorCiv() && it.isAlive() && it.civName.equals(name, ignoreCase = true) }
                ?: throw ApiError(400, "worldVote names a great power we know and that stands: ${civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.joinToString { it.civName }}; not '$name'" +
                    (if (civ.getKnownCivs().any { it.isCityState && it.civName.equals(name, ignoreCase = true) }) " - a city-state cannot be voted for" else ""))
            json.remove("worldVote"); json.addChild("worldVote", JsonValue(power.civName))
        }
        val standing = orders.getOrPut(civ.civName) { StandingOrders() }
        val alreadyKnown = ArrayList<String>()
        val rejected = ArrayList<String>()
        val taken = ArrayList<String>()
        json.get("research")?.let { goals ->
            val ahead = ruleset.technologies.keys.filter { !reached(civ, it) }
            val kept = goals.asStringArray().map { goal -> if (goal in ruleset.technologies) goal else onlyMatch(goal, ruleset.technologies.keys)?.also { taken += "$goal -> $it" }
                // A building, wonder or unit named as a goal means the technology that unlocks it
                ?: (ruleset.buildings[goal] ?: ruleset.units[goal])?.requiredTech?.also { taken += "$goal -> $it (the technology it needs)" } ?: goal }.filter { goal ->
                if (goal !in ruleset.technologies) rejected += "No technology is called '$goal'" + (if (goal in ruleset.policies) " - that is a policy; put it in policyBranches" else didYouMean(goal, ahead).ifEmpty { " in this world; get_options.futureTechs lists every technology still ahead of us" }) + ". The other research goals were kept."
                if (goal in ruleset.technologies && reached(civ, goal)) alreadyKnown += goal
                goal in ruleset.technologies
            }
            goals.remove(); json.addChild("research", JsonValue(JsonValue.ValueType.array).also { array -> kept.forEach { array.addChild(JsonValue(it)) } })
        }
        json.get("policyBranches")?.let { wishes ->
            val kept = wishes.asStringArray().filter { wish ->
                val known = wish in ruleset.policyBranches || wish in ruleset.policies
                if (!known) rejected += "No policy or policy branch is called '$wish'; branches: ${ruleset.policyBranches.keys.joinToString()}. The others were kept."
                // One already taken is said, not skipped in silence (Carthage counted on an Aesthetics it had long adopted)
                val done = ruleset.policyBranches[wish]?.let { branch -> branch.policies.all { civ.policies.isAdopted(it.name) } } ?: (wish in ruleset.policies && civ.policies.isAdopted(wish))
                if (known && done) rejected += (if (wish in ruleset.policyBranches) "Every policy of $wish is already adopted" else "$wish is already adopted") + "; it was dropped from the policy order."
                known && !done
            }.let { list ->
                // Branches that shut each other out (Piety and Rationalism): the later one in the order can never come, so it is dropped with a word
                val excluded = ArrayList<String>()
                list.filter { wish ->
                    val branch = ruleset.policyBranches[wish] ?: ruleset.policies[wish]?.branch ?: return@filter true
                    val shutBy = branch.uniques.mapNotNull { Regex("Only available <before adopting \\[(.+?)\\]>").find(it)?.groupValues?.get(1) }
                    val clash = list.takeWhile { it != wish }.firstOrNull { earlier -> (ruleset.policyBranches[earlier]?.name ?: ruleset.policies[earlier]?.branch?.name) in shutBy }
                    if (clash != null) { excluded += wish; rejected += "$wish cannot be adopted after $clash - the two shut each other out; $wish is dropped from the policy order (put it first if you want it instead)." }
                    clash == null
                }
            }
            wishes.remove(); json.addChild("policyBranches", JsonValue(JsonValue.ValueType.array).also { array -> kept.forEach { array.addChild(JsonValue(it)) } })
        }
        json.get("posture")?.takeUnless { it.isNull }?.let { posture ->
            posture.get("stance")?.takeUnless { it.isNull }?.asString()?.let { if (it !in listOf("attack", "defend", "raid")) rejected += "stance must be attack, defend or raid, not '$it'; the posture was not changed".also { posture.remove() } }
            posture.get("warAim")?.takeUnless { it.isNull }?.asString()?.let { name ->
                val target = civ.gameInfo.getCities().firstOrNull { it.name == name }
                val declaringOn = json.get("declareWar")?.asStringArray()?.toList() ?: emptyList()
                if (target == null || !(civ.isAtWarWith(target.civ) || target.civ.civName in declaringOn)) { rejected += "warAim must be a city of a power we are at war with; '$name' is not. The posture was not changed"; posture.remove() }
            }
        }
        json.get("queue")?.forEach { order -> order.get("items")?.let { items ->
            val fixed = items.asStringArray().map { item -> if (item in ruleset.buildings || item in ruleset.units || com.unciv.models.ruleset.PerpetualConstruction.isNamePerpetual(item)) item else onlyMatch(item, ruleset.buildings.keys + ruleset.units.keys)?.also { taken += "$item -> $it" } ?: item }
            items.remove(); order.addChild("items", JsonValue(JsonValue.ValueType.array).also { array -> fixed.forEach { array.addChild(JsonValue(it)) } })
        } }
        if (taken.isNotEmpty()) rejected += "Taken as meant: ${taken.joinToString()}."
        standing.readFrom(json)
        // An item put on the never-build list goes from every queue now, not after the turn (a Military Academy ordered off at T240 was finished at T241, Run 20)
        val struckNow = if (!json.has("neverBuild")) emptyList() else civ.cities.flatMap { city ->
            // The officials' own choices only: a queue the ruler set (a locked city) keeps what the ruler put in it, as the order's description says
            (if (city.id in RulerOverrides.lockedCities) emptyList() else city.cityConstructions.constructionQueue.toList()).filter { name -> (ruleset.buildings[name] ?: ruleset.units[name])?.let { isForbidden(civ, it) } == true }.map { name ->
                city.cityConstructions.constructionQueue.remove(name)
                if (city.cityConstructions.constructionQueue.isEmpty()) city.cityConstructions.chooseNextConstruction()
                "$name in ${city.name}" } }
        if (struckNow.isNotEmpty()) log(civ, "By your order: ${struckNow.joinToString()} struck from the queues, as never to be built.")
        applyPersonality(civ, standing)
        RulerOverrides.postures[civ.civID] = RulerOverrides.Posture(standing.warAim, standing.stance, standing.reserve)
        if (json.has("posture")) log(civ, "By your order: the host's posture is now ${standing.stance}" + (standing.warAim?.let { ", its aim $it" } ?: "") + (if (standing.reserve > 0) ", ${standing.reserve} soldiers kept home" else "") + ".")
        standing.warAim?.let { aim -> civ.gameInfo.getCities().firstOrNull { it.name == aim }?.let { target ->
            val centre = target.getCenterTile()
            val gate = centre.neighbors.filter { it.isLand && !it.isImpassible() }.toList()
            val held = gate.filter { it.militaryUnit != null && it.militaryUnit!!.civ != civ }
            if (gate.isNotEmpty() && held.size == gate.size) rejected += "Note: every land approach to $aim is held by enemy soldiers (${held.joinToString { it.militaryUnit!!.name }}), the rest is mountain or sea. The host will gather within range and fight for the approaches; engines can bombard from two tiles off. Expect it to be slow."
        } }

        val done = ArrayList<String>()
        if (struckNow.isNotEmpty()) done += "Struck from the queues now, as never to be built: ${struckNow.joinToString()}"
        val failed = ArrayList<String>(rejected)
        /** Each item stands alone: a bad one is reported under "failed" and the rest still go through */
        fun tryEach(list: JsonValue?, action: (JsonValue) -> String) {
            list?.forEach { entry ->
                try { done += action(entry) }
                catch (ex: IllegalArgumentException) { failed += ex.message ?: "failed" }
                catch (ex: ApiError) { failed += ex.message ?: "failed" }
                // Anything else the engine throws costs that one item, said under failed, never the rest of the order (audit 2026-10-03)
                catch (ex: Exception) { ex.printStackTrace(); failed += "This part of the order could not be carried out (${ex.javaClass.simpleName}: ${ex.message}); the rest went on" }
            }
        }
        // Whatever happens below, the standing orders are applied and the realm saved: an order half carried out is still on disk as it stands
        try {

        // Replace a city's whole build queue; the officials then leave it alone until it runs out
        tryEach(json.get("queue")) { order ->
            val city = civ.city(order.getString("city"))
            val items = order.get("items").asStringArray().toList()
            // "Science", "Gold" and the like turn production into that yield for good - so only ever as the closing item
            val closing = com.unciv.models.ruleset.PerpetualConstruction.perpetualConstructionsMap[items.lastOrNull() ?: ""]
            val constructions = (if (closing != null) items.dropLast(1) else items).map { civ.construction(it) }
            // Each item is judged with the items ahead of it in this same queue counted as built (a Temple behind its Shrine is fine);
            // one that cannot be built is left out, the rest is queued as ordered - never the whole queue dropped for one item
            val leftOut = ArrayList<String>(); val good = ArrayList<INonPerpetualConstruction>()
            // A yield this city cannot turn production into is left out like any other item
            val perpetual = closing?.takeIf { it.isBuildable(city.cityConstructions) }
            if (closing != null && perpetual == null) leftOut += "${closing.name} (${conversionProblem(city, closing.name)})"
            for ((index, construction) in constructions.withIndex()) {
                val reasons = whyNot(city, construction, items.take(index))
                if (reasons.isNotEmpty()) leftOut += "${items[index]} (${reasons.joinToString()})" else good += construction
            }
            if (good.isEmpty() && perpetual == null) throw ApiError(400, "${city.name} cannot build ${leftOut.joinToString("; ")}; its queue is unchanged")
            city.cityConstructions.constructionQueue.clear()
            for (construction in good) city.cityConstructions.addToQueue(construction)
            if (perpetual != null) city.cityConstructions.addToQueue(perpetual)
            city.cityConstructions.currentConstructionIsUserSet = true
            RulerOverrides.lockedCities += city.id
            val now = "${city.name} queue is now: ${city.cityConstructions.constructionQueue.joinToString()}"
            log(civ, "By your order: ${city.name}'s queue is now ${city.cityConstructions.constructionQueue.joinToString()}.")
            if (leftOut.isNotEmpty()) throw ApiError(400, "${city.name} cannot build ${leftOut.joinToString("; ")} - left out; the rest was queued as ordered. $now")
            now
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
            log(civ, "By your order: ${city.name} builds $item first; its queue is now ${city.cityConstructions.constructionQueue.joinToString()}.")
            "${city.name} queue is now: ${city.cityConstructions.constructionQueue.joinToString()}"
        }
        // What becomes of a city: annex a puppet, raze it, or stop a razing
        tryEach(json.get("cities")) { order ->
            val city = civ.city(order.getString("city"))
            when (val action = order.getString("action")) {
                "annex" -> { if (!city.isPuppet) throw ApiError(400, "${city.name} is not a puppet"); city.annexCity(); log(civ, "By your order: ${city.name} is annexed and governed as our own."); "${city.name} annexed" }
                "raze" -> { if (city.isCapital()) throw ApiError(400, "A capital cannot be razed"); city.isBeingRazed = true; log(civ, "By your order: ${city.name} is being razed; it shrinks each turn until nothing is left."); "${city.name} is being razed" }
                "spare" -> { if (!city.isBeingRazed) throw ApiError(400, "${city.name} is not being razed"); city.isBeingRazed = false; log(civ, "By your order: the razing of ${city.name} is stopped."); "${city.name} spared" }
                else -> throw ApiError(400, "Unknown city action $action; use annex, raze or spare")
            }
        }
        tryEach(json.get("marry")) { entry -> diplomacy.marry(civ, entry.asString(), policyCostPerCity(civ)) }
        tryEach(json.get("denounce")) { entry -> diplomacy.denounce(civ, entry.asString()) }
        tryEach(json.get("protect")) { entry -> diplomacy.protect(civ, entry.asString(), true) }
        tryEach(json.get("endProtection")) { entry -> diplomacy.protect(civ, entry.asString(), false) }
        // Re-arming by hand: named units, or "all" - each told apart, done or not and why
        json.get("rearm")?.let { list ->
            val entries = list.asStringArray().map { it.trim() }
            entries.filter { !it.equals("all", ignoreCase = true) && it.removePrefix("#").toIntOrNull() == null }.forEach { failed += "rearm takes unit numbers or \"all\"; not '$it'" }
            val wanted = entries.flatMap { entry -> if (entry.equals("all", ignoreCase = true)) units.rearmable(civ).map { it.id } else listOfNotNull(entry.removePrefix("#").toIntOrNull()) }.distinct()
            for (id in wanted) {
                val result = units.rearm(civ, id, standing.goldReserve ?: 0)
                if (result.first) done += result.second else failed += result.second
            }
            if (wanted.isEmpty() && entries.any { it.equals("all", ignoreCase = true) }) done += "No soldier of ours can be re-armed with newer arms we know."
        }
        // Unit orders, diplomacy
        tryEach(json.get("units")) { units.give(civ, it) }
        // Buy outright with gold - after the unit orders: a disbanding is done at once, and a soldier bought in the same breath then lands inside the city it emptied, not beside it (Persia's Infantry, Run 23)
        tryEach(json.get("purchase")) { order ->
            val city = civ.city(order.getString("city"))
            val item = order.getString("item")
            val construction = civ.construction(item)
            when (val with = order.getString("with", "gold").trim().lowercase()) {
                "faith" -> return@tryEach buyWithFaith(civ, city, construction)
                "gold" -> {}
                else -> throw ApiError(400, "purchase takes with: gold or faith; not '$with'")
            }
            val reasons = whyNot(city, construction)
            val cost = construction.getStatBuyCost(city, Stat.Gold)
            val problem = when {
                reasons.isNotEmpty() -> reasons.joinToString()
                !construction.canBePurchasedWithStat(city, Stat.Gold) || cost == null -> "$item cannot be bought with gold"
                civ.gold < cost -> "$item costs $cost gold, treasury has ${civ.gold}"
                else -> null
            }
            if (problem != null) throw ApiError(400, "Could not buy $item in ${city.name}: $problem")
            val queueBefore = city.cityConstructions.constructionQueue.toList()
            if (!city.cityConstructions.purchaseConstruction(construction, -1, false)) throw ApiError(400, "Could not buy $item in ${city.name}: no room to place it")
            val refill = refilled(city, queueBefore, item)
            val centre = city.getCenterTile()
            val bought = civ.units.getCivUnits().filter { it.name == item && it.getTile().aerialDistanceTo(centre) <= 1 }.maxByOrNull { it.id }
            // An aircraft or a missile waits inside the city, whoever holds its walls, and a ship in its waters; only a soldier can be left standing beside them
            val holder = centre.militaryUnit?.takeIf { bought != null && bought.isMilitary() && bought.baseUnit.isLandUnit && it != bought } // a ship is no garrison
            val note = (if (holder != null && bought != null) " ${item} #${bought.id} stands beside ${city.name}, which our ${holder.name} #${holder.id} still holds: order it to garrison ${city.name} to swap them, or the officials use it as they see fit." else "") +
                (if (bought != null) " It acts on our next turn, after the powers ahead of us in the turn order have moved." else "")
            log(civ, "By your order: bought $item in ${city.name} for $cost gold.$note$refill")
            "Bought $item in ${city.name} for $cost gold (${civ.gold} left).$note$refill"
        }
        // Answers before proposals: a counter is a refusal and a new offer in one order, and the new offer is refused as a mirror while the old one still waits
        tryEach(json.get("answerOffers")) { diplomacy.answerOffer(civ, it.getString("power"), it.getString("answer"), it.getString("terms", null)) }
        tryEach(json.get("answerDemands")) { entry ->
            val (told, warWith) = diplomacy.answerDemand(civ, entry.getString("power"), entry.getString("answer"))
            if (warWith == null) told else {
                // The refusal was a declaration: the war is ours, as if declared by order (the engine's declaration wrote the world's event and the council's record)
                "$told - that refusal declared war on ${warWith.civName}. No peace can be made for ${civ.gameInfo.ruleset.modOptions.constants.minimumWarDuration} turns"
            }
        }
        tryEach(json.get("trade")) { diplomacy.proposeTrade(civ, it) }
        tryEach(json.get("renew")) { diplomacy.orderRenewal(civ, it.getString("power"), it.getString("terms", null)) }
        // A warning weighed and let stand: matched by its key (its first clause, counts and lists struck out) or by the start of its text, counts struck out
        // but a unit's #number kept whole - so "Great Prophet #378" is that prophet and no other
        tryEach(json.get("acknowledge")) { entry ->
            val given = entry.asString().trim().removeSuffix(".")
            if (given.isEmpty()) throw ApiError(400, "acknowledge takes a warning as the briefing shows it")
            val standingNow = warnings(civ)
            val matched = standingNow.filter { ackKey(it) == ackKey(given) }.ifEmpty { standingNow.filter { countsStruck(it).startsWith(countsStruck(given), ignoreCase = true) } }.groupBy { ackKey(it) }
            if (matched.isEmpty()) throw ApiError(400, "No warning standing now reads '${entry.asString()}'" + (if (standingNow.isEmpty()) "; there are none" else "; warnings now: ${standingNow.joinToString(" | ") { firstClause(it) }}"))
            if (matched.size > 1) throw ApiError(400, "'${entry.asString()}' could be any of ${matched.size} warnings; give more of the one you mean: ${matched.values.joinToString(" | ") { firstClause(it.first()) }}")
            val (key, warning) = matched.entries.single().let { it.key to it.value.first() }
            if (key !in standing.acknowledged) standing.acknowledged += key
            "Acknowledged: '${firstClause(warning)}' now stands under warningsAcknowledged while it lasts - its figures and the units it names may change; if it clears and returns, it shows in full again"
        }
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
            // Declaring war ends every defensive pact of ours (the game's rule): named, since Greece lost three in Run 16 without having weighed it
            val pacts = civ.diplomacy.values.filter { it.diplomaticStatus == DiplomaticStatus.DefensivePact }.map { it.otherCivName }
            // The engine clears our deals with the target and with everyone it pulls in, without a word, here between turns where the turn's compare cannot see it:
            // diffed around the declaration and named, the gold already paid for a Research Agreement among them (audit 2026-10-03)
            val dealsBefore = dealsOf(civ); val warsBefore = warsDeclared.size
            diplomacy.declareWar() // the engine's declaration writes the world's event and the council's record, and calls in the target's pact partners and allies
            val minimum = civ.gameInfo.ruleset.modOptions.constants.minimumWarDuration
            val ended = if (pacts.isEmpty()) "" else " Declaring it ended our defensive pact${if (pacts.size == 1) "" else "s"} with ${pacts.joinToString(" and ")}."
            val swept = RulerHost.diplomacy.lostToWar(civ, dealsBefore)?.let { " It swept away our deals: $it." } ?: ""
            val company = cameIn(civ, target, warsDeclared.drop(warsBefore))
            log(civ, "By your order: we declared war on $targetName. No peace can be made for $minimum turns.$ended$swept$company")
            "Declared war on $targetName. No peace can be made for $minimum turns - neither side may offer it before then.$ended$swept$company"
        }
        if (alreadyKnown.isNotEmpty()) failed += "Already known, dropped from the research goals: ${alreadyKnown.joinToString()}"
        standing.research.removeAll { reached(civ, it) }
        // City focus: which yields the citizens work for
        tryEach(json.get("cityFocus")) { order ->
            val city = civ.city(order.getString("city"))
            // A puppet works for gold, whatever is set: the engine resets it every turn (CityTurnManager) - refused rather than undone in silence
            if (city.isPuppet) throw ApiError(400, "${city.name} is a puppet and works its tiles for gold until it is annexed (cities: annex)")
            val focus = com.unciv.logic.city.CityFocus.entries.firstOrNull { it.name.equals(order.getString("focus"), ignoreCase = true) || it.name.equals(order.getString("focus") + "Focus", ignoreCase = true) }
                ?: throw ApiError(400, "Unknown focus; use ${com.unciv.logic.city.CityFocus.entries.joinToString { it.name }}")
            city.setCityFocus(focus)
            if (focus == com.unciv.logic.city.CityFocus.NoFocus) RulerOverrides.focusedCities -= city.id else RulerOverrides.focusedCities += city.id
            city.reassignAllPopulation()
            "${city.name} now works its tiles for ${focus.name}"
        }
        } finally {
            applyStandingOrders(civ)
            save()
        }
        return mapOf("orders" to standing.toMap(civ), "done" to done, "failed" to failed,
            "researchQueue" to civ.tech.techsToResearch, "researchNote" to "researchQueue is the scholars' path to the first goal; every goal stands under orders.research and is taken up in turn",
            "gold" to civ.gold)
    }

    /** What faith would buy this in this city, if faith can buy it here at all (the engine's own tests, an "Unbuildable" Missionary included); null if it cannot */
    /** A purchase that leaves a queue empty: the engine has the officials fill it at once (CityConstructions.purchaseConstruction), before any snapshot sees it - so it is said here */
    private fun refilled(city: City, before: List<String>, item: String): String {
        val leftOfOurs = before.filter { it != item }
        val now = city.cityConstructions.constructionQueue
        if (leftOfOurs.isNotEmpty() || now.isEmpty()) return ""
        return " The queue of ${city.name} was left empty, so the officials put ${now.joinToString()} in it."
    }

    private fun faithPrice(city: City, construction: INonPerpetualConstruction): Int? = construction.getStatBuyCost(city, Stat.Faith)
        ?.takeIf { construction.canBePurchasedWithStat(city, Stat.Faith) && construction.isPurchasable(city.cityConstructions) }

    /** The purchase order with faith: the stored faith pays, as the engine's own buy button would */
    private fun buyWithFaith(civ: Civilization, city: City, construction: INonPerpetualConstruction): String {
        val item = construction.name
        // A religious unit takes the faith of the city it is born in (UnitManager): of another faith, the officials would disband it as not ours the next turn
        val ours = civ.religionManager.religion
        if (construction is com.unciv.models.ruleset.unit.BaseUnit && (construction.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion) || construction.hasUnique(com.unciv.models.ruleset.unique.UniqueType.PreventSpreadingReligion))
            && !construction.hasUnique(com.unciv.models.ruleset.unique.UniqueType.TakeReligionOverBirthCity) && city.religion.getMajorityReligion() != ours) {
            val better = civ.cities.filter { it.religion.getMajorityReligion() == ours && ours != null }.map { it.name }
            throw ApiError(400, "Could not buy $item in ${city.name}: it would carry the faith of ${city.religion.getMajorityReligion()?.getReligionDisplayName() ?: "no religion"}, as its people do, not ours - and the officials disband such a one" +
                (if (better.isNotEmpty()) "; buy it in a city that follows our faith: ${better.joinToString()}" else "; no city of ours follows our faith now"))
        }
        val cost = faithPrice(city, construction)
        val stored = civ.religionManager.storedFaith
        if (cost == null) {
            val elsewhere = civ.cities.filter { faithPrice(it, construction) != null }.map { it.name }
            val reasons = whyNot(city, construction).filterNot { it == "our people do not build it" } // "Unbuildable": made by faith alone, which is no bar here
            throw ApiError(400, "Could not buy $item in ${city.name} with faith: " + when {
                reasons.isNotEmpty() -> reasons.joinToString()
                elsewhere.isNotEmpty() -> "faith buys it only in ${elsewhere.joinToString()}"
                else -> "faith cannot buy it in any city of ours now (religion.faithBuys says what faith buys, and where)"
            })
        }
        if (stored < cost) throw ApiError(400, "Could not buy $item in ${city.name}: it costs $cost faith, and we hold $stored")
        val queueBefore = city.cityConstructions.constructionQueue.toList()
        if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Faith, cost) || !city.cityConstructions.purchaseConstruction(construction, -1, false, Stat.Faith))
            throw ApiError(400, "Could not buy $item in ${city.name} with faith: ${if (city.isInResistance()) "the city is in resistance" else if (city.isPuppet) "a puppet city buys nothing" else "no room to place it - a unit of its kind already stands in ${city.name}"}")
        val refill = refilled(city, queueBefore, item)
        log(civ, "By your order: bought $item in ${city.name} for $cost faith.$refill")
        return "Bought $item in ${city.name} for $cost faith (${civ.religionManager.storedFaith} left).$refill"
    }

    /** Called before every turn so the automation finds research and policies already chosen the ruler's way */
    /** A research goal is reached once known - except the repeatable ones (Future Tech), which are never done */
    private fun reached(civ: Civilization, tech: String) =
        civ.tech.isResearched(tech) && civ.gameInfo.ruleset.technologies[tech]?.isContinuallyResearchable() != true

    /** The engine's research queue holds the path to the first research goal still ahead. Called before every turn, and again mid-turn when a technology
     *  lands by a Great Scientist's hand and leaves the queue empty (RulerUnits.researchRefill) */
    private fun applyResearchGoals(civ: Civilization) {
        val standing = orders[civ.civName] ?: return
        val goal = standing.research.firstOrNull { !reached(civ, it) } ?: return
        val path = civ.tech.getRequiredTechsToDestination(civ.gameInfo.ruleset.technologies[goal]!!).map { it.name }
        if (path.isNotEmpty() && civ.tech.techsToResearch != path) {
            civ.tech.techsToResearch.clear()
            civ.tech.techsToResearch.addAll(path)
        }
    }

    private fun applyStandingOrders(civ: Civilization) {
        if (civ.isDefeated()) return // a fallen realm adopts no policy and marches no garrison (the Iroquois adopted Aristocracy the turn they fell, Run 23)
        val standing = orders[civ.civName] ?: return
        val ruleset = civ.gameInfo.ruleset
        applyResearchGoals(civ)

        if (standing.garrisonEveryCity) for (city in civ.cities) {
            val centre = city.getCenterTile()
            val position = centre.position
            val holder = centre.militaryUnit
            if (holder != null && !poorDefender(civ, holder)) continue
            if (units.orders.any { (id, order) -> id != holder?.id && order.action == "garrison" && order.x == position.x.toInt() && order.y == position.y.toInt() }) continue // someone is on the way
            val soldier = civ.units.getCivUnits()
                .filter { it.isMilitary() && it.baseUnit.isLandUnit && it.id !in units.orders && !it.getTile().isCityCenter() && !poorDefender(civ, it) && !units.failedLately(it, centre) && units.canMarch(it, centre) } // same landmass: a spent unit cannot be asked for a path
                .minByOrNull { it.getTile().aerialDistanceTo(centre) } ?: continue
            units.orders[soldier.id] = RulerUnits.Order("garrison", position.x.toInt(), position.y.toInt())
            log(civ, "By your standing order: ${city.name} ${if (holder == null) "stood empty" else "was held only by a ${holder.name}"}, so our ${soldier.name} #${soldier.id} marches in to garrison it.")
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
            hostAdopted.getOrPut(civ.civName) { HashSet() } += policy.name
            log(civ, "Adopted the policy ${policy.name}.")
        }
    }

    //endregion
    //region Turns, chancery log and public events

    /** What the officials paid for during the turn being played, per civ - written into the chancery log once the turn is over */
    private val spending = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Int, String>>>()
    /** Units of each civ destroyed or captured in battle during the turn being played (the engine's word, see RulerOverrides.noteLoss) */
    private val losses = HashMap<String, MutableList<RulerOverrides.UnitLoss>>()
    /** Blows each civ's own host struck during the turn being played, in order (RulerOverrides.noteStrike): the engine tells only the one struck, so the attacker's
     *  chancery had nothing of its own fighting (three realms besieged Tenochtitlan without a line, Run 21). Told as one line per target */
    private val strikes = HashMap<String, MutableList<RulerOverrides.Strike>>()
    /** Blows struck on each ruled civ's cities and units during the turn being played, in order: told as one line per attacker and place, in place of the engine's notice of each blow */
    private val blows = HashMap<String, MutableList<RulerOverrides.Strike>>()
    /** Units of each civ that went by their own work or by the ground they stood on during the turn being played, by unit number: why, and where they stood (RulerOverrides.noteExpended) */
    private val expended = HashMap<String, HashMap<Int, Pair<String, Tile>>>()
    /** A ship that attacked one of our cities or units, as the attack's notice placed it: kept a few turns for the threats list */
    private class ShipRaid(val turn: Int, val owner: String, val target: String, val from: String)
    private val shipRaids = HashMap<String, HashMap<Int, ShipRaid>>()

    /** The notifications each civ's last bookkeeping read: the same objects move from the live list into the log at the turn's end, and are not read twice */
    private val notificationsSeen = HashMap<String, MutableSet<com.unciv.logic.civilization.Notification>>()

    private fun log(civ: Civilization, text: String) {
        chancery.getOrPut(civ.civName) { ArrayList() } += mapOf("turn" to stamp(), "text" to text)
    }

    /** The first turn whose year is at or after the one asked for (years run negative before Christ) */
    private fun turnOfYear(year: Int): Int = (0..game().turns).firstOrNull { game().getYear(it - game().turns) >= year }?.let { it - 1 } ?: game().turns

    private fun chanceryLog(civ: Civilization, since: Int, until: Int = Int.MAX_VALUE) =
        (chancery[civ.civName] ?: emptyList()).filter { (it["turn"] as Int) > since && (it["turn"] as Int) <= until }

    /** Units the officials disbanded for a reason of their own this turn, by realm and unit number: the reason, for the bookkeeping's line */
    private val disbandedFor = HashMap<String, HashMap<Int, String>>()
    private val noise = listOf("has no work to do", "will end in", "will end next turn", "can be promoted", "assigned you a new quest", "no longer needs your help",
        // the engine's bare word on a trade request: our own line says which proposal and how it ended, and the deals line says what came into force
        "has accepted your trade request", "has denied your trade request",
        // a city-state's end: the host's own line (recordPublicEvents) says who took its city, to every court that knew it
        "The City-State of [")
    /** The engine's notice of a blow on us ("An enemy [Pikeman] ([-40] HP) has attacked [Onondaga] ([-26] HP)"): the turn's blows are folded into one line per attacker and place instead */
    private val blowNotice = Regex("^(An enemy|Enemy city) \\[[^\\]]+\\]( \\(\\[-\\d+\\] HP\\))? (has attacked|has captured|has destroyed|has raided|was destroyed while attacking) (?!our tile improvement)")
    /** The engine's notices in the chancery's own voice: the realm speaks as "we", a blow's arithmetic is struck ("(-40 HP)"), its templates' residue
     *  ("Gained 1 Worker unit(s) due to adopting Citizenship") and a city's wish for a luxury in the words of a power's demand ("Ecbatana demands Copper!") are put in words */
    private fun inOurWords(text: String): String {
        var line = text.replace(Regex("\\s*\\(-\\d+ HP\\)"), "").replace(Regex(" {2,}"), " ")
        line = line.replace(Regex("^You "), "We ").replace(Regex("^Your "), "Our ")
        // "you" as the doer ("is grateful that you killed") becomes "we"; as the one done to ("rewarded you") becomes "us"
        line = line.replace(Regex("\\b(that|because|whom|if|when|as) you\\b"), "$1 we").replace(Regex("\\byou\\b"), "us").replace(Regex("\\byour\\b"), "our")
        line = line.replace(Regex("\\b(\\d+) (.+?) unit\\(s\\)")) { m -> "${m.groupValues[1]} ${m.groupValues[2]} unit${if (m.groupValues[1] == "1") "" else "s"}" }
        line = line.replace(Regex(" due to (adopting|constructing|completing|researching|discovering|founding)"), " on $1")
        line = line.replace(Regex("^([^!]+) demands ([^!]+)!$"), "$1 asks for $2.")
        return line
    }

    /** What one ruled civ looked like before a turn, to explain afterwards what the officials did */
    private class CivSnapshot(civ: Civilization) {
        val researching = civ.tech.currentTechnologyName()
        val queues = civ.cities.associate { it.name to it.cityConstructions.constructionQueue.toList() }
        val units = civ.units.getCivUnits().associate { it.id to Pair(it.name, it.getTile()) }
        val gold = civ.gold
        val happiness = civ.getHappiness()
        val deals = RulerHost.dealsOf(civ)
        val turnsLeft = RulerHost.dealTurnsLeft(civ)
        val policies = civ.policies.getAdoptedPolicies().toSet()
    }

    internal fun dealsOf(civ: Civilization) = diplomacy.dealsInForce(civ)
    internal fun dealTurnsLeft(civ: Civilization) = diplomacy.dealTurnsLeft(civ)

    /** Faith the officials spent this turn, by realm: (faith, "what in where") - told with the turn's bookkeeping */
    private val faithSpending = HashMap<String, ArrayList<Pair<Int, String>>>()
    /** Free technologies taken this turn, by realm: (technology, the goal it was taken toward, or null for the officials' own pick) */
    private val freeTechs = HashMap<String, ArrayList<Pair<String, String?>>>()
    /** Policies the host adopted by the ruler's policy order, by realm: the rest of what was adopted is the officials' */
    private val hostAdopted = HashMap<String, HashSet<String>>()

    private fun recordChancery(civ: Civilization, before: CivSnapshot) {
        // A policy adopted that the ruler's policy order did not name was the officials' choice; it is told (audit 2026-10-03: Representation taken unseen)
        val adoptedNow = civ.policies.getAdoptedPolicies().filterNot { com.unciv.models.ruleset.Policy.isBranchCompleteByName(it) }
        val theirs = adoptedNow.filter { it !in before.policies && it !in (hostAdopted.remove(civ.civName) ?: emptySet()) }
        if (theirs.isNotEmpty()) log(civ, "The officials adopted the polic${if (theirs.size == 1) "y" else "ies"} ${theirs.joinToString(" and ")}" +
            (if (orders[civ.civName]?.policyBranches.isNullOrEmpty()) " - your policy order names no branch" else " - nothing your policy order names could be adopted") + ".")
        val turn = civ.gameInfo.turns
        val enemies = RulerCampaigns.enemiesOf(civ)
        units.noteStanding(civ)
        // The game's own notifications first - they cover research, buildings, combat, diplomacy. The turn's entry in the log holds what was
        // on the desk when our turn ended; what later powers did on their turns is still in the live list. Both are read now, in one window,
        // less what the last bookkeeping already read (the same objects, since moved into the log)
        val onFile = (civ.notificationsLog.lastOrNull()?.takeIf { it.turn == turn - 1 }?.notifications ?: emptyList()) + civ.notifications
        val seen = notificationsSeen[civ.civName] ?: emptySet<com.unciv.logic.civilization.Notification>()
        val notifications = onFile.filter { it !in seen } // an identity set: two notices with the same words on different turns are both news
        notificationsSeen[civ.civName] = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.unciv.logic.civilization.Notification, Boolean>()).apply { addAll(onFile) }
        diplomacy.sweepLost(civ)
        // An alliance won and lost again within the turn (a tie in influence flipping back and forth) is churn, not news
        val flipped = notifications.filter { "We have allied with [" in it.text }.map { it.text.substringAfter("allied with [").substringBefore("]") }
            .filter { ally -> notifications.any { "lost our alliance" in it.text && "[$ally]" in it.text } }.toSet()
        // A city that changed hands this turn: the engine's "has been built" lines for it are what it came with, told with the taking (below), not as new work
        val takenNow = civ.cities.filter { it.name !in before.queues }.map { it.name }.toSet()
        val takenWith = HashMap<String, MutableList<String>>()
        for (notification in notifications) {
            if (noise.any { it in notification.text }) continue
            val came = Regex("^\\[(.+)\\] has been built in \\[(.+)\\]$").find(notification.text)?.takeIf { it.groupValues[2] in takenNow }
            if (came != null) { takenWith.getOrPut(came.groupValues[2]) { ArrayList() } += came.groupValues[1]; continue }
            if (flipped.any { "[$it]" in notification.text && ("allied with" in notification.text || "lost our alliance" in notification.text) }) continue
            // The engine's template text loses its brackets and braces ("{Gained 1 Settler unit(s)}{ }{due to adopting Collective Rule}")
            val text = inOurWords(notification.text.replace("[", "").replace("]", "").replace("{", "").replace("}", "")) +
                // the engine's minimum war binds the side attacked as well: say so where the war is first heard of
                (if ("has declared war on us" in notification.text) " No peace can be made for ${civ.gameInfo.ruleset.modOptions.constants.minimumWarDuration} turns." else "")
            // "An enemy X has pillaged our Farm" says not whose: the raider still stands on the tile it pillaged
            val raider = if (notification.text.startsWith("An enemy [") && "has pillaged" in notification.text)
                notification.actions.filterIsInstance<com.unciv.logic.civilization.LocationAction>().firstOrNull()?.location
                    ?.let { civ.gameInfo.tileMap.getIfTileExistsOrNull(it.x, it.y) }?.getUnits()?.firstOrNull { it.civ != civ && it.isMilitary() }?.civ
                else null
            // A blow on us is told below, folded with the turn's others and with whose hand it was (the engine says "An enemy Pikeman"); a civilian taken has its own loss line
            if (!blowNotice.containsMatchIn(notification.text)) log(civ, if (raider == null) text else text.replaceFirst(" has pillaged", " of ${if (raider.isBarbarian) "the barbarians" else RulerCampaigns.people(raider.civName)} has pillaged"))
            // An attack's notice carries where the attacker stood: a ship there is remembered for the threats list, since it may sail out of sight before a look-in
            if (notification.text.startsWith("An enemy [")) notification.actions.filterIsInstance<com.unciv.logic.civilization.LocationAction>().getOrNull(1)?.location
                ?.let { civ.gameInfo.tileMap.getIfTileExistsOrNull(it.x, it.y) }?.let { from -> from.getUnits().firstOrNull { it.civ != civ && it.isMilitary() && it.baseUnit.isWaterUnit }?.let { ship ->
                    val target = Regex("has \\w+\\s+(?:the defence of )?(?:our )?\\[([^\\]]+)\\]").find(notification.text)?.groupValues?.get(1) ?: "one of ours"
                    shipRaids.getOrPut(civ.civName) { HashMap() }[ship.id] = ShipRaid(turn, ship.civ.civName, target, RulerUnits.where(civ, from)) } }
        }
        shipRaids[civ.civName]?.values?.removeAll { turn - it.turn > 5 }
        // A ruler's queue binds until it has run out
        // (a queue that ran out mid-turn is refilled by the officials at once, so "empty" is not enough of a test:
        //  a front item the ruler never ordered means the ruler's queue is spent)
        for (city in civ.cities) {
            val queue = city.cityConstructions.constructionQueue
            val ordered = before.queues[city.name] ?: emptyList()
            if (queue.isEmpty() || queue.first() !in ordered) RulerOverrides.lockedCities -= city.id
        }
        // Then what the officials changed on their own
        val paid = spending.remove(civ.civName) ?: emptyList<Pair<Int, String>>()
        for (city in civ.cities) {
            val was = before.queues[city.name] ?: continue
            val now = city.cityConstructions.constructionQueue
            // a bought item leaves the queue with no notification: it was not dropped, it was paid for (the spending line says so)
            val built = notifications.filter { "has been built in [${city.name}]" in it.text }.map { it.text.substringAfter("[").substringBefore("]") } +
                paid.map { it.second }.mapNotNull { Regex("buying (.+) in ${Regex.escape(city.name)}$").find(it)?.groupValues?.get(1) }
            val isPerpetual = { name: String -> com.unciv.models.ruleset.PerpetualConstruction.isNamePerpetual(name) }
            for (item in was) if (item !in now && item !in built && !(isPerpetual(item) && city.id !in RulerOverrides.lockedCities)) {
                // Gone from here and newly queued in another city the same turn: the officials moved it (a limited item counts the queued copy, so "why not" would be wrong)
                val movedTo = if (isPerpetual(item)) null else civ.cities.firstOrNull { other -> other != city && item in other.cityConstructions.constructionQueue && item !in (before.queues[other.name] ?: emptyList()) }
                if (movedTo != null) { log(civ, "The officials moved $item from ${city.name}'s queue to ${movedTo.name}'s."); continue }
                val construction = civ.gameInfo.ruleset.buildings[item] ?: civ.gameInfo.ruleset.units[item]
                val reasons = construction?.let { whyNot(city, it) } ?: emptyList()
                log(civ, if (reasons.isNotEmpty()) "The officials dropped $item from ${city.name}'s queue because ${reasons.joinToString()}."
                    else if (city.id !in RulerOverrides.lockedCities) "The officials changed their own plan for ${city.name}: $item out."
                    else "The officials dropped $item from ${city.name}'s queue, replacing it with their own choice.")
            }
            // A city that starts or stops turning its production into Science or Gold (or faith) by the officials' hand: one line each way (Songhai never ordered it, Run 20)
            val wasTurning = was.firstOrNull()?.takeIf(isPerpetual); val nowTurning = now.firstOrNull()?.takeIf(isPerpetual)
            if (wasTurning != nowTurning && city.id !in RulerOverrides.lockedCities) log(civ, when {
                nowTurning != null -> "The officials set ${city.name} to turn its production into $nowTurning."
                else -> "${city.name} no longer turns its production into $wasTurning: the officials have it build ${now.firstOrNull() ?: "nothing"}." })
            for (item in now) if (item !in was && !isPerpetual(item) && civ.cities.none { other -> other != city && item in (before.queues[other.name] ?: emptyList()) && item !in other.cityConstructions.constructionQueue
                    && notifications.none { "has been built in [${other.name}]" in it.text && "[$item]" in it.text } })
                log(civ, "The officials queued $item in ${city.name}" + (favouredBy(civ, city, item)?.let { ", favoured by your $it weight" } ?: "") + ".")
        }
        for (city in civ.cities) if (city.name !in before.queues && !diplomacy.married.remove(city.name)) {
            // A city new to our rolls was founded only if nobody held it before us: retaking our own is "back", and the buildings it came with were not built this turn
            val from = city.previousOwner.takeIf { it.isNotEmpty() && it != civ.civID }?.let { civ.gameInfo.getCivilization(it).civName }
            val standing = takenWith[city.name].orEmpty()
            log(civ, if (from == null && city.foundingCivObject == civ) "Founded ${city.name}."
                else (if (city.foundingCivObject == civ) "We took ${city.name} back from ${from ?: "its holders"}" else "We took ${city.name}${(from ?: city.foundingCivObject?.civName)?.let { " from $it" } ?: ""}") +
                    (if (standing.isNotEmpty()) " (its ${standing.joinToString(" and ")} ${if (standing.size == 1) "stands" else "stand"})" else "") +
                    (if (city.isPuppet) ": it is ours, held as a puppet (it builds and pays for itself, gives no orders) until you annex it - or raze it, or spare it; a cities order does each."
                    else ": it is ours${if (city.foundingCivObject == civ) " again" else ""}, and governed as our own."))
        }
        val alive = civ.units.getCivUnits().map { it.id }.toSet()
        val fallen = losses.remove(civ.civName) ?: emptyList<RulerOverrides.UnitLoss>()
        // Re-arming keeps a unit's number, so a number that is gone was killed, captured, disbanded, used up, or founded a city
        for ((id, unit) in before.units) if (id !in alive) {
            if (units.given.remove(id)) continue // given away by order: its own line said so
            val fell = fallen.firstOrNull { it.unit.id == id }
            if (fell == null && unit.first == "Settler") continue // settlers vanish by founding
            val spent = civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CreateWaterImprovements) == true
            val great = civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.GreatPerson) == true
            val disbandedWhy = disbandedFor[civ.civName]?.remove(id)
            val needs = civ.gameInfo.ruleset.units[unit.first]?.requiredResource
            // The host knows whose hand spent a unit: the ruler's use order says so as it is carried out, anything else was the officials' once it was left to them
            val byWhom = if (units.usedByOrder.remove(id)) "by your order" else "by the officials once it was left to them"
            val went = expended[civ.civName]?.remove(id)
            // No unit number: a unit gone from the rolls is nothing the advisor can order, and the line may reach the ruler's letter
            log(civ, if (disbandedWhy != null && !civ.isDefeated()) "Our ${unit.first} ${describeWhere(civ, unit.second)} was disbanded by the officials $disbandedWhy."
                else if (fell != null) "Our ${unit.first} was ${if (fell.captured) "captured" else "lost in action"} ${describeWhere(civ, fell.where)}" +
                    (fell.enemy?.let { enemy -> ", ${if (fell.captured) "taken" else "killed"} by ${if (enemy.isBarbarian) "a barbarian" else enemy.civName + "'s"} ${fell.enemyUnit ?: "soldiers"}" } ?: "") + "."
                // A realm that fell takes what was left of its host with it: no officials disbanded them (the Maya at T210, Run 20)
                else if (civ.isDefeated()) "Our ${unit.first} ${describeWhere(civ, unit.second)} was lost with the fall of ${civ.civName}."
                else if (spent) "Our ${unit.first} ${describeWhere(civ, unit.second)} did its work and was used up, as such boats are: the fishing grounds it set up stay."
                else if (great) "Our ${unit.first} was spent $byWhom: its ability used ${describeWhere(civ, went?.second ?: unit.second)}."
                else if (went != null) "Our ${unit.first} ${describeWhere(civ, went.second)} ${went.first}${if (went.first.startsWith("was used up")) ", $byWhom" else ""}."
                else if (civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.SelfDestructs) == true) "Our ${unit.first} was spent on its strike, launched ${describeWhere(civ, unit.second)}."
                // A part that reached the capital is not lost: it is aboard
                else if (civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.SpaceshipPart) == true && civ.victoryManager.currentsSpaceshipParts[unit.first] > 0) "Our ${unit.first} was added to the ship in the capital."
                else "Our ${unit.first} ${describeWhere(civ, unit.second)} left service: disbanded by the officials to save its upkeep${needs?.let { " or to free its $it" } ?: ""}, or used up (a missionary spent).")
        }
        // Our own blows, which the engine tells only to the one struck: one line per target this turn - a city by name, a power's soldiers in the field together,
        // its ships at sea together (a blow on a barbarian Caravel once read "soldiers in the field")
        for ((_, blows) in (strikes.remove(civ.civName) ?: emptyList<RulerOverrides.Strike>()).groupBy { it.targetCity ?: (it.targetCivName + if (it.targetTile?.isWater == true) " at sea" else "") }) {
            val whose = blows.first().targetCivName.let { if (it == Constants.barbarians) "the barbarians" else it }
            val theirs = whose + if (whose.endsWith("s")) "'" else "'s" // the Aztecs' soldiers, Japan's
            val times = when (blows.size) { 1 -> "once"; 2 -> "twice"; else -> "${blows.size} times" }
            val city = blows.first().targetCity
            log(civ, if (city != null) "Our host struck $city ($whose) $times: " + (if (blows.any { it.fell }) "the walls were stormed and the city is taken." else "the walls at ${blows.last().health} of ${blows.last().maxHealth} health.")
                else "Our host struck " + (if (blows.first().targetTile?.isWater == true) {
                    // On the water, ships and embarked soldiers alike: in one harbour, said by name, or else at sea
                    val harbour = blows.mapNotNull { it.targetTile }.map { RulerCampaigns.placeOf(civ, it, emptySet()) }.distinct().singleOrNull()?.takeIf { it.startsWith("the harbour of") }
                    "$whose ${harbour?.let { "in $it" } ?: "at sea"}"
                } else "$theirs soldiers in the field") + " $times: " + listOfNotNull(
                    blows.filter { it.fell }.map { it.targetUnit!! }.takeIf { it.isNotEmpty() }?.let { "their ${it.joinToString(" and ")} fell" },
                    // the last blow on each unit still standing: one hit twice and then killed is a fall, not a fall and a survivor
                    blows.filter { !it.fell && it.targetUnitId !in blows.filter { b -> b.fell }.map { b -> b.targetUnitId } }.groupBy { it.targetUnitId ?: it.targetUnit!! }.values
                        .map { hits -> "${hits.last().targetUnit} at ${hits.last().health} health" }.takeIf { it.isNotEmpty() }?.let { "their ${it.joinToString(", ")}" }
                ).joinToString("; ") + ".")
        }
        // The enemy's blows on us, folded the same way (the engine's notice of each is dropped above): one line per attacker and place - a city by name, our soldiers
        // or ships where they stood - with what struck, how our walls and companies stand after, who of ours fell and who of theirs died in the attempt
        for ((key, hits) in (blows.remove(civ.civName) ?: emptyList<RulerOverrides.Strike>()).groupBy { it.attacker.civName to (it.targetCity ?: it.targetTile?.let { RulerCampaigns.placeOf(civ, it, enemies) } ?: "") }) {
            val (attacker, place) = key
            val arms = hits.mapNotNull { it.attackerUnit }.distinct(); val guns = hits.mapNotNull { it.attackerCity }.distinct()
            val owner = if (attacker == Constants.barbarians) "the barbarians'" else RulerCampaigns.possessive(attacker)
            val list = { parts: List<String> -> if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last() }
            // "Polynesia's Machine Gun and the guns of Tonga", "The guns of Polynesia's Tonga", "The barbarians' Brute"
            val whose = (if (arms.isEmpty()) "the guns of $owner ${list(guns)}" else "$owner ${list(arms + guns.map { "the guns of $it" })}").replaceFirstChar { it.uppercase() }
            val times = when (hits.size) { 1 -> "once"; 2 -> "twice"; else -> "${hits.size} times" }
            val city = hits.first().targetCity
            val died = hits.filter { it.attackerFell }.mapNotNull { it.attackerUnit }.takeIf { it.isNotEmpty() }?.let { "; their ${it.joinToString(" and ")} died in the attempt" } ?: ""
            log(civ, if (city != null) "$whose struck $city $times: " + (if (hits.any { it.fell }) (if (attacker == Constants.barbarians) "the walls were overrun and the city raided" else "the walls were stormed and the city is lost") else "the walls at ${hits.last().health} of ${hits.last().maxHealth} health") + died + "."
                else "$whose struck our ${if (hits.first().targetTile?.isWater == true) "ships" else "soldiers"} ${RulerCampaigns.atPlace(place)} $times: " + listOfNotNull(
                    hits.filter { !it.fell && it.targetUnitId !in hits.filter { b -> b.fell }.map { b -> b.targetUnitId } }.groupBy { it.targetUnitId ?: it.targetUnit!! }.values
                        .map { blows -> "our ${blows.last().targetUnit} at ${blows.last().health} health" }.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    hits.filter { it.fell }.map { it.targetUnit!! }.takeIf { it.isNotEmpty() }?.let { "our ${it.joinToString(" and ")} fell" }
                ).joinToString("; ") + died + ".")
        }
        applyStandingOrders(civ) // a finished goal leaves the queue empty until the next goal's path is set - not idleness
        val researching = civ.tech.currentTechnologyName()
        if (researching == null) {
            if (before.researching != null) log(civ, "No research goal remains; the officials choose the next technology themselves until goals are given.")
        } else if (before.researching != researching && researching !in (orders[civ.civName]?.research ?: emptyList())) {
            val goal = orders[civ.civName]?.research?.firstOrNull { !reached(civ, it) }
            val queue = civ.tech.techsToResearch
            val onTheWay = goal != null && researching in queue && goal in queue
            log(civ, "Research is now on $researching" + (if (onTheWay) ", on the way to $goal (${queue.indexOf(goal) - queue.indexOf(researching)} more step${if (queue.indexOf(goal) - queue.indexOf(researching) == 1) "" else "s"} after it)" else "") + ".")
        }
        diplomacy.peaceOffers(civ, announce = true)
        diplomacy.tradeOffers(civ, announce = true)
        diplomacy.friendshipOffers(civ, announce = true)
        diplomacy.demands(civ, announce = true)
        orders[civ.civName]?.let { standing ->
            val aim = standing.warAim ?: return@let
            val target = civ.gameInfo.getCities().firstOrNull { it.name == aim }
            if (target == null || !civ.isAtWarWith(target.civ)) {
                standing.warAim = null
                RulerOverrides.postures[civ.civID] = RulerOverrides.Posture(null, standing.stance, standing.reserve)
                log(civ, if (target?.civ == civ) "$aim is ours. The host has no aim now; name the next city, or make peace." else "$aim is no longer a city of an enemy; the host has no aim now.")
            }
        }
        // Deals: say exactly what came into force and what ended
        val dealsNow = diplomacy.dealsInForce(civ)
        for (deal in diplomacy.endingSoon(civ, 5) - diplomacy.endingSoon(civ, 4).toSet()) log(civ, "A deal ends in five turns, in ${yearText(turn + 5)}: $deal. The same terms can be offered to them again only once it has ended; order renew and they are offered again that very turn, as our proposal.")
        val began = (dealsNow - before.deals).toList(); val ended = (before.deals - dealsNow).toList()
        // A deal that ends the turn its renewal begins was replaced, not lost: same partner, same goods, only the numbers differ
        val goodsOf = { deal: String -> deal.replace(Regex(" x\\d+"), "").replace(Regex("\\d+"), "#") }
        val (changed, lapsed) = ended.partition { old -> began.any { goodsOf(it) == goodsOf(old) } }
        // Renewed within the turn on the very same terms (another court's bookkeeping took our officials' offer): it reads the same, but its turns went up
        val replaced = changed + diplomacy.dealTurnsLeft(civ).filter { (deal, left) -> deal in before.deals && left > (before.turnsLeft[deal] ?: left) }.keys
        val renewedAs = changed.associateWith { old -> began.first { goodsOf(it) == goodsOf(old) } }
        if (began.isNotEmpty() || replaced.isNotEmpty() || lapsed.isNotEmpty()) log(civ, "Deals: " + listOfNotNull(
            (began - renewedAs.values.toSet()).takeIf { it.isNotEmpty() }?.let { "in force with " + it.joinToString("; ") },
            // A renewal says which terms now hold: the new ones beside the old, or the same terms running again
            replaced.takeIf { it.isNotEmpty() }?.let { old -> old.joinToString("; ") { ended -> renewedAs[ended]?.let { now -> "renewed, and these terms now hold - $now (in place of: ${ended.substringAfter(": ")})" } ?: "renewed on the same terms, which run again from now - $ended" } },
            lapsed.takeIf { it.isNotEmpty() }?.let { "ended with " + it.joinToString("; ") }).joinToString(". ") + ".")
        val goldBeforeRenewals = civ.gold
        diplomacy.renewEnded(civ, lapsed, replaced) // the renewals ordered for deals that ended this turn go out now, as our own proposals
        val paidByRenewals = goldBeforeRenewals - civ.gold // the ruler's own renewal paid it, by its own line: not "without the officials' hand"
        diplomacy.officialsDealings(civ)
        // The never-build list is applied where the officials choose; this catches what slipped past (an item already in the queue when it was banned)
        for (city in civ.cities) {
            val current = city.cityConstructions.getCurrentConstruction() as? INonPerpetualConstruction ?: continue
            if (city.id in RulerOverrides.lockedCities || !isForbidden(civ, current)) continue // the user-set flag outlives the ruler's queue, the lock does not
            city.cityConstructions.constructionQueue.remove(current.name)
            log(civ, if ((current as? com.unciv.models.ruleset.unit.BaseUnit)?.isCityFounder() == true && !units.hasLandForACity(civ)) "The officials wanted ${current.name} in ${city.name}, but there is no land left for a new city, so it was struck."
                else "The officials wanted ${current.name} in ${city.name}; it is on the never-build list, so it was struck.")
            if (city.cityConstructions.constructionQueue.isEmpty()) city.cityConstructions.chooseNextConstruction()
        }
        for ((what, items) in paid.groupBy { it.second }) log(civ, "The officials spent ${items.sumOf { it.first }} gold on ${what}.")
        officialsSpent[civ.civName] = paid.groupBy { it.second }.map { (what, items) -> "${items.sumOf { it.first }} gold on $what" }
        faithSpending.remove(civ.civName)?.let { spent -> log(civ, "The officials spent ${spent.sumOf { it.first }} faith on ${spent.joinToString(" and ") { (if (it.second.first().lowercaseChar() in "aeiou") "an " else "a ") + it.second }}; ${civ.religionManager.storedFaith} faith is left" +
            (orders[civ.civName]?.faithReserve?.let { " (they leave $it untouched, by your reserve)" } ?: " (no faith reserve is set: they may spend it all)") + ".") }
        freeTechs.remove(civ.civName)?.forEach { (tech, goal) -> log(civ, if (goal != null) "A free technology: $tech, taken ${if (goal == tech) "as our research goal" else "on the way to $goal"}." else "A free technology: $tech, the officials' pick - no research goal named a step that could be taken.") }
        if (paid.isNotEmpty()) log(civ, "The treasury now holds ${civ.gold} gold.")
        val unexplained = before.gold - civ.gold - paid.sumOf { it.first } - diplomacy.officialsDealGold.remove(civ.civName).let { it ?: 0 } - paidByRenewals.coerceAtLeast(0)
        // Worth a line when it is more than a turn's income (never for trifles under 10): forty gold is a fortune in one age and small change in another
        val income = civ.stats.statsForNextTurn.gold.toInt()
        if (unexplained > maxOf(10, income)) log(civ, "A further $unexplained gold left the treasury this turn without the officials' hand in it (upkeep, payments under our deals, or a deal struck).")
        if (before.happiness >= 0 && civ.getHappiness() < 0) log(civ, "The people have become unhappy (${civ.getHappiness()}).")
    }

    private fun isForbidden(civ: Civilization, construction: INonPerpetualConstruction): Boolean {
        // The officials would go on training settlers for ever; with no land left they sit idle or get disbanded, a city's growth thrown away each time
        if ((construction as? com.unciv.models.ruleset.unit.BaseUnit)?.isCityFounder() == true && !units.hasLandForACity(civ)) return true
        val forbidden = orders[civ.civName]?.neverBuild ?: return false
        return forbidden.any { entry ->
            entry.equals(construction.name, ignoreCase = true)
                || entry.lowercase() in listOf("all wonders", "wonders") && (construction as? com.unciv.models.ruleset.Building)?.isAnyWonder() == true
                || entry.lowercase() in listOf("all military units", "military units") && (construction as? com.unciv.models.ruleset.unit.BaseUnit)?.isMilitary == true
        }
    }

    /** The turn whose vote was last told, the vote each realm was last reminded of, and who held the United Nations at the last look - so each is told once */
    private var voteTallied = -1
    private val voteReminded = HashMap<String, Int>()
    private var unHolder: String? = null

    /** A ruled realm's vote when the world votes: as pledged, or an abstention - and the chancery says which */
    private fun castWorldVote(civ: Civilization): String? {
        val pledged = orders[civ.civName]?.worldVote
        val power = pledged?.let { name -> civ.getKnownCivs().firstOrNull { it.civName == name && it.isMajorCiv() && !it.isDefeated() } }
        log(civ, when {
            power != null -> "The world voted for its leader: by your pledge, our vote went to ${power.civName}."
            pledged != null -> "The world voted for its leader: our vote was pledged to $pledged, who no longer stands, so we abstained."
            else -> "The world voted for its leader: our vote was not pledged, so we abstained."
        })
        return power?.civID
    }

    /** After a turn: the United Nations standing for the first time, a vote drawing near with our vote unpledged, and the tally once the votes are counted */
    private fun noteWorldVote(game: GameInfo, ruled: List<Civilization>) {
        if ("Diplomatic" !in game.gameParameters.victoryTypes || ruled.isEmpty()) return
        val holder = ruled.first().victoryManager.getUNBuildingAndOwnerNames().second?.let { game.getCivilization(it) }
        if (holder != null && unHolder == null) for (civ in ruled.filter { it.isAlive() }) {
            val pledged = orders[civ.civName]?.worldVote
            log(civ, "The United Nations now stands, built by ${if (holder == civ) "us" else holder.civName}: the world votes for its leader in ${civ.getTurnsTillNextDiplomaticVote() ?: civ.getTurnsBetweenDiplomaticVotes()} turns, and every ${civ.getTurnsBetweenDiplomaticVotes()} turns after. " +
                (pledged?.let { "Our vote is pledged to $it." } ?: "Our vote is not pledged: unpledged, we abstain - set worldVote to pledge it."))
        }
        unHolder = holder?.civName
        // A vote close at hand with our vote unpledged: said once per vote, in time for a look-in
        for (civ in ruled.filter { it.isAlive() }) {
            val due = civ.getTurnsTillNextDiplomaticVote() ?: continue
            if (due !in 1..4 || orders[civ.civName]?.worldVote != null || voteReminded[civ.civName] == game.turns + due) continue
            voteReminded[civ.civName] = game.turns + due
            log(civ, "The world votes for its leader in $due turn${if (due == 1) "" else "s"}, and our vote is not pledged: unpledged, we abstain. Set worldVote to pledge it to a great power.")
        }
        // The votes are counted the turn after they are cast, and cleared the turn after that: told once, to every realm
        val votes = game.diplomaticVictoryVotesCast
        if (votes.isEmpty() || !game.diplomaticVictoryVotesProcessed || voteTallied == game.turns - 1 || voteTallied == game.turns) return
        voteTallied = game.turns
        val unOwner = holder?.civID
        val name = { id: String -> game.civilizations.firstOrNull { it.civID == id }?.civName ?: id }
        val tally = votes.entries.filter { it.value != null }.groupBy { it.value!! }.mapValues { (_, cast) -> cast.sumOf { if (it.key == unOwner) 2 else 1 } }.entries.sortedByDescending { it.value }
        val abstained = votes.values.count { it == null }
        val elected = game.civilizations.firstOrNull { it.isMajorCiv() && it.victoryManager.hasEnoughVotesForDiplomaticVictory() }
        val needed = (diplomaticFacts(ruled.first())?.get("votes") as? String)?.let { Regex("(\\d+) are needed").find(it)?.groupValues?.get(1) }
        val counted = tally.joinToString { "${name(it.key)} ${it.value}" }.ifEmpty { "no power had a vote" } + (if (abstained > 0) "; $abstained abstained" else "")
        for (civ in ruled.filter { it.isAlive() }) {
            val ours = votes[civ.civID]
            log(civ, "The world's vote for its leader was counted: $counted. We ${if (votes.containsKey(civ.civID)) (ours?.let { "voted for ${name(it)}" } ?: "abstained") else "did not vote"}. " +
                (if (elected != null) "${if (elected == civ) "We are" else "${elected.civName} is"} elected world leader${needed?.let { ", with the $it votes needed" } ?: ""}."
                 else "Nobody won the${needed?.let { " $it" } ?: ""} votes needed; the world votes again in ${civ.getTurnsBetweenDiplomaticVotes()} turns."))
        }
    }

    /** A diplomatic victory in facts, when one is in play: who votes, how many votes there are and how many win, what calls the vote, and the city-states each power we know holds */
    /** What the officials spent from the treasury in the last turn, per realm: the gold breakdown's last line (not kept across a restart) */
    private val officialsSpent = HashMap<String, List<String>>()

    /** Gold a turn by source, as the game's own overview shows it: income and expenses, the officials' gifts and purchases of the last turn apart */
    private fun goldBreakdown(civ: Civilization): Map<String, Any?> {
        val bySource = civ.stats.getStatMapForNextTurn().mapValues { it.value.gold.toInt() }.filterValues { it != 0 }
        val units = civ.units.getCivUnits().count()
        // The overview's own headings, a few put plainly
        val named = { key: String -> when (key) {
            "Unit upkeep" -> "Unit upkeep ($units units)"; "Maintenance" -> "Buildings' upkeep"; "Trade" -> "Deals (gold per turn)"
            "Construction" -> "Cities building gold"; else -> key } }
        return mapOf(
            "income" to bySource.filterValues { it > 0 }.entries.sortedByDescending { it.value }.associate { named(it.key) to it.value },
            "expenses" to bySource.filterValues { it < 0 }.entries.sortedBy { it.value }.associate { named(it.key) to it.value },
            "net" to bySource.values.sum(),
            "officialsSpentLastTurn" to officialsSpent[civ.civName]?.ifEmpty { listOf("nothing") },
            "note" to "the officials' gifts and purchases are spent from the treasury, not from these figures")
    }

    private fun diplomaticFacts(civ: Civilization): Map<String, Any?>? {
        val game = civ.gameInfo
        if ("Diplomatic" !in game.gameParameters.victoryTypes) return null
        val un = game.ruleset.buildings.values.firstOrNull { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.OneTimeTriggerVoting, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals) } ?: return null
        val holder = civ.victoryManager.getUNBuildingAndOwnerNames().second?.let { game.getCivilization(it) }
        val voters = game.civilizations.filter { !it.isBarbarian && !it.isSpectator() && !it.isDefeated() }
        val total = voters.size + 1
        // VictoryManager.votesNeededForDiplomaticVictory, the original game's own sum: counted on the votes there are once the United Nations stands
        val needed = diplomacy.votesNeeded(game)
        val every = civ.getTurnsBetweenDiplomaticVotes()
        val powers = (listOf(civ) + civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }).toList()
        return mapOf(
            "unitedNations" to if (holder != null) "built by ${if (holder == civ) "us" else holder.civName}: the world votes for a leader every $every turns" + (civ.getTurnsTillNextDiplomaticVote()?.let { if (it == 0) "; the world votes this turn" else "; the next vote in $it turn${if (it == 1) "" else "s"}" } ?: "") +
                    // City-states cast for their ally as the vote's turn is played, after every great power has moved (NextTurnAutomation): what moves one must be done before
                    ". A city-state votes for whoever is its ally when the voting turn is played, after every great power has moved in it, so the last look-in that can still move a city-state's vote is the one before that turn"
                else "not yet built (${un.name} is a wonder${un.requiredTech?.let { " that needs $it" } ?: ""}): nobody votes until it stands; the first vote comes $every turns after it is built, then one every $every turns",
            "votes" to "$total votes in all${if (holder == null) " once it stands" else ""}: one for each of the ${voters.count { it.isMajorCiv() }} great powers and ${voters.count { it.isCityState }} city-states still standing, and a second for the holder of the ${un.name}. $needed are needed to win, and the most votes with no tie. No power may vote for itself - the holder's two votes go to another",
            "howTheyVote" to run {
                val courts = powers.filter { it != civ && it.civID in RulerOverrides.ruledCivs }.map { it.civName }
                val played = powers.filter { it != civ && it.civID !in RulerOverrides.ruledCivs }.map { it.civName }
                "a city-state votes for its ally, and abstains without one - its vote follows influence alone, never attitude. " +
                    (if (courts.isNotEmpty()) "${courts.joinToString()} ${if (courts.size == 1) "is an advised court" else "are advised courts"}: each votes as its court has pledged, and abstains unpledged. " else "") +
                    (if (played.isNotEmpty()) "${played.joinToString()} ${if (played.size == 1) "is" else "are"} played by the game: each votes for the great power it regards most highly. " else "") +
                    "Our own vote is ours: cast as pledged (worldVote), or we abstain. No great power's vote is known before it is cast"
            },
            "ourVote" to (orders[civ.civName]?.worldVote?.let { "pledged to $it" } ?: "not pledged - we abstain when the world votes"),
            "cityStatesWeKnow" to civ.getKnownCivs().count { it.isCityState && it.isAlive() },
            "cityStateAllies" to powers.associate { power -> (if (power == civ) "us" else power.civName) to civ.getKnownCivs().count { it.isCityState && it.isAlive() && it.allyCiv == power } },
            "cityStatesWeHaveNotMet" to game.civilizations.count { it.isCityState && it.isAlive() && !civ.knows(it) }.takeIf { it > 0 }?.let { "$it - each votes for its own ally, which we cannot see" })
    }

    /** What one more city of our own adds to every policy's culture cost: the map's size sets it, and some policies cut it (PolicyManager.getPolicyCultureCost) */
    private fun policyCostPerCity(civ: Civilization): String {
        var modifier = civ.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().policyCostPerCityModifier
        civ.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.LessPolicyCostFromCities).forEach { modifier *= 1 - it.params[0].toFloat() / 100 }
        return "${kotlin.math.round(modifier * 1000) / 10}%".replace(".0%", "%")
    }

    /** A cultural victory in facts, when one is in play: the branches of policy each power we know has completed against those needed, and the last step */
    private fun culturalFacts(civ: Civilization): Map<String, Any?>? {
        val game = civ.gameInfo
        if ("Cultural" !in game.gameParameters.victoryTypes) return null
        val milestones = game.ruleset.victories["Cultural"]?.milestoneObjects ?: return null
        val needed = milestones.firstOrNull { it.type == com.unciv.models.ruleset.MilestoneType.CompletePolicyBranches }?.params?.firstOrNull()?.toIntOrNull()
        val last = milestones.firstOrNull { it.type == com.unciv.models.ruleset.MilestoneType.BuiltBuilding }?.params?.firstOrNull()?.let { game.ruleset.buildings[it] }
        return mapOf(
            "branchesNeeded" to needed,
            // Our own branches, policy by policy (the opening policy counts): rivals' are not ours to see beyond the branches they complete
            "ourBranches" to ourBranches(civ).map { (branch, done, total) -> "${branch.name} $done of $total" + if (done == total) " (complete)" else "" }.ifEmpty { null },
            "ourNearestBranch" to ourBranches(civ).filter { it.second < it.third }.minByOrNull { it.third - it.second }?.let { (branch, done, total) -> "${branch.name}: ${total - done} more ${if (total - done == 1) "policy" else "policies"} complete it" },
            "branchesCompleted" to (listOf(civ) + civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }).associate { (if (it == civ) "us" else it.civName) to it.policies.completedBranches.size },
            "lastStep" to last?.let { "${it.name}: a national wonder of ${it.getProductionCost(civ, null)} production that only a power with ${needed ?: "the"} branches complete may build${if (it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CannotBeHurried)) "; it cannot be hurried" else ""}. The power that finishes it wins" })
    }

    /** Each branch of policy we have opened: (branch, policies of it adopted, policies in it), the opening one counted */
    private fun ourBranches(civ: Civilization) = civ.gameInfo.ruleset.policyBranches.values.filter { civ.policies.isAdopted(it.name) }.map { branch ->
        val members = branch.policies.filter { it.policyBranchType == com.unciv.models.ruleset.Policy.PolicyBranchType.Member }
        Triple(branch, 1 + members.count { civ.policies.isAdopted(it.name) }, 1 + members.size) }

    /** The ways this game can be won, as its own settings have them - a game may have been set to fewer than all */
    private fun victoriesInPlay(civ: Civilization): String {
        val types = civ.gameInfo.gameParameters.victoryTypes.filter { it in civ.gameInfo.ruleset.victories }
        if (types.isEmpty()) return "No victory is in play in this game."
        val how = types.map { type -> when (type) {
            "Scientific" -> "a scientific victory, the turn the last spaceship part is added to the ship in the capital"
            "Domination" -> "a domination victory, by holding every other great power's original capital"
            "Cultural" -> "a cultural victory, by completing five branches of policy and building the Utopia Project"
            "Diplomatic" -> "a diplomatic victory, by being voted world leader once the United Nations stands"
            else -> "a ${type.lowercase()} victory" } }
        return "This game can be won only by " + (if (how.size == 1) how.single() else how.dropLast(1).joinToString("; ") + "; or " + how.last()) + "."
    }

    /** A place as a chancery line names it, with its preposition: "at the fields of Ecbatana", "in the country south of Persepolis" - never a count of tiles, which the ruler must not read */
    private fun describeWhere(civ: Civilization, tile: Tile): String = RulerCampaigns.atPlaceOf(civ, tile)

    /** A city's place in the world as a painter needs it: "on the coast, among forests, grassland country" */
    private fun lieOfTheLand(city: com.unciv.logic.city.City): String {
        val centre = city.getCenterTile()
        return listOfNotNull(
            if (city.isCoastal()) "on the coast" else null,
            if (centre.isAdjacentToRiver()) "by a river" else null,
            if (centre.isHill()) "on a hill" else null,
            centre.getTilesInDistance(2).count { it.terrainFeatures.contains("Forest") }.takeIf { it >= 3 }?.let { "among forests" },
            centre.getTilesInDistance(2).any { it.baseTerrain == "Mountain" }.takeIf { it }?.let { "under mountains" },
            centre.baseTerrain.lowercase().takeIf { it in listOf("desert", "tundra", "snow", "plains", "grassland") }?.let { "$it country" }).joinToString(", ").ifEmpty { "inland" }
    }

    private class WorldSnapshot(game: GameInfo) {
        val wars = HashSet<Set<String>>()
        val cityOwners = HashMap<String, String>()
        val capitals = HashSet<String>()
        val wonders = HashMap<String, String>()
        /** wonder -> the city it stands in, and the lie of the land there */
        val wonderSites = HashMap<String, String>()
        val eras = HashMap<String, String>()
        val alive = HashSet<String>()
        init {
            for (civ in game.civilizations) {
                if (civ.isBarbarian || civ.isSpectator()) continue
                if (civ.isAlive()) alive += civ.civName
                if (civ.isMajorCiv()) eras[civ.civName] = civ.getEra().name
                for (city in civ.cities) {
                    cityOwners[city.name] = civ.civName
                    if (city.isCapital()) capitals += city.name
                    for (building in city.cityConstructions.getBuiltBuildings())
                        if (building.isWonder) {
                            wonders[building.name] = civ.civName
                            wonderSites[building.name] = "${city.name} (${if (city.isCapital()) "the capital, " else ""}${lieOfTheLand(city)})"
                        }
                }
                for (diplomacy in civ.diplomacy.values)
                    if (diplomacy.diplomaticStatus == DiplomaticStatus.War && !diplomacy.otherCiv.isBarbarian)
                        wars += setOf(civ.civName, diplomacy.otherCivName)
            }
        }
    }

    /** GameInfo.turns only moves once every player has played, so whatever is logged during a turn - or by an order between turns - belongs to the turn about to be counted */
    private fun stamp() = if (settling) game().turns else game().turns + 1

    private fun addPublicEvent(text: String) {
        publicEvents += mapOf("turn" to stamp(), "year" to yearText(stamp()), "text" to text)
    }

    private fun recordPublicEvents(before: WorldSnapshot, after: WorldSnapshot) {
        for (war in after.wars - before.wars) {
            // History needs to know who drew the sword: without it the Chronicler guesses, and blames the victim
            val (first, second) = war.toList().let { it[0] to it[1] }
            // The engine's own record wrote this one as it was declared, join or declaration (noteWar); the grievance below is read only for a war it did not see
            if (warsDeclared.any { it.covers(first, second) }) continue
            // the grievance fades slowly, so between two old enemies the fresher (deeper) one names this war's aggressor
            fun grievance(victim: String, aggressor: String) = game().civilizations.firstOrNull { it.civName == victim }
                ?.getDiplomacyManager(game().civilizations.first { it.civName == aggressor })
                ?.diplomaticModifiers?.get(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.DeclaredWarOnUs.name) ?: 0f
            val (secondWronged, firstWronged) = grievance(second, first) to grievance(first, second)
            addPublicEvent(when {
                secondWronged < 0f && secondWronged <= firstWronged -> "$first declared war on $second."
                firstWronged < 0f -> "$second declared war on $first."
                else -> "War has broken out between $first and $second (drawn in at an ally's side)."
            })
        }
        for (war in before.wars - after.wars)
            if (war.all { it in after.alive }) addPublicEvent("${war.joinToString(" and ")} have made peace.")
        val ended = HashSet<String>() // city-states whose end was told with their city's taking
        for ((city, owner) in after.cityOwners) {
            val previousOwner = before.cityOwners[city]
            if (previousOwner == null) {
                if (owner in after.eras) addPublicEvent("$owner founded the city of $city.") // city-states founding themselves is not news
            }
            else if (previousOwner != owner) {
                val game = game()
                val sides = listOf(owner, previousOwner).mapNotNull { name -> game.civilizations.firstOrNull { it.civName == name } }
                // A city-state's fall is one event, not a capture "from Sydney", a capital's fall and a destruction (the Chronicler wrote conquests of Zurich from Zurich)
                val cityState = sides.getOrNull(1)?.takeIf { it.isCityState && previousOwner !in after.alive }
                if (cityState != null) ended += previousOwner
                addPublicEvent(if (cityState != null) "$owner took $city, and the city-state of $previousOwner is no more." else "$owner captured $city from $previousOwner!")
                // The engine tells only the loser; every other realm that knows both sides hears it as news of the world
                if (sides.size == 2) for (name in orders.keys) {
                    if (name == owner) continue // the taker's own line says what it took
                    // The loser hears whose hand it was: the engine says only "An enemy Great War Infantry has captured" (Babylon blamed Greece for Budapest's taking)
                    if (name == previousOwner) { sides[1].takeIf { it.isAlive() }?.let { log(it, "$city${if (city in before.capitals) ", our capital," else ""} fell to $owner.") }; continue }
                    val listener = game.civilizations.firstOrNull { it.civName == name }?.takeIf { it.isAlive() } ?: continue
                    // A city-state's end is news to every court that knew it, whoever took it (the engine's own word on it is dropped from the chancery)
                    if (cityState != null) { if (listener.knows(cityState)) log(listener, "$city fell to $owner, and the city-state of $previousOwner is no more."); continue }
                    if (sides.all { listener.knows(it) })
                        log(listener, "$city${if (city in before.capitals) ", $previousOwner's capital," else ""} fell to $owner.")
                }
            }
        }
        for (city in before.cityOwners.keys - after.cityOwners.keys) addPublicEvent("The city of $city has been destroyed.")
        for ((wonder, owner) in after.wonders)
            if (wonder !in before.wonders) addPublicEvent("$owner completed the wonder $wonder in ${after.wonderSites[wonder] ?: "one of its cities"}.")
        for ((civ, era) in after.eras)
            if (before.eras[civ] != era) {
                // The first learning of the new age is what opened it: named, so the age's picture can show it rather than an emblem
                val people = game().civilizations.firstOrNull { it.civName == civ }
                val opened = people?.tech?.techsResearched?.firstOrNull { game().ruleset.technologies[it]?.era() == era }
                addPublicEvent("$civ entered the $era" + (opened?.let { " with the learning of $it" } ?: "") + ".")
            }
        for (civ in before.alive - after.alive - ended) addPublicEvent("$civ has been destroyed.")
    }

    /** Log lines an advisor would want to see at once, not at the next scheduled look-in */
    // Not answers to our own proposals: acting on one sends another, and the advisor would be woken by his own echo every turn.
    // Not "demands": that is a city asking for a luxury, not a neighbour asking for tribute.
    // Not our own order's echo ("By your order: we declared war"), and not a barbarian camp cleared ("We have captured a barbarian encampment").
    private val wakeWorthy = listOf("dropped .* because", "(?<!By your order: we )declared war", "joined .* in the war against us", "has denounced us", "lost in action",
        // A city-state's alliance lost: its vote and its goods go with it (the Inca lost Geneva at T281 and slept through the vote, Run 20). Churn within a turn is filtered before it is logged
        "lost our alliance",
        // The alerts every realm gets (the Utopia Project begun or finished): the end of the game may be in sight
        "has started constructing", "has completed [^.]*!$",
        "awaits orders", "The United Nations now stands", "our vote is not pledged", "(?<!We have )captured(?! a barbarian)", "has been destroyed", "is no more", "has been stuck", "offers peace", "\\(an advised court\\) offers a deal", "demands that we", "proposes a Declaration of Friendship").map { Regex(it, RegexOption.IGNORE_CASE) }

    private val warningsWoken = HashMap<String, HashSet<String>>()
    /** The world as the last advance left it (null before the first) */
    private var worldAtLastAdvance: WorldSnapshot? = null
    /** A war as the engine declared it (RulerOverrides.warDeclared), names only, kept since the last advance: the world's events and the council's news are
     *  built from these, so a pact partner's or a city-state's join is never told as a declaration, and the council is framed for the declarer as their
     *  own doing and for a partner pulled in as its pact's (audit 2026-10-03) */
    private class WarRecord(val declarer: String, val target: String, val type: WarType, val ally: String?) {
        val joins get() = type == WarType.DefensivePactWar || type == WarType.CityStateAllianceWar
        val byPact get() = type == WarType.DefensivePactWar
        fun covers(first: String, second: String) = setOf(declarer, target) == setOf(first, second)
        /** Is this join part of [war]: its ally's war against its target, on either side of it */
        fun joined(war: WarRecord) = joins && ally != null && (war.declarer == ally && war.target == target || war.target == ally && war.declarer == target)
        override fun toString() = "$declarer>$target>$type>${ally ?: ""}"
        companion object { fun parse(text: String) = text.split(">").let { WarRecord(it[0], it[1], WarType.valueOf(it[2]), it.getOrNull(3)?.ifEmpty { null }) } }
    }
    private val warsDeclared = ArrayList<WarRecord>()

    /** The world's one line for a war as it was declared */
    private fun told(war: WarRecord) = when (war.type) {
        WarType.DirectWar -> "${war.declarer} declared war on ${war.target}."
        WarType.DefensivePactWar -> "${war.declarer} came in at ${war.ally}'s side by its pact, against ${war.target}."
        WarType.CityStateAllianceWar -> "${war.declarer}, ${war.ally}'s ally, joined the war against ${war.target}."
        WarType.JoinWar -> "${war.declarer} declared war on ${war.target}, called in by ${war.ally}."
        WarType.TeamWar -> "${war.declarer} declared war on ${war.target}, together with ${war.ally}."
        WarType.ProtectedCityStateWar -> "${war.declarer} declared war on ${war.target} for ${war.ally}, the city-state under its protection."
        WarType.AlliedCityStateWar -> "${war.declarer} declared war on ${war.target} for its ally ${war.ally}."
    }

    /** Every war the engine declares, whoever's, as it is declared: recorded for the council, and written into the world's events at once */
    private fun noteWar(war: RulerOverrides.War) {
        if (war.declarer.isBarbarian || war.target.isBarbarian) return
        val record = WarRecord(war.declarer.civName, war.target.civName, war.type, war.ally?.civName)
        warsDeclared += record
        // A power's city-state allies join one by one as the engine calls them in; the world's history has them in one line, not one per city
        val last = publicEvents.lastOrNull()?.takeIf { it["turn"] == stamp() }
        val company = last?.let { Regex("^(.+), ${Regex.escape(record.ally ?: "")}'s all(?:y|ies), joined the war against ${Regex.escape(record.target)}\\.$").find(it["text"] as String) }
        if (record.type == WarType.CityStateAllianceWar && company != null) {
            val names = company.groupValues[1].replace(" and ", ", ").split(", ") + record.declarer
            publicEvents[publicEvents.size - 1] = last + ("text" to "${names.dropLast(1).joinToString()} and ${names.last()}, ${record.ally}'s allies, joined the war against ${record.target}.")
        } else addPublicEvent(told(record))
    }

    /** Who came in on a war's first day, for the declaring court's own account: the target's pact partners and allies, and our own allies */
    private fun cameIn(civ: Civilization, target: Civilization, joins: List<WarRecord>): String {
        fun names(list: List<WarRecord>) = list.map { it.declarer }.distinct().let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString() + " and " + it.last() }
        val partners = joins.filter { it.byPact && it.ally == target.civName }
        val theirAllies = joins.filter { !it.byPact && it.ally == target.civName }
        val ourAllies = joins.filter { !it.byPact && it.ally == civ.civName }
        val theirs = listOfNotNull(
            partners.takeIf { it.isNotEmpty() }?.let { "${target.civName}'s pact partner${if (it.size == 1) "" else "s"} ${names(it)} came in at its side" },
            theirAllies.takeIf { it.isNotEmpty() }?.let { "${if (partners.isEmpty()) "${target.civName}'s" else "its"} all${if (it.size == 1) "y" else "ies"} ${names(it)} joined the war" })
        return (if (theirs.isEmpty()) "" else " " + theirs.joinToString(", and ") + ".") +
            (if (ourAllies.isEmpty()) "" else " Our all${if (ourAllies.size == 1) "y" else "ies"} ${names(ourAllies)} came in with us.")
    }
    /** True while the turn's bookkeeping runs after nextTurn: lines written then belong to the turn just played, all other lines to the turn about to be */
    @Volatile private var settling = false

    private fun advance(turns: Int, wakeOnEvents: Boolean = false): Any? {
        brokenTurn?.let { throw ApiError(503, "A turn could not be played ($it); the world was reloaded as it stood before it. Restart the host before playing on.") }
        val game = game()
        if (game.majorCivs().any { it.victoryManager.hasWon() }) throw ApiError(409, "The game is over")
        val startTurn = game.turns
        // "Before" is the world as the last advance left it: a war declared by order between rounds is news to everyone else
        val roundStart = worldAtLastAdvance ?: WorldSnapshot(game)
        var stopReason: String? = null
        var council: Council? = null
        val ruledCivs = orders.keys.map { name -> game.civilizations.first { it.civName == name } }.filter { it.isAlive() }
        // What each advisor has already been shown; anything new below is worth waking them for
        val warningsSeen = ruledCivs.associate { civ -> civ.civName to warnings(civ).map(::normalised).toSet() }
        val wake = LinkedHashMap<String, List<String>>()

        for (i in 1..turns) {
            val before = WorldSnapshot(game)
            val civSnapshots = ruledCivs.associateWith { diplomacy.noteRequests(it); CivSnapshot(it) }
            for (civ in ruledCivs) applyStandingOrders(civ)
            // The engine cannot pick up a turn it failed partway through (GameInfo.nextTurn moves currentPlayer only at the end), and a second try would play
            // the realms already moved twice: the world goes back to the last save, every court is told, and no turn is played until the host is restarted
            try { game.nextTurn() } catch (ex: Throwable) { failTurn(ex); throw ApiError(500, "The turn could not be played: $ex. The world was reloaded as it stood before it; restart the host before playing on.") }
            guardedStep("the trail") { trail.record(game) }
            val after = WorldSnapshot(game)
            settling = true
            try {
                guardedStep("the world's events") { recordPublicEvents(before, after) }
                // One realm's bookkeeping going wrong costs that realm's account of the turn, never the others' or the save
                for (civ in ruledCivs) guardedStep("the chancery of ${civ.civName}") {
                    try { recordChancery(civ, civSnapshots[civ]!!) }
                    catch (ex: Exception) { log(civ, "The chancery's account of this turn could not be finished (${ex.javaClass.simpleName}); some of what happened may be missing from it."); throw ex }
                }
                guardedStep("the world's vote") { noteWorldVote(game, ruledCivs) }
            } finally { settling = false }
            guardedStep("the roll of the dead") { units.forgetTheDead(game) }

            for (civ in ruledCivs) {
                val news = chanceryLog(civ, game.turns - 1).map { it["text"] as String }.filter { line -> wakeWorthy.any { it.containsMatchIn(line) } }
                // A warning wakes an advisor once per game: many of them flicker on and off between turns, and every look-in shows them all anyway
                val everSeen = warningsWoken.getOrPut(civ.civName) { HashSet(warningsSeen[civ.civName] ?: emptySet()) }
                val warningsNow = warnings(civ)
                val standingNow = warningsNow.map(::normalised)
                // An acknowledged warning is forgotten once it clears: if it returns, it shows in full again
                orders[civ.civName]?.acknowledged?.retainAll(warningsNow.map(::ackKey).toSet())
                // Deduplicated on the normalised key, sent in the warning's own words (the key reads "The people are unhappy (#)")
                val fresh = warningsNow.filter { everSeen.add(normalised(it)) }
                    .filter { "no defender" in it || "unhappy" in it || "treasury" in it.lowercase() || "no military" in it }
                if (news.isNotEmpty() || fresh.isNotEmpty()) wake[civ.civName] = news + fresh
            }
            val winner = game.majorCivs().firstOrNull { it.victoryManager.hasWon() }
            council = if (winner == null) councilEvent(roundStart, after) else null
            stopReason = if (winner != null) "${winner.civName} has won a ${winner.victoryManager.getVictoryTypeAchieved()} victory" else council?.news
            // The world's history must hear of it too, or the last chapter never knows the story ended. Once: the events are saved with the host, and a restart must not add it again
            if (winner != null && publicEvents.none { (it["text"] as String).startsWith("${winner.civName} has won a ") }) {
                settling = true // it belongs to the turn just played
                try { addPublicEvent("$stopReason.") } finally { settling = false }
            }
            save() // every turn on disk as soon as it is played: a turn that fails later goes back to this one, not to the start of the advance
            if (stopReason != null || (wakeOnEvents && wake.isNotEmpty())) break
        }
        worldAtLastAdvance = WorldSnapshot(game)
        warsDeclared.clear() // whatever was declared before or during this advance is old news to the next
        save()
        backup()
        return mapOf(
            "fromTurn" to startTurn,
            "fromYear" to yearText(startTurn),
            "turn" to game.turns,
            "year" to yearText(game.turns),
            "stoppedEarly" to stopReason,
            // The first war of the council in fields, for the orchestrator to frame each court's part without parsing the sentence; every war under "wars"
            "declaredBy" to council?.wars?.firstOrNull()?.get("declaredBy"),
            "target" to council?.wars?.firstOrNull()?.get("target"),
            "drawnIn" to (council?.wars?.firstOrNull()?.get("drawnIn") ?: emptyList<Any>()),
            "wars" to (council?.wars ?: emptyList<Any>()),
            "wake" to wake,
            "publicEvents" to publicEvents.filter { (it["turn"] as Int) > startTurn },
            "rankings" to rankings()
        )
    }

    /** Set when a turn could not be played: the world was reloaded from the last save, and nothing more is played until the host is restarted */
    private var brokenTurn: String? = null

    /** A turn the engine failed partway through: back to the last save on disk, each court told, the host stopped from playing on */
    private fun failTurn(ex: Throwable) {
        ex.printStackTrace()
        val failedAt = game().turns
        brokenTurn = "${ex.javaClass.simpleName} at turn $failedAt"
        // Then the host leaves, once the answer is on its way: start.sh's keep loop starts it afresh from the save on disk, and the orchestrator retries
        Thread { Thread.sleep(2000); System.err.println("Exiting after a failed turn ($brokenTurn); the keep loop restarts the host from the last save."); kotlin.system.exitProcess(3) }.apply { isDaemon = true }.start()
        resetHostState()
        load()
        for (name in orders.keys) game().civilizations.firstOrNull { it.civName == name }?.let { civ ->
            log(civ, "The turn could not be played (${ex.javaClass.simpleName}: ${ex.message}); the world stands as it was before it, and the host waits to be restarted.") }
        save()
    }

    /** One step of the bookkeeping after a turn: a failure is printed and skipped, and the rest - the save above all - goes on */
    private fun guardedStep(what: String, step: () -> Unit) {
        try { step() } catch (ex: Exception) { System.err.println("Bookkeeping step failed ($what): $ex"); ex.printStackTrace() }
    }

    /** Something a ruler must hear about right away, ending the round early */
    /** What stops the clocks for everyone: only a war with a major power - the one thing that needs the ruler's change of course.
     *  A lost city wakes the advisor (there is little a ruler can do in that hour, and the dispatch tells it); a fallen realm is dealt with when the age is done. */
    /** [news] is the sentence for every court; [wars] one entry per war it covers: declaredBy, target, drawnIn (the pact partners pulled in, each with the
     *  partner whose pact it was) and its own line - so a ruler pulled in by its own pact is told so, not that it was struck (audit 2026-10-03) */
    private class Council(val news: String, val wars: List<Map<String, Any?>>)
    private fun councilEvent(roundStart: WorldSnapshot, now: WorldSnapshot): Council? {
        val ruled = orders.keys.filter { it in now.alive }
        fun major(name: String) = game().civilizations.first { it.civName == name }.isMajorCiv()
        // Every new war between a ruled realm and a major power since the round began
        val fresh = (now.wars - roundStart.wars).filter { war -> war.any { it in ruled } && war.all { major(it) } }
        if (fresh.isEmpty()) return null
        // The engine's own record of each: the declaration it began with (a join's is its ally's war), else the bare fact
        val declarations = warsDeclared.filter { !it.joins }
        val byWar = LinkedHashMap<WarRecord, Set<String>>(); val bare = ArrayList<Set<String>>()
        for (pair in fresh) {
            val (first, second) = pair.toList().let { it[0] to it[1] }
            val record = warsDeclared.firstOrNull { it.covers(first, second) }
            val war = if (record == null) null else if (!record.joins) record else declarations.firstOrNull { record.joined(it) }
            if (war == null) bare += pair else byWar.putIfAbsent(war, pair)
        }
        fun names(list: List<String>) = if (list.size <= 1) list.joinToString() else list.dropLast(1).joinToString() + " and " + list.last()
        val wars = byWar.keys.sortedBy { warsDeclared.indexOf(it) }.map { war ->
            val joins = warsDeclared.filter { it.joined(war) }
            val partners = joins.filter { it.byPact }
            val allies = joins.filter { !it.byPact }.groupBy { it.ally!! }
            val line = listOf(told(war).removeSuffix(".")) +
                partners.map { "${it.declarer} came in at ${it.ally}'s side by its pact" } +
                allies.map { (ally, company) -> "${names(company.map { it.declarer })}, $ally's all${if (company.size == 1) "y" else "ies"}, joined the war" }
            mapOf("declaredBy" to war.declarer, "target" to war.target, "drawnIn" to partners.map { mapOf("civ" to it.declarer, "with" to it.ally) }, "news" to line.joinToString("; "))
        } + bare.map { pair ->
            val (ours, theirs) = pair.toList().sortedBy { it !in ruled }
            mapOf("declaredBy" to null, "target" to null, "drawnIn" to emptyList<Any>(), "news" to "$ours is now at war with $theirs")
        }
        return Council(wars.joinToString(". ") { it["news"] as String }, wars)
    }

    private fun rankings() = game().majorCivs().filter { it.isAlive() }
        .sortedByDescending { it.calculateTotalScore() }
        .map { mapOf("civ" to it.civName, "score" to it.calculateTotalScore().toInt(), "cities" to it.cities.size) }

    //endregion
    //region Briefing

    /** How we stand with a power, in a word the war estimate can sort by */
    private fun relationTo(civ: Civilization, other: Civilization): String = when {
        civ.isAtWarWith(other) -> "at war"
        civ.getDiplomacyManager(other)?.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.DefensivePact -> "defensive pact"
        civ.getDiplomacyManager(other)?.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship) == true -> "friend"
        else -> "at peace"
    }

    /** A realm that has fallen has no briefing and no options: the orchestrator's "is no more" paths take it from here */
    private fun ensureStanding(civ: Civilization) {
        if (civ.isDefeated()) throw ApiError(410, "${civ.civName} is no more: it holds no city and no host")
    }

    private fun briefing(civ: Civilization, logSince: Int? = null): Map<String, Any?> {
        ensureStanding(civ)
        for (city in civ.cities) city.cityStats.update() // yields are cached per turn; stale ones showed food 0 and "0 turns left"
        val next = civ.stats.statsForNextTurn
        val turn = civ.gameInfo.turns
        val sites = units.citySites(civ) // the full site search, once: the warnings want it too
        return mapOf(
            "civ" to civ.civName,
            "turn" to turn,
            "year" to yearText(turn),
            // Advisors speak in years, but everything here counts turns - and early on a turn is decades long
            "calendar" to (civ.gameInfo.getYear(1) - civ.gameInfo.getYear(0)).let { years ->
                "One turn is now about $years year${if (years == 1) "" else "s"}. Every figure of turns in this briefing (turnsLeft, deal durations, 'about N turns away') counts turns, not years: ${if (years > 1) "3 turns is about ${3 * years} years. " else ""}Reckon it over before you speak of time, and prefer a loose span ('within a generation', 'a century's work') to an exact count." },
            "era" to civ.getEra().name,
            "wondersBarredFor" to wonderless.toList().ifEmpty { null },
            "waysToWin" to civ.gameInfo.gameParameters.victoryTypes.toList().ifEmpty { listOf("none - this world was made without victory conditions; nobody can build a spaceship or win a vote") },
            "gold" to civ.gold,
            "perTurn" to mapOf("gold" to next.gold.toInt(), "science" to next.science.toInt(), "culture" to next.culture.toInt()),
            "goldBreakdown" to goldBreakdown(civ),
            "happiness" to civ.getHappiness(),
            "happinessBreakdown" to civ.stats.getHappinessBreakdown().filter { it.value != 0f }.mapValues { it.value.toInt() },
            // Warnings the advisor acknowledged fold to their first clause while they stand
            *warnings(civ, sites).partition { ackKey(it) in (orders[civ.civName]?.acknowledged ?: emptyList<String>()) }.let { (acknowledged, shown) ->
                arrayOf("warnings" to shown, "warningsAcknowledged" to acknowledged.map { firstClause(it) }.ifEmpty { null }) },
            "victory" to civ.gameInfo.civilizations.firstOrNull { it.victoryManager.hasWon() }.let { winner -> mapOf("winner" to winner?.civName, "victoryType" to winner?.victoryManager?.getVictoryTypeAchieved(),
                "note" to if (winner != null) "The game is over." else "Nobody has won yet. ${victoriesInPlay(civ)}",
                "diplomatic" to diplomaticFacts(civ), "cultural" to culturalFacts(civ)) },
            "researching" to civ.tech.currentTechnologyName(),
            "researchQueue" to civ.tech.techsToResearch,
            "techsResearched" to civ.tech.techsResearched.size,
            "techsKnown" to civ.tech.techsResearched.sorted().joinToString(", "),
            "spaceship" to civ.gameInfo.ruleset.victories["Scientific"]?.let { victory -> victory.requiredSpaceshipPartsAsCounter.takeIf { it.isNotEmpty() && "Scientific" in civ.gameInfo.gameParameters.victoryTypes }?.let { needed ->
                val have = civ.victoryManager.currentsSpaceshipParts
                mapOf("parts" to needed.keys.associateWith { "${have[it]} of ${needed[it]}${civ.gameInfo.ruleset.units[it]?.requiredTech?.takeUnless { tech -> civ.tech.isResearched(tech) }?.let { tech -> " (needs $tech)" } ?: ""}" },
                    "others" to civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.mapNotNull { other ->
                        val parts = other.victoryManager.currentsSpaceshipParts
                        val built = parts.values.sum()
                        val apollo = other.hasUnique(com.unciv.models.ruleset.unique.UniqueType.EnablesConstructionOfSpaceshipParts)
                        // What is still to come, and the learning each missing part waits on: the timing a rival can judge (facts, no reckoning of turns)
                        val missing = needed.entries.filter { parts[it.key] < it.value }.joinToString { (part, count) -> (if (count - parts[part] > 1) "${count - parts[part]} $part" else part) +
                            (civ.gameInfo.ruleset.units[part]?.requiredTech?.let { tech -> ", which ${if (count - parts[part] > 1) "need" else "needs"} $tech (${if (other.tech.isResearched(tech)) "known to them" else "not yet known to them"})" } ?: "") }
                        if (built == 0 && !apollo) null else "${other.civName}: ${if (apollo) "the Apollo Program stands, " else ""}$built of ${needed.values.sum()} parts" + (if (missing.isNotEmpty()) "; missing $missing" else "") }.toList(),
                    "apollo" to civ.hasUnique(com.unciv.models.ruleset.unique.UniqueType.EnablesConstructionOfSpaceshipParts),
                    "note" to "Parts are units: queue them by name in any city once the Apollo Program stands${civ.gameInfo.ruleset.buildings["Apollo Program"]?.requiredTech?.takeUnless { civ.tech.isResearched(it) }?.let { " (a wonder that needs $it)" } ?: ""} and the techs are known (each needs Aluminum while it is built and on its way - the Aluminum is freed once the part is added to the ship); a finished part walks to the capital and is added to the ship by the officials. They CANNOT be bought with gold - saving gold for them is wasted. The ship launches, and the game is won, when every part is added.") } },
            "policies" to civ.policies.getAdoptedPolicies().toList(),
            "culture" to mapOf("stored" to civ.policies.storedCulture, "neededForNextPolicy" to civ.policies.getCultureNeededForNextPolicy(),
                "turnsToNextPolicy" to next.culture.toInt().let { perTurn -> val short = civ.policies.getCultureNeededForNextPolicy() - civ.policies.storedCulture
                    if (short <= 0) "now" else if (perTurn <= 0) "never, at no culture a turn" else "about ${(short + perTurn - 1) / perTurn} at $perTurn culture a turn" },
                "costOfEachCity" to civ.cities.count { !it.isPuppet }.let { own -> "each city of ours beyond the first that is not a puppet adds ${policyCostPerCity(civ)} to the culture cost of every policy; we have $own such cities. A puppet adds nothing until it is annexed" }),
            "militaryStrength" to civ.getStatForRanking(RankingType.Force),
            "warEstimate" to (if (civ.isAtWar() || orders[civ.civName]?.warAim != null) warEstimate(civ)
                else mapOf("note" to "At peace: the chancery's reckoning of every rival city against our engines is in get_options.warEstimate - read it before any talk of war.")),
            // Workers left to the officials are one count, not a line each: they are seldom ordered, and the lines cost every look-in
            "units" to civ.units.getCivUnits().toList().partition { it.isCivilian() && it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.BuildImprovements) && units.leftToOfficials(it) }.let { (workers, rest) ->
                rest.map { units.describeUnit(civ, it) } + listOfNotNull(workers.size.takeIf { it > 0 }?.let { "$it worker${if (it == 1) "" else "s"} at work, automated" }) },
            // The empty list says what the officials' settler gate says (RulerUnits.hasLandForACity), so they are never told there is no land while they train settlers
            "citySites" to sites.ifEmpty { listOf(if (units.hasLandForACity(civ)) "(none we can name: no explored site within reach is free - but unexplored ground within reach may yet hold one, so the officials may still train settlers; explore it, or put Settler on neverBuild)"
                else "(none: no land is left within our reach${if (civ.tech.unitsCanEmbark) ", on our shores or across the sea" else " on the lands we live on, and our people cannot yet cross the sea"} - the officials train no settlers while it stays so)") },
            "cities" to civ.cities.map { city ->
                val stats = city.cityStats.currentCityStats
                mapOf("name" to city.name, "pop" to city.population.population, "capital" to city.isCapital(),
                    "puppet" to city.isPuppet.takeIf { it }, "beingRazed" to city.isBeingRazed.takeIf { it }, "coastal" to city.isCoastal().takeIf { it },
                    "buildings" to city.cityConstructions.getBuiltBuildings().map { it.name }.sorted(),
                    "buildQueue" to city.cityConstructions.constructionQueue,
                    "turnsLeft" to city.cityConstructions.currentConstructionName().let { current ->
                        if (current.isEmpty()) null
                        else if (com.unciv.models.ruleset.PerpetualConstruction.isNamePerpetual(current)) "ongoing (production turned into $current)"
                        else city.cityConstructions.turnsToConstruction(current).coerceAtLeast(1) },
                    // The food the city's turn will add, rounded as the engine rounds it - the starving warning reads the same figure
                    "production" to stats.production.toInt(), "foodSurplus" to city.foodForNextTurn(),
                    "growth" to when {
                        civ.getHappiness() <= -10 -> "none: the people are too unhappy"
                        civ.getHappiness() < 0 && city.foodForNextTurn() <= 0 -> "stalled: unhappiness cuts growth by three quarters"
                        (city.cityConstructions.getCurrentConstruction() as? com.unciv.models.ruleset.unit.BaseUnit)?.isCityFounder() == true -> "held while the Settler is built"
                        else -> null },
                    "unhappiness" to city.cityStats.happinessList.values.filter { it < 0 }.sum().toInt(),
                    "focus" to city.getCityFocus().name,
                    "health" to city.health, "garrison" to city.getCenterTile().militaryUnit?.name)
            },
            "threats" to threats(civ),
            "strategicResources" to strategicResources(civ),
            "religion" to religion(civ),
            "knownCivs" to diplomacy.relations(civ),
            "log" to condensed(chanceryLog(civ, logSince ?: (turn - 12)).map { "T${it["turn"]}: ${it["text"]}" }),
            "peaceOffers" to diplomacy.peaceOffers(civ),
            "tradeOffers" to diplomacy.tradeOffers(civ) + diplomacy.friendshipOffers(civ),
            "demands" to diplomacy.demands(civ),
            "dealsLedger" to diplomacy.ledger(civ),
            "standingOrders" to orders[civ.civName]?.toMap(civ)
        )
    }

    /** A soldier who would not hold a wall in this age: far behind our best arms, a scout, or a siege piece (which cannot defend itself) */
    private fun poorDefender(civ: Civilization, unit: com.unciv.logic.map.mapunit.MapUnit): Boolean {
        val best = civ.units.getCivUnits().filter { !it.baseUnit.isProbablySiegeUnit() }.maxOfOrNull { it.baseUnit.strength.coerceAtLeast(it.baseUnit.rangedStrength) } ?: 0
        return unit.baseUnit.strength.coerceAtLeast(unit.baseUnit.rangedStrength) * 2 < best || unit.name == "Scout" || unit.baseUnit.isProbablySiegeUnit()
    }

    /** The chancery's own reckoning of every rival city we have seen, against what we could bring to it: the sober part of any talk of war */
    private fun warEstimate(civ: Civilization): Map<String, Any?> {
        val soldiers = civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit }.toList()
        val siege = soldiers.filter { it.baseUnit.isProbablySiegeUnit() }
        val melee = soldiers.filter { it.baseUnit.isMelee() }
        // A foot soldier standing in a city of ours holds it; it is not free to walk into another (Rome broke Uxmal's walls with all six of its infantry on garrison, Run 20)
        val inGarrison = melee.count { it.getTile().isCityCenter() && it.getTile().getOwner() == civ }
        val aircraft = civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isAirUnit() }
        val warships = civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isWaterUnit }.toList()
        val ourLands = civ.cities.map { it.getCenterTile().getContinent() }.toSet()
        val bestEngine = siege.maxByOrNull { it.baseUnit.rangedStrength }
        val bestFoot = melee.maxByOrNull { it.baseUnit.strength }
        // A city heals this much every turn (the engine's CityTurnManager): what breaks it is what all our engines take off in a turn beyond that
        val healing = 20
        // The engine's own combat sum, at full health and even luck: what one volley of our best engine takes off a city, and what the city does to our best foot soldier
        fun volley(unit: com.unciv.logic.map.mapunit.MapUnit, city: City) = com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
            com.unciv.logic.battle.MapUnitCombatant(unit), com.unciv.logic.battle.CityCombatant(city), city.getCenterTile(), 0.5f)
        fun cityHits(unit: com.unciv.logic.map.mapunit.MapUnit, city: City) = com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
            com.unciv.logic.battle.CityCombatant(city), com.unciv.logic.battle.MapUnitCombatant(unit), unit.getTile(), 0.5f)
        val cities = civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.flatMap { other -> other.cities.filter { it.getCenterTile().isExplored(civ) } }
            .map { city ->
                val defence = com.unciv.logic.battle.CityCombatant(city).getCityStrength()
                // The combat sum runs away against walls near zero (150169392 for a city at 1 health): a volley takes at most what the walls can hold,
                // and what it takes from them now at most what is left of them
                val fullVolley = (bestEngine?.let { volley(it, city) } ?: 0).coerceIn(0, com.unciv.logic.battle.CityCombatant(city).getMaxHealth())
                val perVolley = fullVolley.coerceAtMost(city.health)
                val volleysToBreak = if (perVolley > 0) (city.health + perVolley - 1) / perVolley else null
                // Whether all our engines together, each with its own volley, outpace the healing at all. How many turns a siege takes is not reckoned: engines
                // move, set up, are shot at and fall back, and the one figure that tried said 1-2 turns where Run 20's sieges took 5 to 13
                val allEnginesTake = siege.sumOf { (volley(it, city)).coerceIn(0, com.unciv.logic.battle.CityCombatant(city).getMaxHealth()) }
                val outpaced = allEnginesTake > healing
                val hitOnFoot = bestFoot?.let { cityHits(it, city) }
                // What stands about the city within three tiles, on the tiles we can see: its soldiers, and whatever shoots at aircraft (a SAM, a fighter on
                // its field, a building that intercepts). Of the tiles we cannot see we say nothing - the walls alone misled three sieges in Run 11
                val near = city.getCenterTile().getTilesInDistance(3).filter { it.isVisible(civ) }.toList()
                val defenders = near.filter { it != city.getCenterTile() }.flatMap { it.getUnits() }.filter { it.civ == city.civ && it.isMilitary() && it.baseUnit.isLandUnit }
                val defendersStrength = defenders.sumOf { it.baseUnit.strength.coerceAtLeast(it.baseUnit.rangedStrength) }
                val antiAirUnits = near.flatMap { it.getUnits() }.filter { it.civ == city.civ && it.interceptChance() > 0 }
                val antiAir = antiAirUnits.size + city.cityConstructions.getBuiltBuildings().count { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ChanceInterceptAirAttacks) }
                fun s(n: Int) = if (n == 1) "" else "s"
                val beside = when {
                    near.isEmpty() -> "what stands beside them is beyond our sight"
                    defenders.isEmpty() && antiAir == 0 -> "we see nothing standing beside them"
                    else -> listOfNotNull(
                        defenders.takeIf { it.isNotEmpty() }?.let { "the ${it.size} defender${s(it.size)} beside them (strength $defendersStrength together)" },
                        antiAir.takeIf { it > 0 }?.let { "$it anti-air (${(antiAirUnits.map { u -> u.name } + if (antiAir > antiAirUnits.size) listOf("the city's own") else emptyList()).distinct().joinToString(", ")})" +
                            (if (aircraft > 0) " against our $aircraft aircraft" else "") }
                    ).joinToString(" and ") + (if (defenders.size + antiAir == 1) " is" else " are") + " another matter"
                }
                // The approaches: how many land tiles touch the city, and how many of those an enemy soldier stands on
                val approaches = city.getCenterTile().neighbors.filter { it.isLand && !it.isImpassible() }.toList()
                val heldApproaches = approaches.count { it.militaryUnit != null && it.militaryUnit!!.civ != civ }
                val gate = when {
                    approaches.isEmpty() -> "no land approach at all - only from the sea"
                    heldApproaches == approaches.size -> "every land approach (${approaches.size}) is held by an enemy soldier; the rest is mountain or sea - engines can still bombard from two tiles off, but nothing walks in until the approaches are won"
                    approaches.size <= 2 -> "only ${approaches.size} land approaches - a narrow gate, easily plugged"
                    else -> "${approaches.size} land approaches, $heldApproaches held"
                }
                // A city on a landmass none of ours stands on: the sums below count soldiers who must first cross the water, and the straight line is no road
                val acrossTheSea = if (city.getCenterTile().getContinent() in ourLands) null
                    else "across the sea from every city of ours: " + (if (!civ.tech.unitsCanEmbark) "our soldiers cannot yet embark, so none can reach it"
                        else "our soldiers embark and cross on their own, and at sea an embarked soldier cannot attack and defends poorly") +
                        "; " + (if (warships.isEmpty()) "we have no warships to escort them" else "we have ${warships.size} warship${s(warships.size)} (${warships.groupingBy { it.name }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}) to escort them") +
                        ". The reckoning below is of the walls alone, once our engines stand ashore before them"
                // The march: from the nearest of our engines (or towns) to the walls, at the pace of our slowest engine - the walls are judged as they stand now,
                // and a defender with warning raises and buys defenders while we march (Run 19: 1 of 7 such reckonings held)
                // Only engines and towns on the city's own landmass march to it; from anywhere else the way is water
                val land = city.getCenterTile().getContinent()
                val enginesAcross = siege.isNotEmpty() && siege.none { it.getTile().getContinent() == land }
                val from = siege.filter { it.getTile().getContinent() == land }.minByOrNull { it.getTile().aerialDistanceTo(city.getCenterTile()) }?.getTile()
                    ?: civ.cities.filter { it.getCenterTile().getContinent() == land }.minByOrNull { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) }?.getCenterTile()
                val pace = siege.minOfOrNull { it.getMaxMovement() }?.coerceAtLeast(1) ?: 2
                // Across the water the straight line is no road: no march in turns is given there
                val marchTurns = if (acrossTheSea != null || enginesAcross) null else from?.let { ((it.aerialDistanceTo(city.getCenterTile()) - 2).coerceAtLeast(0) + pace - 1) / pace }
                val march = when {
                    acrossTheSea != null -> "the way there is across the sea, and how long the crossing takes cannot be reckoned from here"
                    enginesAcross -> "our engines all stand across the sea from it, so they must cross the water first, and how long that takes cannot be reckoned from here"
                    marchTurns == null -> ""
                    marchTurns == 0 -> "our engines already stand within reach of the walls"
                    else -> "the march there is about $marchTurns turn${s(marchTurns)} at the pace of our slowest engine, and the defender will raise and buy defenders in that time"
                }
                val verdict = (acrossTheSea?.let { "$it - " } ?: "") + when {
                    bestEngine == null -> "no siege engines - the walls cannot be broken"
                    !outpaced -> "our ${siege.size} engine${s(siege.size)} together take less off the walls in a turn ($allEnginesTake) than the $healing they heal - what we have cannot break them"
                    else -> "$volleysToBreak volley${s(volleysToBreak ?: 0)} of our best engine (${bestEngine.name}) to break the walls as they stand now, which heal $healing a turn" +
                        (if (march.isNotEmpty()) "; $march" else "") + "; $beside" +
                        (if (melee.size - inGarrison == 0) "; and every foot soldier of ours holds a city, so none is free to walk in once the walls are down" else "")
                }
                mapOf("city" to city.name, "of" to city.civ.civName, "ourRelation" to relationTo(civ, city.civ), "defence" to defence, "health" to city.health, "walls" to city.cityConstructions.builtBuildings.any { "Walls" in it || "Castle" in it },
                    "garrison" to city.getCenterTile().militaryUnit?.name, "tilesFromOurNearestCity" to civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) },
                    "acrossTheSea" to (acrossTheSea != null).takeIf { it },
                    "oneVolleyOfOurBestEngineTakes" to perVolley, "volleysToBreak" to volleysToBreak, "healsPerTurn" to healing, "marchTurns" to marchTurns,
                    "defendersWithin3Tiles" to if (near.isEmpty()) null else defenders.size, "defendersStrength" to if (near.isEmpty()) null else defendersStrength, "antiAirWithin3Tiles" to if (near.isEmpty()) null else antiAir,
                    "cityShotAtOurBestFootSoldierTakes" to hitOnFoot, "approaches" to gate, "verdict" to verdict)
            // Enemies first, then the rest: an ally's capital once headed an at-war briefing as the softest walls (audit 2026-10-03)
            }.sortedWith(compareBy({ it["ourRelation"] != "at war" }, { it["volleysToBreak"] as Int? ?: 999 }))
        val unseen = civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.associate { other -> other.civName to other.cities.count { !it.getCenterTile().isExplored(civ) } }.filterValues { it > 0 }
        return mapOf(
            "ourBestEngine" to bestEngine?.name, "ourBestFootSoldier" to bestFoot?.name, "siegeEngines" to siege.size, "footSoldiers" to (melee.size - inGarrison), "footSoldiersHoldingOurCities" to inGarrison.takeIf { it > 0 },
            "ranged" to soldiers.count { it.baseUnit.isRanged() && !it.baseUnit.isProbablySiegeUnit() }, "ourAircraft" to aircraft, "ourWarships" to warships.size,
            "citiesWeHaveSeen" to cities,
            "citiesNeverSeen" to unseen,
            "whoElseWouldFight" to civ.getKnownCivs().filter { it.isMajorCiv() && !civ.isAtWarWith(it) }.associate { target -> target.civName to
                (civ.gameInfo.civilizations.filter { ally -> ally != target && ally != civ && ally.isAlive() && ((ally.isMajorCiv() && ally.getDiplomacyManager(target)?.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.DefensivePact) || (ally.isCityState && ally.allyCiv == target)) }
                    .map { it.civName + (if (it.isCityState) " (city-state ally)" else " (defensive pact)") }) }.filterValues { it.isNotEmpty() },

            "howToRead" to "Sums from the chancery's own reckoning of battle, at full health and even luck. A city is taken by breaking its health with engines and then walking a foot soldier in; it heals each turn, shoots one volley a turn at anything within two tiles, and its garrison and walls do the rest. Defenders and anti-air are counted within three tiles of the city, on the tiles we can see this turn. Bowmen and crossbows barely scratch walls. A city across the sea is reached only by embarking, and its verdict says so first; tilesFromOurNearestCity is a straight line over land and water alike. footSoldiers are those free to march: one standing in a city of ours holds it. What we have never seen we cannot judge - say so.")
    }

    /** The log as a look-in should read it: no bookkeeping echoes, repeats folded, and never more than the last sixty lines */
    private fun condensed(lines: List<String>): List<String> {
        val kept = lines.filterNot { "The treasury now holds" in it }
        val folded = ArrayList<String>()
        var last: String? = null; var count = 0
        for (line in kept) {
            val body = line.substringAfter(": ")
            if (body == last) { count++; folded[folded.size - 1] = "${folded.last().substringBefore(" (x")} (x$count)" }
            else { folded += line; last = body; count = 1 }
        }
        return if (folded.size <= 60) folded else listOf("(${folded.size - 60} earlier lines omitted - get_log shows them)") + folded.takeLast(60)
    }

    /** A warning with its numbers struck out: what tells one warning from another as the figures in it change */
    private fun normalised(warning: String) = warning.replace(Regex("-?\\d+"), "#")
    /** A warning's first clause, as the folded list shows it: up to its first colon, semicolon, dash or full stop */
    private fun firstClause(warning: String) = warning.split(Regex(": |; | - |\\. ")).first().trim().removeSuffix(".")
    /** Counts struck out ("N"), a unit's "#number" kept whole */
    private fun countsStruck(text: String) = text.replace(Regex("(?<!#)-?\\b\\d+"), "N")
    /** What an acknowledged warning is known by: its first clause, with the lists in brackets and the counts struck out - so a changing tail, unit list or
     *  figure never brings it back (Greece acknowledged one warning ten times in twenty turns) - but a unit's #number kept, so one prophet is never another */
    private fun ackKey(warning: String) = countsStruck(firstClause(warning).replace(Regex("\\s*\\([^)]*\\)"), "")).replace(Regex("\\s+"), " ").trim().lowercase()

    private fun warnings(civ: Civilization, citySites: List<String>? = null): List<String> {
        val warnings = ArrayList<String>()
        if (civ.getHappiness() < 0) warnings += "The people are unhappy (${civ.getHappiness()}): ${if (civ.getHappiness() <= -10) "no city grows at all" else "every city's growth is cut by three quarters"} while the mood is below zero, and production suffers - farming for food does little until the mood is mended. " +
            "The mood is a sum, and happinessBreakdown shows its parts: every citizen costs one and every city a few; each distinct luxury gives four, and buildings, policies and wonders what they say (the build lists mark a building's mood as +N). A wide, populous realm settles a little below zero and growth is its brake; what raises it is a luxury not yet ours, a mood building not yet raised, a policy or wonder that gives it, a belief of our religion, or fewer and smaller cities."
        if (civ.tech.currentTechnologyName() == null) warnings += "Research is idle - give research goals."
        val standing = orders[civ.civName]
        val goalsLeft = standing?.research?.count { !reached(civ, it) } ?: 0
        if (standing != null && goalsLeft == 0) warnings += "All research goals are reached - the officials pick techs themselves until new goals are given."
        else if (goalsLeft == 1) warnings += "Only one research goal left - add more."
        for (city in civ.cities) {
            val defender = city.getCenterTile().militaryUnit
            // A garrison march that failed is told with the warning, so the ruler knows the standing order has not mended it (the tail is no part of the warning's key)
            val missed = units.garrisonMiss(civ, city.getCenterTile())?.let { "; $it" } ?: ""
            if (defender == null) warnings += "${city.name} has no defender in it$missed."
            else if (poorDefender(civ, defender))
                warnings += "${city.name} is only held by ${if (defender.name.first().lowercaseChar() in "aeiou") "an" else "a"} ${defender.name} - weak for this age" +
                    (if (orders[civ.civName]?.garrisonEveryCity == true && missed.isEmpty()) "; by your standing garrison order a stronger soldier of ours relieves it when one is free" else "") + "$missed."
        }
        for (unit in civ.units.getCivUnits()) if (unit.isGreatPerson() && units.orders[unit.id]?.action == "hold")
            warnings += "${unit.name} #${unit.id} ${describeWhere(civ, unit.getTile())} awaits orders."
        if (civ.getHappiness() in 0..2) warnings += "The people's mood is thin (+${civ.getHappiness()}): one more city or a lost luxury tips them into unrest."
        // The rearm order's own test (RulerUnits.newerArms), so the warning never sends the advisor to an order that then finds no one to re-arm
        val oldArms = civ.units.getCivUnits().filter { it.baseUnit.isLandUnit && units.newerArms(it).isNotEmpty() }.toList()
        val (outdated, waiting) = oldArms.partition { unit -> units.newerArms(unit).any { unit.upgrade.canUpgrade(it) } }
        val rearmMode = orders[civ.civName]?.officialsRearm ?: "war"
        // Re-arming is done only on our own land and ashore: soldiers abroad or at sea are said apart, and never sent to the order alone
        val away = outdated.filter { it.isEmbarked() || it.getTile().getOwner() != civ }
        // Each re-arming passes the resource test alone; all of them together may need more than we have (6 to re-arm with 4 Aluminum spare, Run 20)
        val needed = HashMap<String, Int>()
        for (unit in outdated) units.newerArms(unit).firstOrNull { unit.upgrade.canUpgrade(it) }?.getResourceRequirementsPerTurn(unit.cache.state)?.forEach { (name, amount) ->
            needed[name] = (needed[name] ?: 0) + amount - (unit.baseUnit.getResourceRequirementsPerTurn(unit.cache.state)[name] ?: 0) }
        val short = needed.filter { (name, amount) -> amount > (civ.getCivResourcesByName()[name] ?: 0) }
        if (outdated.size >= 3) warnings += "${outdated.size} soldiers carry arms of an earlier age (${outdated.groupingBy { it.name }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}): they cost upkeep and would not hold a wall. " +
            short.entries.joinToString("") { (name, amount) -> "Re-arming them all needs $amount $name and we have ${civ.getCivResourcesByName()[name] ?: 0} spare, so not all can be. " } +
            (if (away.size == outdated.size) "All of them stand abroad or at sea, where none can be re-armed: bring them home to our own land, or disband the worst." else when (rearmMode) {
                "always" -> "Gold above your reserve lets the officials re-arm them; or disband the worst."
                "never" -> "The officials re-arm nothing on their own: order rearm to do it, or disband the worst."
                else -> if (civ.getKnownCivs().any { it.isMajorCiv() && civ.isAtWarWith(it) }) "We are at war, so gold above your reserve lets the officials re-arm them; or disband the worst."
                    else "The officials re-arm only in war: order rearm to do it now, or disband the worst."
            } + (if (away.isNotEmpty()) " ${away.size} of them ${if (away.size == 1) "stands" else "stand"} abroad or at sea, and can be re-armed only once back on our own land." else ""))
        if (waiting.size >= 3) warnings += "${waiting.size} soldiers (${waiting.groupingBy { it.name }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}) carry old arms that gold alone cannot replace: the newer arms need ${waiting.flatMap { units.newerArms(it).mapNotNull { to -> to.requiredResource } }.distinct().joinToString(" or ")}, which we lack."
        if (!units.hasLandForACity(civ)) for (unit in civ.units.getCivUnits()) if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity))
            warnings += "${unit.name} #${unit.id} has nowhere to go: no land is left for a new city within our reach${if (civ.tech.unitsCanEmbark) ", on our shores or across the sea" else ""}. It costs upkeep - disband it, or keep it and know why."
        if (civ.policies.canAdoptPolicy()) warnings += "A policy can be adopted but none of the preferred branches has one available - add a branch to the policy order."
        if (civ.units.getCivUnits().any { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity) } && (citySites ?: units.citySites(civ)).isEmpty())
            warnings += "We have a Settler but no free land for a city within reach - every site is too close to an existing city or unexplored. Explore further, or disband/keep the Settler."
        for (city in civ.cities) {
            if (city.cityConstructions.constructionQueue.isEmpty()) warnings += "${city.name} has an empty build queue."
            if (city.foodForNextTurn() < 0) warnings += "${city.name} is starving."
        }
        if (civ.gold < 0 || civ.gold + civ.stats.statsForNextTurn.gold * 5 < 0) warnings += "The treasury is running dry (${civ.gold} gold, ${civ.stats.statsForNextTurn.gold.toInt()} per turn)."
        if (civ.units.getCivUnits().none { !it.isCivilian() }) warnings += "We have no military units at all."
        // A settler that has stood still for ten turns or more: held by order, or left to officials who found no site. Said with the price of one more city, so the wait is a weighed choice
        for (settler in civ.units.getCivUnits().filter { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity) }) {
            val waited = units.turnsStill(settler)
            if (waited < 10) continue
            val order = units.orders[settler.id]
            val breakdown = civ.stats.getHappinessBreakdown()
            val perCity = if (civ.cities.isEmpty()) 0f else -(breakdown["Cities"] ?: 0f) / civ.cities.size
            val population = civ.cities.sumOf { it.population.population }
            val perCitizen = if (population == 0) 1f else -(breakdown["Population"] ?: 0f) / population
            warnings += "${settler.name} #${settler.id} has waited $waited turns ${describeWhere(civ, settler.getTile())}, ${if (order != null) "held by your ${order.action} order" else "left to the officials, who found it no site"}. " +
                "It costs upkeep meanwhile. One more city would cost about ${Math.round(perCity + perCitizen)} contentment at first (the people stand at ${if (civ.getHappiness() >= 0) "+" else ""}${civ.getHappiness()}); found one, disband it, or keep it and know why."
        }
        // A spaceship part that could be built but is in no city's queue: the ruler's own binding queues keep the officials from adding it
        for (part in civ.gameInfo.ruleset.units.values.filter { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.SpaceshipPart) }) {
            val buildable = civ.cities.filter { part.isBuildable(it.cityConstructions) }
            if (buildable.isEmpty() || civ.cities.any { part.name in it.cityConstructions.constructionQueue } || civ.units.getCivUnits().any { it.name == part.name }) continue
            val stored = civ.cities.map { it to it.cityConstructions.getWorkDone(part.name) }.filter { it.second > 0 }
            warnings += "${part.name} can be built and no city is building it" + (stored.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString { (city, done) -> "${city.name} has ${done} stored" }})" } ?: "") + ". Queue it by name: the officials never add a part to a queue you gave them."
        }
        // Cities joined to the capital only across another power's land: an Open Borders pact that lapses cuts them off
        val connected = civ.cache.citiesConnectedToCapitalToMediums.keys.filter { it.civ == civ && !it.isCapital() }
        if (connected.isNotEmpty()) {
            val onOurOwn = com.unciv.logic.civilization.transients.CapitalConnectionsFinder(civ, withoutPacts = true).find().keys
            val hanging = connected.filter { it !in onOurOwn }
            if (hanging.isNotEmpty()) {
                val pacts = civ.diplomacy.values.filter { it.hasOpenBorders && it.otherCiv.isMajorCiv() }.map { manager ->
                    manager.otherCivName + (manager.trades.flatMap { it.ourOffers + it.theirOffers }.filter { it.name == "Open Borders" }.maxOfOrNull { it.duration }?.let { " (ends in $it turns)" } ?: "") }
                warnings += "${hanging.joinToString { it.name }} ${if (hanging.size == 1) "is" else "are"} joined to the capital only by way of foreign land, under Open Borders with ${pacts.joinToString(" and ").ifEmpty { "a neighbour" }}. When that pact lapses ${if (hanging.size == 1) "it is" else "they are"} cut off until it is renewed: keep it renewed, or build a road of our own."
            }
        }
        noShipAtSea(civ)?.let { warnings += it }
        return warnings
    }

    /** A realm that has not met every great power finds the rest beyond the water only by sea, our land units exploring on foot: said when no warship
     *  of ours is afloat and a coastal city of ours could build one (on an archipelago in Run 18 the officials built only Work Boats) */
    private fun noShipAtSea(civ: Civilization): String? {
        val game = civ.gameInfo
        if (game.tileMap.mapParameters.type == com.unciv.logic.map.MapType.pangaea) return null // one landmass: every people can be walked to, and the line would only press ships on a court that needs none
        val others = game.civilizations.count { it.isMajorCiv() && it.isAlive() && it != civ }
        val met = civ.getKnownCivs().count { it.isMajorCiv() && it.isAlive() }
        if (met >= others) return null
        // A Trireme and its kind keep to the coast: what crosses the open sea is the first ship without that bar (Songhai's Trireme explored its coasts and met nobody, Run 20)
        val coastBound = { unit: com.unciv.models.ruleset.unit.BaseUnit -> unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CannotEnterOcean) }
        val firstOceanGoing = game.ruleset.units.values.filter { it.isWaterUnit && it.isMilitary && !coastBound(it) && it.uniqueTo == null }.minByOrNull { it.cost }
        val oceanShip = firstOceanGoing?.let { "the ${it.name}" + (it.requiredTech?.let { tech -> if (civ.tech.isResearched(tech)) ", which we can build" else ", which needs $tech" } ?: "") }
        val ocean = oceanShip?.let { "the first ship that can cross the open sea is $it" } ?: ""
        val ships = civ.units.getCivUnits().filter { it.baseUnit.isWaterUnit && it.isMilitary() }.toList()
        if (ships.any { !coastBound(it.baseUnit) }) return null
        if (ships.isNotEmpty()) return if (ships.any { it.isExploring() } || ocean.isEmpty()) null
            else "Our ships keep to the coast, and we have met $met of the $others other great powers: a ${ships.first().name} and its kind cannot cross the open sea; the first that can is $oceanShip."
        val canBuild = civ.cities.filter { it.isCoastal() }.flatMap { city -> game.ruleset.units.values.filter { it.isWaterUnit && it.isMilitary && it.isBuildable(city.cityConstructions) } }.distinct()
        if (canBuild.isEmpty()) return null
        val first = canBuild.sortedBy { it.cost }.first()
        return "We have no ship at sea, and have met $met of the $others other great powers: on foot our people find only the land they can walk to, and lands beyond the water are found only by ships - " +
            "${first.name} or better, ordered to explore" + (if (coastBound(first) && ocean.isNotEmpty()) ". A ${first.name} keeps to the coast; $ocean" else "") + "."
    }

    /** Barbarian camps we can see and foreign soldiers close to our cities */
    /** A belief in words: its name and what it gives, the rulebook's brackets struck out */
    private fun beliefInWords(belief: com.unciv.models.ruleset.Belief) = "${belief.name}: " + belief.uniques.joinToString("; ") {
        it.replace(Regex("\\[([^\\]]*)\\]"), "$1").replace(Regex("\\s*<([^>]*)>"), " ($1)") }

    /** Faith and religion, facts only: the officials run it (the Faith weight tilts them) unless a Great Prophet or Missionary is ordered to act */
    private fun religion(civ: Civilization): Map<String, Any?> {
        val manager = civ.religionManager
        val ours = manager.religion
        val pantheon = ours?.getBeliefs(com.unciv.models.ruleset.BeliefType.Pantheon)?.toList().orEmpty()
        val founded = ours?.takeIf { it.isMajorReligion() }
        fun named(religion: com.unciv.models.Religion) = if (religion == ours) (if (religion.isMajorReligion()) "our ${religion.getReligionDisplayName()}" else "our pantheon")
            else "${religion.getReligionDisplayName()} (${religion.foundingCiv.civName}'s)"
        val cities = civ.cities.map { city ->
            val majority = city.religion.getMajorityReligion()
            // The religion pressing hardest on the city from its neighbours: where its people are drifting
            val pull = city.religion.getPressuresFromSurroundingCities().filter { it.value > 0 && it.key != majority?.name }.maxByOrNull { it.value }
                ?.let { civ.gameInfo.religions[it.key] }
            "${city.name}: ${majority?.let { named(it) } ?: "no religion"}" + (pull?.let { ", drawn toward ${named(it)} by its neighbours" } ?: "")
        }
        val missionaries = civ.units.getCivUnits().filter { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion) || it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion) }
            .map { unit -> "${unit.name} #${unit.id}" + (unit.religion?.let { " (of ${civ.gameInfo.religions[it]?.let(::named) ?: it})" } ?: "") + ": " + listOfNotNull(
                if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion) && manager.mayFoundReligionAtAll()) "can found our religion in a city of ours" else null,
                if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayEnhanceReligion) && manager.mayEnhanceReligionAtAll()) "can enhance our religion in a city of ours" else null,
                if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion)) "can spread its religion in a city it stands in" else null,
                RulerUnits.greatImprovementOf(unit)?.let { "can raise $it on an unimproved tile of ours (use, ability improvement)" }
            ).ifEmpty { listOf("nothing it can do now") }.joinToString(", ") + " - order it with use, ability religion, in the city" }.toList()
        return mapOf(
            "faithPerTurn" to civ.stats.statsForNextTurn.faith.toInt(),
            "faithStored" to manager.storedFaith,
            "pantheon" to (pantheon.map(::beliefInWords).ifEmpty { null } ?: when {
                manager.canFoundOrExpandPantheon() -> "none yet, and the faith for one is ours: the officials will choose it"
                manager.numberOfBeliefsAvailable(com.unciv.models.ruleset.BeliefType.Pantheon) == 0 -> "none, and none can be had: every pantheon belief in this world is taken"
                civ.gameInfo.civilizations.any { it.religionManager.religionState == com.unciv.logic.civilization.managers.ReligionState.EnhancedReligion }
                    && manager.remainingFoundableReligions() == 0 ->
                    "none, and none can be had now: the world's faiths are all founded"
                else -> "none yet - ${manager.faithForPantheon()} faith founds one" }),
            "religion" to (founded?.let { religion -> mapOf("name" to religion.getReligionDisplayName(), "holyCity" to manager.getHolyCity()?.name,
                "beliefs" to religion.getAllBeliefsOrdered().filter { it.type != com.unciv.models.ruleset.BeliefType.Pantheon }.map(::beliefInWords).toList(),
                "citiesFollowing" to manager.numberOfCitiesFollowingThisReligion()) }
                ?: "not founded - a Great Prophet founds one in a city of ours; ${manager.remainingFoundableReligions().let { if (it > 0) "$it more can still be founded in this world" else "none is left to found in this world" }}"),
            "nextGreatProphet" to if (manager.canGenerateProphet(ignoreFaithAmount = true)) "born when stored faith reaches about ${manager.faithForNextGreatProphet()}" else null,
            "ourCities" to cities,
            "prophetsAndMissionaries" to missionaries.ifEmpty { null },
            // Gods and Kings' own rules (Units, Beliefs, Eras and Policies files), checked against the engine's purchase tests
            "faithBuys" to "In this world faith buys: Missionaries and Inquisitors, in a city whose people follow a founded religion; the religious buildings a belief of the city's religion allows (Cathedral, Mosque, Pagoda, Monastery); " +
                "soldiers of the ages before the Industrial, where the city's religion holds the Holy Warriors belief; and from the Industrial era on, Great Prophets (in a city following a founded religion, dearer with each bought) and the Great Person of each completed Commerce, Rationalism, Freedom, Autocracy or Order branch (1,000 faith, dearer each time). " +
                "Before the Industrial era a Great Prophet comes only as stored faith grows. get_options.buildable gives each city's price in faith; the purchase order with with: faith buys.",
            "greatPeopleForFaith" to greatPeopleForFaith(civ),
            // A unit bought appears on the city's centre: a city whose centre holds one of the same kind already cannot take it (Rome's Great Merchants, Run 20)
            "faithBuysNow" to civ.cities.flatMap { city -> (civ.gameInfo.ruleset.buildings.values + civ.gameInfo.ruleset.units.values).filter { item -> item !is com.unciv.models.ruleset.unit.BaseUnit || city.canPlaceNewUnit(item) }
                .mapNotNull { item -> faithPrice(city, item)?.let { item.name to (it to city.name) } } }
                .groupBy({ it.first }, { it.second }).map { (item, offers) -> "$item: ${offers.minOf { it.first }} faith (${offers.filter { it.first == offers.minOf { o -> o.first } }.joinToString { it.second }})" }
                .ifEmpty { listOf("nothing faith can buy now (the note below says what it buys, and when)") },
            "note" to "The officials spend faith by their own rule, and much of it stays unspent: until our religion is founded and enhanced they keep it for a Great Prophet (which faith can buy only from the Industrial era); after that, missionaries and an inquisitor to win back cities of ours that follow another faith, and a few missionaries abroad; once no religion is left to found in the world, a Great Person or a religious building where one can be bought. " +
                "They choose beliefs on their own; the Faith weight tilts how much faith they make. Founding, enhancing and spreading are done by a Great Prophet or Missionary of ours, with the use order (ability religion) in the right city; the purchase order with with: faith spends the rest as you choose.")
    }

    /** Each Great Person faith can buy in this world, from the rules themselves: what opens it (a branch completed, an era), the price, and whether it is open
     *  to us now - so nobody hoards faith for one that cannot come (four realms waited on a Commerce Great Person in Run 21) */
    private fun greatPeopleForFaith(civ: Civilization): List<String> {
        val ruleset = civ.gameInfo.ruleset
        val buying = setOf(com.unciv.models.ruleset.unique.UniqueType.BuyUnitsIncreasingCost, com.unciv.models.ruleset.unique.UniqueType.BuyUnitsForAmountStat, com.unciv.models.ruleset.unique.UniqueType.BuyUnitsWithStat)
        val sources: List<com.unciv.models.ruleset.unique.IHasUniques> = ruleset.policies.values + ruleset.eras.values + ruleset.beliefs.values
        val ways = sources.flatMap { source -> source.uniqueObjects.filter { it.type in buying && "Faith" in it.params && ruleset.units[it.params[0]]?.isGreatPerson == true }.map { source to it } }
        if (ways.isEmpty()) return listOf("no Great Person can be bought with faith in this world")
        return ways.groupBy { it.second.params[0] }.map { (person, opened) ->
            val unit = ruleset.units[person]!!
            val price = opened.first().second.let { u -> if (u.type == com.unciv.models.ruleset.unique.UniqueType.BuyUnitsWithStat) "its faith price" else "${u.params[1]} faith" + if (u.type == com.unciv.models.ruleset.unique.UniqueType.BuyUnitsIncreasingCost) ", dearer each time" else "" }
            val eras = opened.mapNotNull { (source, _) -> source as? com.unciv.models.ruleset.tech.Era }
            val how = (opened.filter { it.first !is com.unciv.models.ruleset.tech.Era }.map { (source, unique) -> "with " + (if (source is com.unciv.models.ruleset.Policy) source.name.replace(" Complete", " complete") else "the ${source.name} belief") +
                    unique.modifiers.joinToString("") { ", " + RulerUnits.inWords(it.text) } } +
                listOfNotNull(eras.minByOrNull { it.eraNumber }?.let { "from the ${it.name} on" })).joinToString("; or ")
            val now = civ.cities.filter { city -> faithPrice(city, unit) != null }
            // What we lack, said plainly: the branch not yet complete, or the era not yet reached
            val lacking = opened.mapNotNull { (source, unique) -> when {
                source is com.unciv.models.ruleset.Policy && !civ.policies.isAdopted(source.name) -> "${source.name.removeSuffix(" Complete")} is not complete"
                source is com.unciv.models.ruleset.tech.Era && civ.getEra().eraNumber < source.eraNumber -> "we are in the ${civ.getEra().name}"
                !unique.conditionalsApply(civ.state) -> "we are in the ${civ.getEra().name}"
                // Where it may be bought: "in all cities in which the majority religion is a major religion" - none of ours may be one (Run 22: Babylon and Greece held the faith and read no reason)
                else -> unique.params.firstOrNull { it.startsWith("in ") && it != "in all cities" }?.let { where -> "it is bought only ${RulerUnits.inWords(where).replace("in all cities in which", "in a city in which").replace("in all cities", "in a city")}, and no city of ours is one now" } } }.distinct()
            "$person: $how ($price) - " + (if (now.isNotEmpty()) "open to us now, ${faithPrice(now.first(), unit)} faith in ${now.first().name}" else "not open to us now" + (lacking.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(", ") } ?: ""))
        } + "a Great Person not named here cannot be bought with faith in this world"
    }

    /** Each strategic resource we hold or use: how many in all, how many our units take, how many the queued items will need, what is spare */
    private fun strategicResources(civ: Civilization): List<String> {
        val ruleset = civ.gameInfo.ruleset
        val net = civ.getCivResourcesByName()
        return ruleset.tileResources.values.filter { it.resourceType == com.unciv.models.ruleset.tile.ResourceType.Strategic }.mapNotNull { resource ->
            val name = resource.name
            val units = civ.units.getCivUnits().sumOf { it.getResourceRequirementsPerTurn()[name] ?: 0 }
            val buildings = civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().sumOf { it.getResourceRequirementsPerTurn(city.state)[name] ?: 0 } }
            val queued = civ.cities.flatMap { city -> city.cityConstructions.constructionQueue.mapNotNull { item ->
                val needs = (ruleset.units[item]?.getResourceRequirementsPerTurn(city.state) ?: ruleset.buildings[item]?.getResourceRequirementsPerTurn(city.state))?.get(name) ?: 0
                if (needs > 0) "$needs for ${city.name}'s $item" else null } }
            val have = net[name] ?: 0
            // What we give in deals is ours still, though out of our hands while the deal stands (the Inca read "6 in all" with 2 leased to Babylon, Run 22)
            val traded = civ.diplomacy.values.sumOf { manager -> manager.trades.sumOf { trade -> trade.ourOffers.filter { it.name == name && it.type == com.unciv.logic.trade.TradeOfferType.Strategic_Resource }.sumOf { it.amount } } }
            val total = have + units + buildings + traded
            if (total == 0 && queued.isEmpty()) null
            else "$name $total in all: " + listOfNotNull(if (units > 0) "$units in use by our units" else null, if (buildings > 0) "$buildings by our buildings" else null, if (traded > 0) "$traded given in deals while they stand" else null,
                    if (queued.isNotEmpty()) "${queued.joinToString()} (queued)" else null).ifEmpty { listOf("none in use") }.joinToString(", ") +
                ", ${(have - queued.sumOf { it.substringBefore(" ").toInt() }).let { if (it < 0) "${-it} short" else "$it spare" }}"
        }
    }

    private fun turnsAgo(turns: Int) = when (turns) { 0 -> "this turn"; 1 -> "last turn"; else -> "$turns turns ago" }

    private fun threats(civ: Civilization): List<String> {
        val threats = ArrayList<String>()
        val friendly = LinkedHashMap<String, ArrayList<String>>() // a friend's soldiers near our cities, by power: one line each, not one per soldier
        val tileMap = civ.gameInfo.tileMap
        for (camp in civ.gameInfo.barbarians.encampments) {
            if (camp.destroyed) continue
            val tile = tileMap[camp.position]
            if (tile !in civ.viewableTiles) continue
            threats += "Barbarian camp at ${RulerUnits.where(civ, tile)}"
        }
        val raids = shipRaids[civ.civName].orEmpty().filterValues { civ.gameInfo.turns - it.turn <= 5 }
        for (tile in civ.viewableTiles) {
            val unit = tile.militaryUnit ?: continue
            // A city-state's soldiers count only when it is at war with us (Budapest's never showed); a friend's are folded into a count below
            if (unit.civ == civ || (unit.civ.isCityState && !civ.isAtWarWith(unit.civ))) continue
            // A ship counts within its own reach of a coastal city - a turn's sail and a shot - and four tiles otherwise
            val ship = unit.baseUnit.isWaterUnit
            val nearest = civ.cities.filter { !ship || it.isCoastal() }.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: continue
            val raid = raids[unit.id]
            if (nearest.getCenterTile().aerialDistanceTo(tile) > (if (ship) maxOf(4, unit.getMaxMovement() + unit.getRange()) else 4) && raid == null) continue
            val stance = when {
                unit.civ.isBarbarian -> "hostile"
                civ.isAtWarWith(unit.civ) -> "AT WAR"
                civ.getDiplomacyManager(unit.civ)?.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship) == true -> { friendly.getOrPut(unit.civ.civName) { ArrayList() } += nearest.name; continue }
                else -> "at peace"
            }
            threats += "${unit.civ.civName} ${unit.name}${if (ship) " #${unit.id} (a ship, " else " ("}strength ${unit.baseUnit.strength.coerceAtLeast(unit.baseUnit.rangedStrength)}, $stance) at ${RulerUnits.where(civ, tile)}" +
                (raid?.let { " - it attacked ${it.target} ${turnsAgo(civ.gameInfo.turns - it.turn)}" } ?: "")
        }
        for ((power, near) in friendly) threats += "$power, a friend: ${near.size} soldier${if (near.size == 1) "" else "s"} near ${near.distinct().joinToString(" and ")} (friendly)"
        // A ship that struck at us and has sailed out of sight since: it may be back (Run 18: a Trireme raided Pyongyang six turns running, never listed)
        for ((id, raid) in raids) {
            val ship = civ.gameInfo.civilizations.firstOrNull { it.civName == raid.owner }?.units?.getUnitById(id) ?: continue
            if (ship.getTile() in civ.viewableTiles) continue
            threats += "${raid.owner} ${ship.name} #$id (a ship) attacked ${raid.target} ${turnsAgo(civ.gameInfo.turns - raid.turn)} from ${raid.from}, and is out of sight now"
        }
        // Aircraft of a power at war with us that can reach one of our cities from where we last saw them (in a city or on a carrier)
        val air = civ.viewableTiles.flatMap { tile -> tile.airUnits.filter { it.civ != civ && civ.isAtWarWith(it.civ) } }
            .mapNotNull { unit -> civ.cities.filter { it.getCenterTile().aerialDistanceTo(unit.getTile()) <= unit.getRange() }.minByOrNull { it.getCenterTile().aerialDistanceTo(unit.getTile()) }?.let { unit to it } }
        for ((unit, target) in air)
            threats += "${unit.civ.civName} ${unit.name} (aircraft, range ${unit.getRange()}, AT WAR) at ${RulerUnits.where(civ, unit.getTile())}: ${target.name} is within its reach" +
                (if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CannotBeIntercepted)) " - it cannot be intercepted: fighters and anti-air guns do not touch it"
                else unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.DamageFromInterceptionReduced).sumOf { it.params[0].toInt() }.let { cut ->
                    if (cut >= 100) " - interception does it no harm: our fighters and anti-air guns may meet it but cannot hurt it" else if (cut > 0) " - interception hurts it $cut% less" else "" })
        return threats
    }

    /** What the rulebook says about a building, wonder, unit or technology - so nobody has to advise from memory */
    private fun lookup(civ: Civilization, name: String): Map<String, Any?> {
        val ruleset = civ.gameInfo.ruleset
        treaty(civ, name)?.let { return it }
        fun <T> find(map: Map<String, T>) = map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        // Every kind's rulebook text in words: brackets struck, conditions in parentheses, the rulebook's own notes (the AI's weights, what is shown where) dropped
        val words = { uniques: Collection<String> -> uniques.filterNot { RulerUnits.isRulebookNoise(it) }.map { RulerUnits.inWords(it) } }
        find(ruleset.buildings)?.let { b ->
            return mapOf("kind" to if (b.isWonder) "wonder" else if (b.isNationalWonder) "national wonder" else "building", "name" to b.name,
                "cost" to b.cost, "maintenance" to b.maintenance, "requiresTech" to b.requiredTech, "requiresBuilding" to b.requiredBuilding,
                "yields" to b.cloneStats().toString(), "happiness" to b.happiness.toInt(),
                "effects" to words(b.uniques), "alreadyBuiltSomewhere" to (b.isWonder && civ.gameInfo.getCities().any { it.cityConstructions.isBuilt(b.name) }),
                "weHaveTheTech" to (b.requiredTech == null || civ.tech.isResearched(b.requiredTech!!)))
        }
        find(ruleset.units)?.let { u ->
            return mapOf("kind" to "unit", "name" to u.name, "cost" to u.cost, "strength" to u.strength, "rangedStrength" to u.rangedStrength, "range" to u.range,
                "movement" to u.movement, "requiresTech" to u.requiredTech, "requiresResource" to u.requiredResource, "upgradesTo" to u.upgradesTo,
                "obsoleteWith" to u.obsoleteTech, "abilities" to words(u.uniques), "weHaveTheTech" to (u.requiredTech == null || civ.tech.isResearched(u.requiredTech!!)))
        }
        find(ruleset.technologies)?.let { t ->
            return mapOf("kind" to "technology", "name" to t.name, "era" to t.era(), "researched" to civ.tech.isResearched(t.name),
                "turnsFromHere" to civ.tech.getRequiredTechsToDestination(t).sumOf { civ.tech.turnsToTech(it.name).toIntOrNull() ?: 0 },
                "prerequisites" to t.prerequisites.toList(),
                "unlocks" to (ruleset.buildings.values.filter { it.requiredTech == t.name }.map { it.name } + ruleset.units.values.filter { it.requiredTech == t.name }.map { it.name }),
                "effects" to words(t.uniques))
        }
        find(ruleset.beliefs)?.let { b -> return mapOf("kind" to "belief", "name" to b.name, "type" to b.type.name.lowercase(), "gives" to beliefInWords(b).substringAfter(": "),
            "heldBy" to civ.gameInfo.religions.values.filter { it.hasBelief(b.name) }.map { "${it.getReligionDisplayName()} (${it.foundingCiv.civName}'s)" }) }
        find(ruleset.policies)?.let { p -> return mapOf("kind" to "policy", "name" to p.name, "branch" to p.branch.name, "effects" to words(p.uniques), "adopted" to civ.policies.isAdopted(p.name)) }
        val yields = { stats: com.unciv.models.stats.Stats -> RulerUnits.statsInWords(stats).ifEmpty { null } }
        find(ruleset.tileImprovements)?.let { i ->
            val great = i.hasUnique(com.unciv.models.ruleset.unique.UniqueType.GreatImprovement)
            // Who raises it: the Great Person whose ability names it (and whether that uses the unit up), or the workers
            val raisers = ruleset.units.values.filter { u -> u.uniqueTo == null && u.uniqueObjects.any { it.type == com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly && it.params[0] == i.name } }
                .map { u -> u.name + if (u.uniqueObjects.any { it.type == com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly && it.params[0] == i.name && "consuming" in it.text }) " (in an instant, and it uses the unit up)" else " (in an instant)" }
            return mapOf("kind" to if (great) "great improvement" else "tile improvement", "name" to i.name,
                "gives" to RulerUnits.improvementInWords(i),
                "builtOn" to i.terrainsCanBeBuiltOn.joinToString().ifEmpty { "a resource's tile only" },
                "builtBy" to raisers.ifEmpty { listOf(if (i.terrainsCanBeBuiltOn.any { ruleset.terrains[it]?.type == com.unciv.models.ruleset.tile.TerrainType.Water }) "Work Boats" else "a Worker") }.joinToString(" or "),
                "requiresTech" to i.techRequired, "weHaveTheTech" to (i.techRequired == null || civ.tech.isResearched(i.techRequired!!)),
                "uniqueTo" to i.uniqueTo,
                "effects" to words(i.uniques.filter { it != "Great Improvement" && !it.startsWith("[+") }),
                "note" to if (great) "a great improvement: the officials' workers never replace it, and pillaging it ruins it until repaired" else null)
        }
        find(ruleset.tileResources)?.let { r ->
            return mapOf("kind" to "${r.resourceType.name.lowercase()} resource", "name" to r.name,
                "gives" to yields(r.cloneStats()),
                "improvedBy" to r.getImprovements().toList(), "whenImproved" to r.improvementStats?.let { yields(it) },
                "revealedBy" to r.revealedBy, "weCanSeeIt" to (r.revealedBy == null || civ.tech.isResearched(r.revealedBy!!)),
                "weHave" to (civ.getCivResourcesByName()[r.name] ?: 0),
                "effects" to words(r.uniques).ifEmpty { null },
                "note" to when (r.resourceType) {
                    com.unciv.models.ruleset.tile.ResourceType.Luxury -> "each distinct luxury we hold makes the people happier; a second of the same adds nothing, but can be traded"
                    com.unciv.models.ruleset.tile.ResourceType.Strategic -> "some units and buildings need it to be built and to stand; what we hold is in the briefing's strategicResources"
                    else -> "it adds to the tile's yield once improved; it cannot be traded" })
        }
        find(ruleset.terrains)?.let { t ->
            return mapOf("kind" to when (t.type) {
                    com.unciv.models.ruleset.tile.TerrainType.NaturalWonder -> "natural wonder"
                    com.unciv.models.ruleset.tile.TerrainType.TerrainFeature -> "terrain feature"
                    else -> "terrain" }, "name" to t.name,
                "yields" to yields(t.cloneStats()), "movementCost" to if (t.impassable) "impassable" else t.movementCost,
                "defence" to if (t.defenceBonus != 0f) "${(t.defenceBonus * 100).toInt()}% for a unit standing on it" else null,
                "effects" to words(t.uniques).ifEmpty { null })
        }
        find(ruleset.unitPromotions)?.let { p ->
            return mapOf("kind" to "promotion", "name" to p.name, "effects" to words(p.uniques), "requires" to p.prerequisites.ifEmpty { null }, "forUnitTypes" to p.unitTypes)
        }
        find(ruleset.nations.filterValues { it.isMajorCiv })?.let { n ->
            return mapOf("kind" to "people", "name" to n.name, "leader" to n.leaderName,
                "ability" to (n.uniqueName.ifBlank { null }?.let { "$it: " } ?: "") + (n.uniqueText.ifBlank { null } ?: words(n.uniques).joinToString("; ")),
                "uniqueUnits" to ruleset.units.values.filter { it.uniqueTo == n.name }.map { u -> u.name + (u.replaces?.let { " (in place of the $it)" } ?: "") },
                "uniqueBuildings" to ruleset.buildings.values.filter { it.uniqueTo == n.name }.map { b -> b.name + (b.replaces?.let { " (in place of the $it)" } ?: "") },
                "uniqueImprovements" to ruleset.tileImprovements.values.filter { it.uniqueTo == n.name }.map { it.name }.ifEmpty { null })
        }
        // A city-state kind, or a city-state by name (its kind)
        (find(ruleset.cityStateTypes) ?: civ.gameInfo.civilizations.firstOrNull { it.isCityState && it.civName.equals(name, ignoreCase = true) }?.cityStateType)?.let { k ->
            return mapOf("kind" to "city-state kind", "name" to k.name,
                "toAFriend" to words(k.friendBonusUniques), "toItsAlly" to words(k.allyBonusUniques),
                "note" to "a city-state is a friend at 30 influence and allied at 60 with the most influence of anyone; an ally also gets its luxuries and strategic resources, and its vote when the world votes")
        }
        throw ApiError(404, "The rulebook has nothing called '$name'. look_up knows buildings and wonders, units, technologies, policies, beliefs, treaties, tile improvements (great improvements too), resources, terrain and natural wonders, promotions, peoples (by name: their ability and unique units and buildings) and city-state kinds (or a city-state by name)")
    }

    /** The treaties a deal can carry, as the rulebook would explain them: what each binds, what it costs, how long it runs, what it needs */
    private fun treaty(civ: Civilization, name: String): Map<String, Any?>? {
        val length = civ.gameInfo.speed.dealDuration
        val raCost = (civ.getEra().researchAgreementCost * civ.gameInfo.speed.goldCostModifier).toInt()
        return when (name.trim().lowercase().removeSuffix("s")) {
            "defensive pact" -> mapOf("kind" to "treaty", "name" to Constants.defensivePact, "length" to "$length turns",
                "binds" to "If a third power declares war on either side, the other is pulled into that war at once, automatically. Declaring war yourself cancels your own defensive pacts - they do not drag a partner into a war we start.",
                "gold" to "none", "needs" to "a Declaration of Friendship in force between the two, shared embassies where the game asks for them, and the learning that allows pacts on both sides")
            "research agreement" -> mapOf("kind" to "treaty", "name" to Constants.researchAgreement, "length" to "$length turns",
                "binds" to "Both sides pay the gold up front; when it runs out each gains research, more the more both learnt meanwhile. A war between the two cancels it, and the gold is lost.",
                "gold" to "$raCost gold from each side at our age (the dearer of the two sides' ages sets it)",
                "needs" to "a Declaration of Friendship in force, shared embassies where the game asks for them, the learning that allows it on both sides, the gold on both sides, techs still left to learn on both sides, and no research agreement already running between the two")
            "open border" -> mapOf("kind" to "treaty", "name" to Constants.openBorders, "length" to "$length turns",
                "binds" to "Each side's units may cross the other's lands; without it they are turned back at the border. It is usually a swap - each side gives its own.",
                "gold" to "none", "needs" to "peace, and the learning that allows it")
            "declaration of friendship" -> mapOf("kind" to "treaty", "name" to "Declaration of Friendship",
                "binds" to "A public friendship: it raises regard on both sides and is the door to defensive pacts and research agreements. Denouncing or attacking a friend is remembered as betrayal by them and by others.",
                "gold" to "none", "needs" to "peace and goodwill; it is proposed with the friendship order and answered on the other side's turn")
            else -> null
        }
    }

    private fun options(civ: Civilization): Map<String, Any?> {
        ensureStanding(civ)
        val ruleset = civ.gameInfo.ruleset
        return mapOf(
            "warEstimate" to warEstimate(civ),
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
            // What each city can build, with turns and its price in gold - and in faith where faith buys it (a Missionary is bought, never built)
            "buildable" to civ.cities.associate { city ->
                city.name to (ruleset.buildings.values.asSequence() + ruleset.units.values.asSequence())
                    .mapNotNull { construction -> Triple(construction, construction.isBuildable(city.cityConstructions), faithPrice(city, construction)).takeIf { it.second || it.third != null } }
                    .map { (construction, buildable, faith) ->
                        val buy = construction.getStatBuyCost(city, Stat.Gold)?.takeIf { buildable && construction.canBePurchasedWithStat(city, Stat.Gold) }
                        "${construction.name} (" + listOfNotNull(if (buildable) "${city.cityConstructions.turnsToConstruction(construction.name)}t" else null, buy?.let { "buy $it gold" }, faith?.let { "buy $it faith" },
                            (construction as? com.unciv.models.ruleset.Building)?.happiness?.toInt()?.takeIf { it > 0 }?.let { "+$it mood" }).joinToString(", ") + ")"
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
