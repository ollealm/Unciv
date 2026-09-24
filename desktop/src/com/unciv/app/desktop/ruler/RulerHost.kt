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
    private val trail = RulerTrail()

    class StandingOrders {
        var personality = LinkedHashMap<String, Float>()
        var research = ArrayList<String>()
        var policyBranches = ArrayList<String>()
        /** Buildings and units the officials must never queue on their own */
        var neverBuild = ArrayList<String>()
        /** Gold the officials must leave untouched; null = they spend as they see fit */
        var goldReserve: Int? = null
        /** The officials may not give gold to city-states on their own */
        var noGifts = false
        /** Keep a soldier inside every city: when one stands empty, the nearest free soldier is sent in under a garrison order */
        var garrisonEveryCity = false
        /** The posture of arms: warAim (an enemy city the host converges on), stance attack|defend|raid, reserve (soldiers kept home) */
        var warAim: String? = null
        var stance: String = "attack"
        var reserve: Int = 0
        fun toMap(civ: Civilization? = null) = mapOf(
            "goldReserve" to goldReserve,
            "noGifts" to noGifts,
            "garrisonEveryCity" to garrisonEveryCity,
            "posture" to mapOf("warAim" to warAim, "stance" to stance, "reserve" to reserve),
            "personality" to personality,
            "neverBuild" to neverBuild,
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
        RulerOverrides.unitHandler = { unit -> unit.civ.civID in RulerOverrides.ruledCivs && units.handle(unit) }
        RulerOverrides.underOrders = { unit -> unit.civ.civID in RulerOverrides.ruledCivs && units.underOrders(unit) }
        RulerOverrides.tradeAnswered = { request, receiver, outcome -> diplomacy.settle(request, receiver, outcome) }
        RulerOverrides.unitLost = { loss -> losses.getOrPut(loss.civ.civName) { ArrayList() } += loss }

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
            "GET /log" -> chanceryLog(civ(), query["sinceYear"]?.toIntOrNull()?.let { turnOfYear(it) } ?: since).let { lines ->
                if (lines.size <= 150) lines else listOf(mapOf("turn" to lines[lines.size - 150]["turn"], "text" to "(${lines.size - 150} earlier lines omitted; ask from a later turn or year)")) + lines.takeLast(150) }
            "GET /lookup" -> lookup(civ(), query["name"] ?: throw ApiError(400, "Missing name"))
            "GET /map.png" -> (query["since"]?.toIntOrNull() ?: (game().turns - 10)).coerceAtLeast(0).let { from ->
                Png(MapRenderer.render(civ(), query["style"] ?: "atlas", trail = trail, since = from, sinceYear = yearText(from))) }
            "GET /orders" -> civ().let { orders.getOrPut(it.civName) { StandingOrders() }.toMap(it) }
            "POST /orders" -> setOrders(civ(), json)
            "POST /grant" -> grant(civ(), json)
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
        val noWonders = json.get("noWonders")?.asStringArray()?.toList() ?: listOf()
        val newGame = GameStarter.startNewGame(GameSetupInfo(
            GameParameters().apply {
                speed = json.getString("speed", "Quick")
                difficulty = json.getString("difficulty", "Prince")
                numberOfCityStates = json.getInt("cityStates", 4)
                victoryTypes = ArrayList(com.unciv.models.ruleset.RulesetCache[baseRuleset]!!.victories.keys.filter { it != "Time" }) // left empty, no victory exists at all - and no Apollo Program
                players = ArrayList<Player>().apply {
                    for (nation in ruled + others) add(Player(nation))
                    add(Player(Constants.spectator, PlayerType.Human))
                }
            },
            MapParameters().apply { mapSize = MapSize(json.getString("mapSize", "Small")) }
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
            "diplomacy" to diplomacy.toMap(),
            "ownWars" to ownWars.map { "${it.first}>${it.second}" },
            "trail" to trail.toMap(),
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
        orders.clear(); publicEvents.clear(); chancery.clear(); spending.clear(); warningsWoken.clear(); losses.clear(); ownWars.clear(); notificationsSeen.clear()
        units.reset(); diplomacy.reset(); trail.clear()
        worldAtLastAdvance = null
        RulerOverrides.lockedCities.clear(); RulerOverrides.focusedCities.clear(); RulerOverrides.postures.clear(); RulerOverrides.goldReserve.clear(); RulerOverrides.noGifts.clear(); RulerOverrides.noWonders.clear()
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
        diplomacy.load(host.get("diplomacy"))
        ownWars.clear(); host.get("ownWars")?.asStringArray()?.forEach { ownWars += Pair(it.substringBefore(">"), it.substringAfter(">")) }
        units.dropMistakenHolds(loaded, chancery.values)
        worldAtLastAdvance = WorldSnapshot(loaded)
        trail.load(host.get("trail"))
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
        json.get("noGifts")?.let { noGifts = it.asBoolean() }
        json.get("garrisonEveryCity")?.takeUnless { it.isNull }?.let { garrisonEveryCity = it.asBoolean() }
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
        if (standing.noGifts) RulerOverrides.noGifts += civ.civID else RulerOverrides.noGifts -= civ.civID
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
    private fun whyNot(city: City, construction: INonPerpetualConstruction): List<String> {
        val ruleset = city.civ.gameInfo.ruleset
        return construction.getRejectionReasons(city.cityConstructions).map { reason -> when (reason.type) {
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
            com.unciv.models.ruleset.RejectionReasonType.HiddenWithoutVictory, com.unciv.models.ruleset.RejectionReasonType.DisabledBySetting, com.unciv.models.ruleset.RejectionReasonType.WonderDisabledEra -> "it is not part of this world"
            com.unciv.models.ruleset.RejectionReasonType.CanOnlyBeBuiltInSpecificCities, com.unciv.models.ruleset.RejectionReasonType.MustBeOnTile, com.unciv.models.ruleset.RejectionReasonType.MustNotBeOnTile,
            com.unciv.models.ruleset.RejectionReasonType.MustBeNextToTile, com.unciv.models.ruleset.RejectionReasonType.MustNotBeNextToTile, com.unciv.models.ruleset.RejectionReasonType.MustOwnTile -> "${city.name} does not lie where it can be built" + (reason.errorMessage.takeIf { it != reason.type.errorMessage }?.let { " ($it)" } ?: "")
            else -> reason.errorMessage.removeSuffix("!")
        } }.distinct().toList()
    }

    private fun setOrders(civ: Civilization, json: JsonValue): Any? {
        val ruleset = civ.gameInfo.ruleset
        val standing = orders.getOrPut(civ.civName) { StandingOrders() }
        val alreadyKnown = ArrayList<String>()
        val rejected = ArrayList<String>()
        val taken = ArrayList<String>()
        json.get("research")?.let { goals ->
            val kept = goals.asStringArray().map { goal -> if (goal in ruleset.technologies) goal else onlyMatch(goal, ruleset.technologies.keys)?.also { taken += "$goal -> $it" } ?: goal }.filter { goal ->
                if (goal !in ruleset.technologies) rejected += "No technology is called '$goal'" + (if (goal in ruleset.policies) " - that is a policy; put it in policyBranches" else didYouMean(goal, ruleset.technologies.keys).ifEmpty { " in this world; get_options.futureTechs lists every technology still ahead of us" }) + ". The other research goals were kept."
                if (goal in ruleset.technologies && reached(civ, goal)) alreadyKnown += goal
                goal in ruleset.technologies
            }
            goals.remove(); json.addChild("research", JsonValue(JsonValue.ValueType.array).also { array -> kept.forEach { array.addChild(JsonValue(it)) } })
        }
        json.get("policyBranches")?.let { wishes ->
            val kept = wishes.asStringArray().filter { wish ->
                val known = wish in ruleset.policyBranches || wish in ruleset.policies
                if (!known) rejected += "No policy or policy branch is called '$wish'; branches: ${ruleset.policyBranches.keys.joinToString()}. The others were kept."
                known
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
            // "Science", "Gold" and the like turn production into that yield for good - so only ever as the closing item
            val perpetual = com.unciv.models.ruleset.PerpetualConstruction.perpetualConstructionsMap[items.lastOrNull() ?: ""]
            if (perpetual != null && !perpetual.isBuildable(city.cityConstructions)) throw ApiError(400, "${city.name} cannot turn production into ${perpetual.name} yet")
            val constructions = (if (perpetual != null) items.dropLast(1) else items).map { civ.construction(it) }
            for ((index, construction) in constructions.withIndex()) {
                val reasons = whyNot(city, construction)
                if (reasons.isNotEmpty()) throw ApiError(400, "${city.name} cannot build ${items[index]}: ${reasons.joinToString()}")
            }
            city.cityConstructions.constructionQueue.clear()
            for (construction in constructions) city.cityConstructions.addToQueue(construction)
            if (perpetual != null) city.cityConstructions.addToQueue(perpetual)
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
            val centre = city.getCenterTile()
            val bought = civ.units.getCivUnits().filter { it.name == item && it.getTile().aerialDistanceTo(centre) <= 1 }.maxByOrNull { it.id }
            val holder = centre.militaryUnit?.takeIf { bought != null && bought.isMilitary() && it != bought }
            val note = if (holder != null && bought != null) " ${item} #${bought.id} stands beside ${city.name}, which our ${holder.name} #${holder.id} still holds: order it to garrison ${city.name} to swap them, or the officials use it as they see fit." else ""
            log(civ, "By your order: bought $item in ${city.name} for $cost gold.$note")
            "Bought $item in ${city.name} for $cost gold (${civ.gold} left).$note"
        }
        // Unit orders, diplomacy
        tryEach(json.get("units")) { units.give(civ, it) }
        // Answers before proposals: a counter is a refusal and a new offer in one order, and the new offer is refused as a mirror while the old one still waits
        tryEach(json.get("answerOffers")) { diplomacy.answerOffer(civ, it.getString("power"), it.getString("answer"), it.getString("terms", null)) }
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
            ownWars += Pair(civ.civName, targetName)
            addPublicEvent("${civ.civName} has declared war on $targetName!")
            log(civ, "By your order: we declared war on $targetName.")
            "Declared war on $targetName"
        }
        if (alreadyKnown.isNotEmpty()) failed += "Already known, dropped from the research goals: ${alreadyKnown.joinToString()}"
        standing.research.removeAll { reached(civ, it) }
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
    /** A research goal is reached once known - except the repeatable ones (Future Tech), which are never done */
    private fun reached(civ: Civilization, tech: String) =
        civ.tech.isResearched(tech) && civ.gameInfo.ruleset.technologies[tech]?.isContinuallyResearchable() != true

    private fun applyStandingOrders(civ: Civilization) {
        val standing = orders[civ.civName] ?: return
        val ruleset = civ.gameInfo.ruleset

        val goal = standing.research.firstOrNull { !reached(civ, it) }
        if (goal != null) {
            val path = civ.tech.getRequiredTechsToDestination(ruleset.technologies[goal]!!).map { it.name }
            if (path.isNotEmpty() && civ.tech.techsToResearch != path) {
                civ.tech.techsToResearch.clear()
                civ.tech.techsToResearch.addAll(path)
            }
        }

        if (standing.garrisonEveryCity) for (city in civ.cities) {
            val centre = city.getCenterTile()
            val position = centre.position
            val holder = centre.militaryUnit
            if (holder != null && !poorDefender(civ, holder)) continue
            if (units.orders.values.any { it.action == "garrison" && it.x == position.x.toInt() && it.y == position.y.toInt() }) continue // someone is on the way
            val soldier = civ.units.getCivUnits()
                .filter { it.isMilitary() && it.baseUnit.isLandUnit && it.id !in units.orders && !it.getTile().isCityCenter() && !poorDefender(civ, it) && units.canMarch(it, centre) } // same landmass: a spent unit cannot be asked for a path
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
            log(civ, "Adopted the policy ${policy.name}.")
        }
    }

    //endregion
    //region Turns, chancery log and public events

    /** What the officials paid for during the turn being played, per civ - written into the chancery log once the turn is over */
    private val spending = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Int, String>>>()
    /** Units of each civ destroyed or captured in battle during the turn being played (the engine's word, see RulerOverrides.noteLoss) */
    private val losses = HashMap<String, MutableList<RulerOverrides.UnitLoss>>()
    /** The notifications each civ's last bookkeeping read: the same objects move from the live list into the log at the turn's end, and are not read twice */
    private val notificationsSeen = HashMap<String, MutableSet<com.unciv.logic.civilization.Notification>>()

    private fun log(civ: Civilization, text: String) {
        chancery.getOrPut(civ.civName) { ArrayList() } += mapOf("turn" to stamp(), "text" to text)
    }

    /** The first turn whose year is at or after the one asked for (years run negative before Christ) */
    private fun turnOfYear(year: Int): Int = (0..game().turns).firstOrNull { game().getYear(it - game().turns) >= year }?.let { it - 1 } ?: game().turns

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
        for (notification in notifications) {
            if (noise.any { it in notification.text }) continue
            if (flipped.any { "[$it]" in notification.text && ("allied with" in notification.text || "lost our alliance" in notification.text) }) continue
            log(civ, notification.text.replace("[", "").replace("]", ""))
        }
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
                val construction = civ.gameInfo.ruleset.buildings[item] ?: civ.gameInfo.ruleset.units[item]
                val reasons = construction?.let { whyNot(city, it) } ?: emptyList()
                log(civ, if (reasons.isNotEmpty()) "The officials dropped $item from ${city.name}'s queue because ${reasons.joinToString()}."
                    else if (city.id !in RulerOverrides.lockedCities) "The officials changed their own plan for ${city.name}: $item out."
                    else "The officials dropped $item from ${city.name}'s queue, replacing it with their own choice.")
            }
            for (item in now) if (item !in was && !isPerpetual(item))
                log(civ, "The officials queued $item in ${city.name}.")
        }
        for (city in civ.cities) if (city.name !in before.queues) log(civ, if (city.foundingCivObject == civ) "Founded ${city.name}."
            else "We took ${city.name}${city.foundingCivObject?.let { " from ${it.civName}" } ?: ""}: it is ours, held as a puppet (it builds and pays for itself, gives no orders) until you annex it - or raze it, or spare it; a cities order does each.")
        val alive = civ.units.getCivUnits().map { it.id }.toSet()
        val fallen = losses.remove(civ.civName) ?: emptyList<RulerOverrides.UnitLoss>()
        // Re-arming keeps a unit's number, so a number that is gone was killed, captured, disbanded, used up, or founded a city
        for ((id, unit) in before.units) if (id !in alive) {
            val fell = fallen.firstOrNull { it.unit.id == id }
            if (fell == null && unit.first == "Settler") continue // settlers vanish by founding
            val spent = civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CreateWaterImprovements) == true
            val great = civ.gameInfo.ruleset.units[unit.first]?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.GreatPerson) == true
            log(civ, if (fell != null) "Our ${unit.first} #$id was ${if (fell.captured) "captured" else "lost in action"} ${describeWhere(civ, fell.where)}" +
                    (fell.enemy?.let { enemy -> ", ${if (fell.captured) "taken" else "killed"} by ${if (enemy.isBarbarian) "a barbarian" else enemy.civName + "'s"} ${fell.enemyUnit ?: "soldiers"}" } ?: "") + "."
                else if (spent) "Our ${unit.first} ${describeWhere(civ, unit.second)} did its work and was used up, as such boats are: the fishing grounds it set up stay."
                else if (great) "Our ${unit.first} #$id was spent: its ability used, by your order or by the officials once it was left to them."
                else "Our ${unit.first} #$id ${describeWhere(civ, unit.second)} left service: disbanded by the officials to save its upkeep, or used up (a spaceship part added to the ship, a missile fired, a missionary spent).")
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
        for (deal in diplomacy.endingSoon(civ, 5) - diplomacy.endingSoon(civ, 4).toSet()) log(civ, "A deal ends in five turns: $deal. It can be offered again once it has ended (turn ${turn + 5}), not before; renew it only if it is still worth its price.")
        val began = (dealsNow - before.deals).toList(); val ended = (before.deals - dealsNow).toList()
        // A deal that ends the turn its renewal begins was replaced, not lost: same partner, same goods, only the numbers differ
        val goodsOf = { deal: String -> deal.replace(Regex(" x\\d+"), "").replace(Regex("\\d+"), "#") }
        val (replaced, lapsed) = ended.partition { old -> began.any { goodsOf(it) == goodsOf(old) } }
        if (began.isNotEmpty() || ended.isNotEmpty()) log(civ, "Deals: " + listOfNotNull(
            began.takeIf { it.isNotEmpty() }?.let { "in force with " + it.joinToString("; ") },
            replaced.takeIf { it.isNotEmpty() }?.let { "replaced by the renewal: " + it.joinToString("; ") },
            lapsed.takeIf { it.isNotEmpty() }?.let { "ended with " + it.joinToString("; ") }).joinToString(". ") + ".")
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
        if (paid.isNotEmpty()) log(civ, "The treasury now holds ${civ.gold} gold.")
        val unexplained = before.gold - civ.gold - paid.sumOf { it.first }
        if (unexplained > 40) log(civ, "A further $unexplained gold left the treasury this turn without the officials' hand in it (upkeep, payments under our deals, or a deal struck).")
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

    private fun describeWhere(civ: Civilization, tile: Tile): String {
        val nearest = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return "far from home"
        val distance = nearest.getCenterTile().aerialDistanceTo(tile)
        return if (distance == 0) "in ${nearest.name}" else "$distance tile${if (distance == 1) "" else "s"} from ${nearest.name}"
    }

    private class WorldSnapshot(game: GameInfo) {
        val wars = HashSet<Set<String>>()
        val cityOwners = HashMap<String, String>()
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
                    for (building in city.cityConstructions.getBuiltBuildings())
                        if (building.isWonder) {
                            wonders[building.name] = civ.civName
                            val centre = city.getCenterTile()
                            val land = listOfNotNull(
                                if (city.isCoastal()) "on the coast" else null,
                                if (centre.isAdjacentToRiver()) "by a river" else null,
                                if (centre.isHill()) "on a hill" else null,
                                centre.getTilesInDistance(2).count { it.terrainFeatures.contains("Forest") }.takeIf { it >= 3 }?.let { "among forests" },
                                centre.getTilesInDistance(2).any { it.baseTerrain == "Mountain" }.takeIf { it }?.let { "under mountains" },
                                centre.baseTerrain.lowercase().takeIf { it in listOf("desert", "tundra", "snow", "plains", "grassland") }?.let { "$it country" })
                            wonderSites[building.name] = "${city.name} (${if (city.isCapital()) "the capital, " else ""}${land.joinToString(", ").ifEmpty { "inland" }})"
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
        for ((city, owner) in after.cityOwners) {
            val previousOwner = before.cityOwners[city]
            if (previousOwner == null) {
                if (owner in after.eras) addPublicEvent("$owner founded the city of $city.") // city-states founding themselves is not news
            }
            else if (previousOwner != owner) addPublicEvent("$owner captured $city from $previousOwner!")
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
        for (civ in before.alive - after.alive) addPublicEvent("$civ has been destroyed.")
    }

    /** Log lines an advisor would want to see at once, not at the next scheduled look-in */
    // Not answers to our own proposals: acting on one sends another, and the advisor would be woken by his own echo every turn.
    // Not "demands": that is a city asking for a luxury, not a neighbour asking for tribute.
    // Not our own order's echo ("By your order: we declared war"), and not a barbarian camp cleared ("We have captured a barbarian encampment").
    private val wakeWorthy = listOf("dropped .* because", "(?<!By your order: we )declared war", "joined .* in the war against us", "denounced", "lost in action",
        "awaits orders", "(?<!We have )captured(?! a barbarian)", "has been destroyed", "has been stuck", "offers peace", "\\(an advised court\\) offers a deal").map { Regex(it, RegexOption.IGNORE_CASE) }

    private val warningsWoken = HashMap<String, HashSet<String>>()
    /** The world as the last advance left it (null before the first) */
    private var worldAtLastAdvance: WorldSnapshot? = null
    /** (declarer, enemy) for each war a ruled civ began by its own order since the last advance: the council it stops is framed for the declarer as their own doing */
    private val ownWars = ArrayList<Pair<String, String>>()
    /** True while the turn's bookkeeping runs after nextTurn: lines written then belong to the turn just played, all other lines to the turn about to be */
    @Volatile private var settling = false

    private fun advance(turns: Int, wakeOnEvents: Boolean = false): Any? {
        val game = game()
        if (game.majorCivs().any { it.victoryManager.hasWon() }) throw ApiError(409, "The game is over")
        val startTurn = game.turns
        // "Before" is the world as the last advance left it: a war declared by order between rounds is news to everyone else
        val roundStart = worldAtLastAdvance ?: WorldSnapshot(game)
        var stopReason: String? = null
        var declaredBy: String? = null
        val ruledCivs = orders.keys.map { name -> game.civilizations.first { it.civName == name } }.filter { it.isAlive() }
        // What each advisor has already been shown; anything new below is worth waking them for
        val warningsSeen = ruledCivs.associate { civ -> civ.civName to warnings(civ).map { it.replace(Regex("-?\\d+"), "#") }.toSet() }
        val wake = LinkedHashMap<String, List<String>>()

        for (i in 1..turns) {
            val before = WorldSnapshot(game)
            val civSnapshots = ruledCivs.associateWith { diplomacy.noteRequests(it); CivSnapshot(it) }
            for (civ in ruledCivs) applyStandingOrders(civ)
            game.nextTurn()
            trail.record(game)
            val after = WorldSnapshot(game)
            settling = true
            try {
                recordPublicEvents(before, after)
                for (civ in ruledCivs) recordChancery(civ, civSnapshots[civ]!!)
            } finally { settling = false }
            units.forgetTheDead(game)

            for (civ in ruledCivs) {
                val news = chanceryLog(civ, game.turns - 1).map { it["text"] as String }.filter { line -> wakeWorthy.any { it.containsMatchIn(line) } }
                // A warning wakes an advisor once per game: many of them flicker on and off between turns, and every look-in shows them all anyway
                val everSeen = warningsWoken.getOrPut(civ.civName) { HashSet(warningsSeen[civ.civName] ?: emptySet()) }
                val fresh = warnings(civ).map { it.replace(Regex("-?\\d+"), "#") }.filter { everSeen.add(it) }
                    .filter { "no defender" in it || "unhappy" in it || "treasury" in it.lowercase() || "no military" in it }
                if (news.isNotEmpty() || fresh.isNotEmpty()) wake[civ.civName] = news + fresh
            }
            val winner = game.majorCivs().firstOrNull { it.victoryManager.hasWon() }
            val council = if (winner == null) orders.keys.firstNotNullOfOrNull { ruled -> councilEvent(ruled, roundStart, after) } else null
            stopReason = if (winner != null) "${winner.civName} has won a ${winner.victoryManager.getVictoryTypeAchieved()} victory" else council?.news
            declaredBy = council?.declaredBy
            if (stopReason != null || (wakeOnEvents && wake.isNotEmpty())) break
        }
        worldAtLastAdvance = WorldSnapshot(game)
        ownWars.clear() // whatever was declared by order before this advance is old news to the next
        save()
        backup()
        return mapOf(
            "fromTurn" to startTurn,
            "fromYear" to yearText(startTurn),
            "turn" to game.turns,
            "year" to yearText(game.turns),
            "stoppedEarly" to stopReason,
            "declaredBy" to declaredBy,
            "wake" to wake,
            "publicEvents" to publicEvents.filter { (it["turn"] as Int) > startTurn },
            "rankings" to rankings()
        )
    }

    /** Something a ruler must hear about right away, ending the round early */
    /** What stops the clocks for everyone: only a war with a major power - the one thing that needs the ruler's change of course.
     *  A lost city wakes the advisor (there is little a ruler can do in that hour, and the dispatch tells it); a fallen realm is dealt with when the age is done. */
    private class Council(val news: String, val declaredBy: String?)
    private fun councilEvent(ruled: String, roundStart: WorldSnapshot, now: WorldSnapshot): Council? {
        if (ruled !in now.alive) return null
        val newEnemies = (now.wars - roundStart.wars).filter { ruled in it }.flatten().filter { it != ruled }
            .filter { enemy -> game().civilizations.first { it.civName == enemy }.isMajorCiv() }
        if (newEnemies.isEmpty()) return null
        // The ruled civ whose own order began one of these wars, on either side of it
        val declarer = ownWars.firstOrNull { (declarer, enemy) -> declarer == ruled && enemy in newEnemies || enemy == ruled && declarer in newEnemies }?.first
        return Council("$ruled is now at war with ${newEnemies.joinToString()}", declarer)
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
            "score" to civ.calculateTotalScore().toInt(),
            "gold" to civ.gold,
            "perTurn" to mapOf("gold" to next.gold.toInt(), "science" to next.science.toInt(), "culture" to next.culture.toInt()),
            "happiness" to civ.getHappiness(),
            "happinessBreakdown" to civ.stats.getHappinessBreakdown().filter { it.value != 0f }.mapValues { it.value.toInt() },
            "warnings" to warnings(civ, sites),
            "victory" to civ.gameInfo.civilizations.firstOrNull { it.victoryManager.hasWon() }.let { winner -> mapOf("winner" to winner?.civName, "victoryType" to winner?.victoryManager?.getVictoryTypeAchieved(),
                "note" to if (winner != null) "The game is over." else "Nobody has won yet. A scientific victory comes the turn the last spaceship part is built and added to the ship in the capital.") },
            "researching" to civ.tech.currentTechnologyName(),
            "researchQueue" to civ.tech.techsToResearch,
            "techsResearched" to civ.tech.techsResearched.size,
            "techsKnown" to civ.tech.techsResearched.sorted().joinToString(", "),
            "spaceship" to civ.gameInfo.ruleset.victories["Scientific"]?.let { victory -> victory.requiredSpaceshipPartsAsCounter.takeIf { it.isNotEmpty() && "Scientific" in civ.gameInfo.gameParameters.victoryTypes }?.let { needed ->
                val have = civ.victoryManager.currentsSpaceshipParts
                mapOf("parts" to needed.keys.associateWith { "${have[it]} of ${needed[it]}" },
                    "apollo" to civ.hasUnique(com.unciv.models.ruleset.unique.UniqueType.EnablesConstructionOfSpaceshipParts),
                    "note" to "Parts are units: queue them by name in any city once the Apollo Program stands and the techs are known (each needs Aluminum); a finished part walks to the capital and is added to the ship by the officials. They CANNOT be bought with gold - saving gold for them is wasted. The ship launches, and the game is won, when every part is added.") } },
            "policies" to civ.policies.getAdoptedPolicies().toList(),
            "culture" to mapOf("stored" to civ.policies.storedCulture, "neededForNextPolicy" to civ.policies.getCultureNeededForNextPolicy()),
            "militaryStrength" to civ.getStatForRanking(RankingType.Force),
            "warEstimate" to (if (civ.isAtWar() || orders[civ.civName]?.warAim != null) warEstimate(civ)
                else mapOf("note" to "At peace: the chancery's reckoning of every rival city against our engines is in get_options.warEstimate - read it before any talk of war.")),
            "militaryNote" to run {
                val soldiers = civ.units.getCivUnits().filter { it.isMilitary() }.toList()
                val total = soldiers.sumOf { it.getForceEvaluation() }.coerceAtLeast(1)
                val siege = soldiers.filter { it.baseUnit.isProbablySiegeUnit() || it.baseUnit.isRanged() }.sumOf { it.getForceEvaluation() }
                val bestOurs = soldiers.filter { it.baseUnit.isMelee() }.maxOfOrNull { it.baseUnit.strength } ?: 0
                "militaryStrength is a headcount of raw force, ours and theirs alike - the figure the whole world sees. It does not say who wins. It leaves out: cities that shoot back (see each power's cityDefence; a city's strength against our best foot soldier, strength $bestOurs), the gap between ages (a later era's soldiers beat an earlier one's well beyond their numbers - compare eras), ground and walls, and what a rich realm buys in a season. ${siege * 100 / total}% of our own figure is archers and siege engines, which cannot stand in a line alone. Before advising an attack, weigh these, and say how sure you are."
            },
            "units" to civ.units.getCivUnits().map { units.describeUnit(civ, it) }.toList(),
            "citySites" to sites.ifEmpty { listOf("(none: no usable site within a settler's reach, or no land left on our shores - the officials train no settlers while it stays so)") },
            "cities" to civ.cities.map { city ->
                val stats = city.cityStats.currentCityStats
                mapOf("name" to city.name, "pop" to city.population.population, "capital" to city.isCapital(),
                    "puppet" to city.isPuppet.takeIf { it }, "beingRazed" to city.isBeingRazed.takeIf { it },
                    "buildings" to city.cityConstructions.getBuiltBuildings().map { it.name }.sorted(),
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
            "log" to condensed(chanceryLog(civ, logSince ?: (turn - 12)).map { "T${it["turn"]}: ${it["text"]}" }),
            "peaceOffers" to diplomacy.peaceOffers(civ),
            "tradeOffers" to diplomacy.tradeOffers(civ),
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
        val bestEngine = siege.maxByOrNull { it.baseUnit.rangedStrength }
        val bestFoot = melee.maxByOrNull { it.baseUnit.strength }
        // The engine's own combat sum, at full health and even luck: what one volley of our best engine takes off a city, and what the city does to our best foot soldier
        fun volley(unit: com.unciv.logic.map.mapunit.MapUnit, city: City) = com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
            com.unciv.logic.battle.MapUnitCombatant(unit), com.unciv.logic.battle.CityCombatant(city), city.getCenterTile(), 0.5f)
        fun cityHits(unit: com.unciv.logic.map.mapunit.MapUnit, city: City) = com.unciv.logic.battle.BattleDamage.calculateDamageToDefender(
            com.unciv.logic.battle.CityCombatant(city), com.unciv.logic.battle.MapUnitCombatant(unit), unit.getTile(), 0.5f)
        val cities = civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.flatMap { other -> other.cities.filter { it.getCenterTile().isExplored(civ) } }
            .map { city ->
                val defence = com.unciv.logic.battle.CityCombatant(city).getCityStrength()
                val perVolley = bestEngine?.let { volley(it, city) } ?: 0
                val volleysToBreak = if (perVolley > 0) (city.health + perVolley - 1) / perVolley else null
                val hitOnFoot = bestFoot?.let { cityHits(it, city) }
                // The approaches: how many land tiles touch the city, and how many of those an enemy soldier stands on
                val approaches = city.getCenterTile().neighbors.filter { it.isLand && !it.isImpassible() }.toList()
                val heldApproaches = approaches.count { it.militaryUnit != null && it.militaryUnit!!.civ != civ }
                val gate = when {
                    approaches.isEmpty() -> "no land approach at all - only from the sea"
                    heldApproaches == approaches.size -> "every land approach (${approaches.size}) is held by an enemy soldier; the rest is mountain or sea - engines can still bombard from two tiles off, but nothing walks in until the approaches are won"
                    approaches.size <= 2 -> "only ${approaches.size} land approaches - a narrow gate, easily plugged"
                    else -> "${approaches.size} land approaches, $heldApproaches held"
                }
                val verdict = when {
                    bestEngine == null -> "no siege engines - the walls cannot be broken"
                    volleysToBreak!! <= siege.size * 2 -> "would fall within a few turns of concerted bombardment"
                    volleysToBreak <= siege.size * 5 -> "a siege of some turns, under fire the whole time"
                    else -> "beyond what our engines can break before they are shot to pieces"
                }
                mapOf("city" to city.name, "of" to city.civ.civName, "defence" to defence, "health" to city.health, "walls" to city.cityConstructions.builtBuildings.any { "Walls" in it || "Castle" in it },
                    "garrison" to city.getCenterTile().militaryUnit?.name, "tilesFromOurNearestCity" to civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) },
                    "oneVolleyOfOurBestEngineTakes" to perVolley, "volleysToBreak" to volleysToBreak, "cityShotAtOurBestFootSoldierTakes" to hitOnFoot, "approaches" to gate, "verdict" to verdict)
            }.sortedBy { it["volleysToBreak"] as Int? ?: 999 }
        val unseen = civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.associate { other -> other.civName to other.cities.count { !it.getCenterTile().isExplored(civ) } }.filterValues { it > 0 }
        return mapOf(
            "ourBestEngine" to bestEngine?.name, "ourBestFootSoldier" to bestFoot?.name, "siegeEngines" to siege.size, "footSoldiers" to melee.size,
            "ranged" to soldiers.count { it.baseUnit.isRanged() && !it.baseUnit.isProbablySiegeUnit() },
            "citiesWeHaveSeen" to cities,
            "citiesNeverSeen" to unseen,
            "whoElseWouldFight" to civ.getKnownCivs().filter { it.isMajorCiv() && !civ.isAtWarWith(it) }.associate { target -> target.civName to
                (civ.gameInfo.civilizations.filter { ally -> ally != target && ally != civ && ally.isAlive() && ((ally.isMajorCiv() && ally.getDiplomacyManager(target)?.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.DefensivePact) || (ally.isCityState && ally.allyCiv == target)) }
                    .map { it.civName + (if (it.isCityState) " (city-state ally)" else " (defensive pact)") }) }.filterValues { it.isNotEmpty() },

            "howToRead" to "Sums from the chancery's own reckoning of battle, at full health and even luck. A city is taken by breaking its health with engines and then walking a foot soldier in; it heals each turn, shoots one volley a turn at anything within two tiles, and its garrison and walls do the rest. Bowmen and crossbows barely scratch walls. What we have never seen we cannot judge - say so.")
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

    private fun warnings(civ: Civilization, citySites: List<String>? = null): List<String> {
        val warnings = ArrayList<String>()
        if (civ.getHappiness() < 0) warnings += "The people are unhappy (${civ.getHappiness()}): growth and production suffer. Luxuries, happiness buildings or fewer new cities help."
        if (civ.tech.currentTechnologyName() == null) warnings += "Research is idle - give research goals."
        val standing = orders[civ.civName]
        val goalsLeft = standing?.research?.count { !reached(civ, it) } ?: 0
        if (standing != null && goalsLeft == 0) warnings += "All research goals are reached - the officials pick techs themselves until new goals are given."
        else if (goalsLeft == 1) warnings += "Only one research goal left - add more."
        for (city in civ.cities) {
            val defender = city.getCenterTile().militaryUnit
            if (defender == null) warnings += "${city.name} has no defender in it."
            else if (poorDefender(civ, defender))
                warnings += "${city.name} is only held by a ${defender.name} - weak for this age."
        }
        for (unit in civ.units.getCivUnits()) if (unit.isGreatPerson() && units.orders[unit.id]?.action == "hold")
            warnings += "${unit.name} #${unit.id} ${describeWhere(civ, unit.getTile())} awaits orders."
        if (civ.getHappiness() in 0..2) warnings += "The people's mood is thin (+${civ.getHappiness()}): one more city or a lost luxury tips them into unrest."
        val outdated = civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit && it.baseUnit.getUpgradeUnits(it.cache.state).any { name -> civ.gameInfo.ruleset.units[name]?.let { to -> it.upgrade.canUpgrade(to, ignoreRequirements = false, ignoreResources = true) } == true } }.toList()
        if (outdated.size >= 3) warnings += "${outdated.size} soldiers carry arms of an earlier age (${outdated.groupingBy { it.name }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}): they cost upkeep and would not hold a wall. Gold above your reserve lets the officials re-arm them; or disband the worst."
        if (!units.hasLandForACity(civ)) for (unit in civ.units.getCivUnits()) if (unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity))
            warnings += "${unit.name} #${unit.id} has nowhere to go: no land is left for a new city on our shores. It costs upkeep - disband it, or keep it only if you mean to take it overseas."
        if (civ.policies.canAdoptPolicy()) warnings += "A policy can be adopted but none of the preferred branches has one available - add a branch to the policy order."
        if (civ.units.getCivUnits().any { it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FoundCity) } && (citySites ?: units.citySites(civ)).isEmpty())
            warnings += "We have a Settler but no free land for a city within reach - every site is too close to an existing city or unexplored. Explore further, or disband/keep the Settler."
        for (city in civ.cities) {
            if (city.cityConstructions.constructionQueue.isEmpty()) warnings += "${city.name} has an empty build queue."
            if (city.cityStats.currentCityStats.food < 0) warnings += "${city.name} is starving."
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
