package com.unciv.app.desktop.ruler

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.tile.Tile
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 *  Draws the world as one civ knows it, in the manner of an old atlas: parchment for the unknown,
 *  muted terrain, inked coastlines and rivers, dashed borders, serif names. No grid, no coordinates.
 */
object MapRenderer {
    private const val HEX = 34f // pixel distance between neighbouring hex centres
    private val RADIUS = HEX / sqrt(3f) + 1.6f // corner radius for that spacing; slight overlap hides seams

    private val parchment = Color(232, 219, 186)
    private val ink = Color(60, 45, 30)
    private val terrainColors = mapOf(
        "Ocean" to Color(150, 184, 204), "Coast" to Color(178, 206, 216), "Lakes" to Color(176, 208, 220),
        "Grassland" to Color(172, 190, 132), "Plains" to Color(204, 194, 138), "Desert" to Color(226, 210, 160),
        "Tundra" to Color(198, 198, 176), "Snow" to Color(238, 238, 232), "Mountain" to Color(160, 150, 138),
    )

    /**
     *  atlas: the whole map drawn by us. reference: terrain, coasts and rivers only, for an image model to repaint.
     *  overlay: borders, units, cities and names drawn on top of [base] (a repaint of the reference, any size).
     *  campaign: the atlas with the age's campaigns since turn [since] on it as an atlas draws them - a few broad arrows, from [trail].
     *  trace: the atlas with every unit's march since [since] traced on it exactly, a line each.
     */
    fun render(civ: Civilization, style: String = "atlas", base: BufferedImage? = null, trail: RulerTrail? = null, since: Int = 0, sinceYear: String = "", until: Int = Int.MAX_VALUE, untilYear: String = ""): ByteArray {
        val drawTerrain = style != "overlay"
        val drawOverlay = style != "reference"
        val game = civ.gameInfo
        val tiles = game.tileMap.values
        val explored = tiles.filter { it.isExplored(civ) }.ifEmpty { tiles }
        val allPoints = tiles.associateWith { HexMath.hex2WorldCoords(it.position) }
        val points = allPoints.filterKeys { tile -> explored.any { it.aerialDistanceTo(tile) <= 2 } }
        val minX = points.values.minOf { it.x }; val maxX = points.values.maxOf { it.x }
        val minY = points.values.minOf { it.y }; val maxY = points.values.maxOf { it.y }
        val scale = HEX / sqrt(3f)
        val margin = HEX * 2
        val width = ((maxX - minX) * scale + margin * 2).toInt().coerceAtLeast(720)
        val height = ((maxY - minY) * scale + margin * 2).toInt() + 30
        fun px(tile: Tile) = (points[tile]!!.x - minX) * scale + margin
        fun py(tile: Tile) = height - 30 - ((points[tile]!!.y - minY) * scale + margin) // y up in the game, down on screen

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = parchment
        g.fillRect(0, 0, width, height)
        if (base != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(base, 0, 0, width, height, null) // the repaint keeps our layout, so our coordinates still hold
        } else if (style != "reference") {
            // a faint mottle so the parchment is not flat
            val mottle = java.util.Random(7)
            g.color = Color(200, 185, 150, 40)
            repeat(width * height / 900) { val x = mottle.nextInt(width); val y = mottle.nextInt(height); g.fillOval(x, y, 3, 3) }
        }

        val visible = civ.viewableTiles
        val known = points.keys.filter { it.isExplored(civ) }
        fun hexagon(tile: Tile, radius: Float): Polygon {
            val polygon = Polygon()
            for (i in 0 until 6) {
                val angle = Math.toRadians(60.0 * i) // flat-top: neighbours lie at 0°, 60°, 120°
                polygon.addPoint((px(tile) + radius * cos(angle)).toInt(), (py(tile) + radius * sin(angle)).toInt())
            }
            return polygon
        }
        /** The edge shared with a neighbour, as a segment */
        fun edge(tile: Tile, neighbor: Tile): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            val mx = (px(tile) + px(neighbor)) / 2; val my = (py(tile) + py(neighbor)) / 2
            val dx = px(neighbor) - px(tile); val dy = py(neighbor) - py(tile)
            val length = sqrt(dx * dx + dy * dy)
            val nx = -dy / length * RADIUS / 2; val ny = dx / length * RADIUS / 2
            return Pair(Pair(mx - nx, my - ny), Pair(mx + nx, my + ny))
        }
        fun line(g: Graphics2D, segment: Pair<Pair<Float, Float>, Pair<Float, Float>>) =
            g.drawLine(segment.first.first.toInt(), segment.first.second.toInt(), segment.second.first.toInt(), segment.second.second.toInt())

        var landMaskForBorders: BufferedImage? = null
        var knownMaskForBorders: BufferedImage? = null
        if (drawTerrain) {
        // Terrain without cells: land and water are painted as two soft layers, and a blurred land mask decides
        // per pixel which one shows - so coasts come out as natural curves instead of hexagon edges
        fun layer(background: Color, paint: (Tile) -> Color?): BufferedImage {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val lg = img.createGraphics()
            lg.color = background; lg.fillRect(0, 0, width, height)
            for (tile in known) { lg.color = paint(tile) ?: continue; lg.fillPolygon(hexagon(tile, RADIUS + 1.5f)) }
            lg.dispose()
            return img
        }
        val meanLand = Color(196, 188, 140); val shallows = terrainColors["Coast"]!!
        val landLayer = blur(layer(meanLand) { if (it.isWater) meanLand else terrainColors[it.baseTerrain] ?: meanLand }, (HEX * 0.45f).toInt())
        val waterLayer = blur(layer(shallows) { if (it.isWater && style != "reference") terrainColors[it.baseTerrain] ?: shallows else shallows }, (HEX * 0.7f).toInt())
        val knownMask = blur(layer(Color.BLACK) { Color.WHITE }, (HEX * 0.5f).toInt())
        val landMask = blur(layer(Color.BLACK) { if (it.isWater) null else Color.WHITE }, (HEX * 0.5f).toInt())
        landMaskForBorders = landMask
        knownMaskForBorders = knownMask
        val wobble = java.util.Random(11)
        val coarse = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height step 6) for (x in 0 until width step 6) {
            val v = wobble.nextInt(256); val c = Color(v, v, v).rgb
            for (dy in 0 until 6) for (dx in 0 until 6) if (x + dx < width && y + dy < height) coarse.setRGB(x + dx, y + dy, c)
        }
        val noise = blur(coarse, 7)
        for (y in 0 until height) for (x in 0 until width) {
            val jitter = ((noise.getRGB(x, y) and 0xff) - 128) / 4 // breaks up the last straight runs of shore
            val knownV = (knownMask.getRGB(x, y) and 0xff) + jitter
            if (knownV < 128) continue // unknown stays parchment
            // The edge of the known world is a soft grey line, so that it reads as the limit of knowledge and not as a shore
            if (knownV < 140) { image.setRGB(x, y, Color(158, 148, 126).rgb); continue }
            val land = (landMask.getRGB(x, y) and 0xff) + jitter
            image.setRGB(x, y, when {
                land in 120..134 -> ink.rgb                     // the shoreline
                land > 134 -> landLayer.getRGB(x, y)
                land > 60 -> lighter(waterLayer.getRGB(x, y))  // pale shallows hugging the coast
                else -> waterLayer.getRGB(x, y)
            })
        }
        // Features as small ink glyphs
        for (tile in known) {
            val x = px(tile); val y = py(tile)
            for (feature in tile.terrainFeatures) when (feature) {
                "Forest", "Jungle" -> {
                    g.color = if (feature == "Forest") Color(96, 120, 78) else Color(74, 110, 70)
                    for ((ox, oy) in listOf(-6f to 2f, 5f to 3f, -1f to -5f)) g.fillOval((x + ox - 3).toInt(), (y + oy - 3).toInt(), 7, 7)
                }
                "Hill" -> {
                    g.color = Color(120, 100, 76); g.stroke = BasicStroke(1.4f)
                    g.drawArc((x - 8).toInt(), (y - 3).toInt(), 16, 10, 0, 180)
                }
                "Marsh" -> { g.color = Color(90, 120, 120); g.stroke = BasicStroke(1f); for (oy in listOf(-4, 0, 4)) g.drawLine((x - 5).toInt(), (y + oy).toInt(), (x + 5).toInt(), (y + oy).toInt()) }
                "Oasis" -> { g.color = Color(120, 170, 200); g.fillOval((x - 4).toInt(), (y - 3).toInt(), 8, 6) }
            }
            if (tile.baseTerrain == "Mountain") {
                g.color = Color(110, 100, 92)
                val peak = Polygon(intArrayOf((x - 9).toInt(), x.toInt(), (x + 9).toInt()), intArrayOf((y + 6).toInt(), (y - 9).toInt(), (y + 6).toInt()), 3)
                g.fillPolygon(peak)
                g.color = Color(235, 235, 235)
                g.fillPolygon(Polygon(intArrayOf((x - 3).toInt(), x.toInt(), (x + 3).toInt()), intArrayOf((y - 4).toInt(), (y - 9).toInt(), (y - 4).toInt()), 3))
            }
            if (drawOverlay) tile.resource?.let { name ->
                val resource = game.ruleset.tileResources[name] ?: return@let
                g.color = when (resource.resourceType.name) { "Luxury" -> Color(190, 140, 30); "Strategic" -> Color(150, 50, 40); else -> Color(90, 80, 60, 120) }
                g.fillOval((x + RADIUS * 0.45f - 2.5f).toInt(), (y - RADIUS * 0.45f - 2.5f).toInt(), 5, 5)
            }
        }
        // Rivers run along hex edges, on the lower side of a tile
        g.color = Color(70, 120, 170)
        g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (tile in known) {
            val p = tile.position
            val riverEdges = listOfNotNull(
                if (tile.hasBottomRiver) game.tileMap.getIfTileExistsOrNull(p.x - 1, p.y - 1) else null,
                if (tile.hasBottomRightRiver) game.tileMap.getIfTileExistsOrNull(p.x - 1, p.y) else null,
                if (tile.hasBottomLeftRiver) game.tileMap.getIfTileExistsOrNull(p.x, p.y - 1) else null,
            )
            for (neighbor in riverEdges) if (neighbor in points) line(g, edge(tile, neighbor))
        }
        } // drawTerrain
        if (!drawOverlay) {
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        // Territory: a soft wash and outline per realm, from a blurred mask so borders flow instead of zigzagging
        if (base == null) for (owner in known.mapNotNull { it.getOwner() }.distinct()) {
            val mask = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val mg = mask.createGraphics(); mg.color = Color.WHITE
            for (tile in known) if (tile.getOwner() == owner) mg.fillPolygon(hexagon(tile, RADIUS + 1.5f))
            mg.dispose()
            val soft = blur(mask, (HEX * 0.5f).toInt())
            val c = owner.nation.getOuterColor()
            val tint = Color(c.r, c.g, c.b)
            for (y in 0 until height) for (x in 0 until width) {
                val inside = soft.getRGB(x, y) and 0xff
                if (inside < 118) continue
                val under = Color(image.getRGB(x, y))
                val onLand = landMaskForBorders?.let { (it.getRGB(x, y) and 0xff) > 134 } ?: true
                // a thin firm line at the edge; within, a light wash on land and barely a breath of it at sea
                // No firm border line along the edge of knowledge: a realm that runs on into the unknown has no known frontier there
                val atEdge = knownMaskForBorders?.let { (it.getRGB(x, y) and 0xff) < 160 } ?: false
                if (inside < 127 && atEdge) continue
                val weight = if (inside < 127) 0.7f else if (onLand) 0.16f else 0.05f
                image.setRGB(x, y, Color(
                    (under.red + (tint.red - under.red) * weight).toInt(), (under.green + (tint.green - under.green) * weight).toInt(),
                    (under.blue + (tint.blue - under.blue) * weight).toInt()).rgb)
            }
        }
        fun colorOf(name: String) = game.civilizations.firstOrNull { it.civName == name }
            ?.let { if (it.isBarbarian) Color(140, 30, 30) else it.nation.getOuterColor().let { c -> Color(c.r, c.g, c.b) } } ?: ink
        var campaigns = emptyList<RulerCampaigns.Campaign>()
        if ((style == "campaign" || style == "trace") && trail != null) {
            if (style == "campaign") campaigns = RulerCampaigns.reckon(trail, civ, since, minOf(until, game.turns))
            else for (tile in visible) for (unit in tile.getUnits()) {
                // Every unit in sight trails the way it came, a point per turn, ending in an arrowhead at its marker
                val own = unit.civ == civ
                if (!unit.isMilitary() && "Settler" !in unit.name) continue // workers and caravans wander; nobody wants their scribbles
                val path = trail.path(unit.id, since, until).mapNotNull { game.tileMap.getIfTileExistsOrNull(it.x, it.y) }
                    .filter { it in points && (own || it.isExplored(civ)) }
                if (path.size < 2 || path.first().aerialDistanceTo(path.last()) < 2) continue
                val xs = path.map { px(it) }; val ys = path.map { py(it) }
                if (xs.zipWithNext().any { (a, b) -> kotlin.math.abs(a - b) > width / 2 }) continue // went round the back of the world
                // Stop short of the unit's own marker, so the arrowhead points at it instead of hiding under it
                val last = xs.size - 1
                val dx = xs[last] - xs[last - 1]; val dy = ys[last] - ys[last - 1]
                val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val endX = xs[last] - dx / length * 9f; val endY = ys[last] - dy / length * 9f
                val curve = java.awt.geom.Path2D.Float()
                curve.moveTo(xs[0], ys[0])
                for (i in 1 until last) curve.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2) // corners rounded through the midpoints
                curve.lineTo(endX, endY)
                val head = Polygon()
                val ux = dx / length; val uy = dy / length
                head.addPoint(endX.toInt(), endY.toInt())
                head.addPoint((endX - ux * 9 - uy * 4.5f).toInt(), (endY - uy * 9 + ux * 4.5f).toInt())
                head.addPoint((endX - ux * 9 + uy * 4.5f).toInt(), (endY - uy * 9 - ux * 4.5f).toInt())
                val color = colorOf(unit.civ.civName)
                val thickness = if (own) 2.6f else 1.8f
                g.color = Color(ink.red, ink.green, ink.blue, 170)
                g.stroke = BasicStroke(thickness + 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(curve)
                g.color = if (own) color else Color(color.red, color.green, color.blue, 190) // others' marches a little fainter than ours
                g.stroke = BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(curve)
                g.fillOval((xs[0] - 3).toInt(), (ys[0] - 3).toInt(), 6, 6)
                g.fillPolygon(head); g.color = ink; g.stroke = BasicStroke(1f); g.drawPolygon(head)
            }
            for (mark in trail.marks) {
                if (mark.turn <= since || mark.turn > until) continue
                val tile = game.tileMap.getIfTileExistsOrNull(mark.x, mark.y) ?: continue
                if (tile !in points || !tile.isExplored(civ)) continue
                val x = px(tile).toInt(); val y = py(tile).toInt()
                if (mark.kind == "taken") {
                    g.color = Color(170, 30, 30); g.stroke = BasicStroke(2.4f)
                    g.drawOval(x - 14, y - 14, 28, 28); g.stroke = BasicStroke(1.2f); g.drawOval(x - 18, y - 18, 36, 36)
                } else if (style == "trace" && (mark.civ == civ.civName || tile in visible)) {
                    // a company lost: a cross in its colours, set a little aside so it does not sit on whoever holds the field now
                    val cx = x + 8; val cy = y - 8
                    g.color = ink; g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4); g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4)
                    g.color = colorOf(mark.civ); g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4); g.drawLine(cx - 4, cy + 4, cx + 4, cy - 4)
                }
            }
        }
        // Units in sight, as small map markers
        for (tile in visible) {
            if (tile !in points) continue
            for (unit in tile.getUnits()) {
                val x = px(tile).toInt(); val y = py(tile).toInt()
                val own = unit.civ == civ
                val nation = unit.civ.nation.getOuterColor()
                g.color = if (unit.civ.isBarbarian) Color(140, 30, 30) else Color(nation.r, nation.g, nation.b)
                g.stroke = BasicStroke(1.2f)
                if (unit.isCivilian()) { g.fillOval(x - 4, y - 4, 8, 8); g.color = ink; g.drawOval(x - 4, y - 4, 8, 8) }
                else {
                    val shield = Polygon(intArrayOf(x - 5, x + 5, x + 5, x, x - 5), intArrayOf(y - 5, y - 5, y + 1, y + 6, y + 1), 5)
                    g.fillPolygon(shield); g.color = if (own) Color.WHITE else ink; g.drawPolygon(shield)
                }
            }
        }
        // The age's campaigns as a history atlas draws them: one broad arrow per force, in its realm's colour, broader for a larger force;
        // the head says how it ended. Over the companies' markers so nothing breaks an arrow; the enemy's first, so that our own lie on top
        for (c in campaigns.sortedBy { it.ours }) {
            val route = c.route.filter { it in points }
            if (route.size < 2) continue
            val xs = route.map { px(it) }; val ys = route.map { py(it) }
            if (xs.zipWithNext().any { (a, b) -> kotlin.math.abs(a - b) > width / 2 }) continue // went round the back of the world
            val body = (if (c.size >= 4) 15f else if (c.size >= 2) 11f else 8f) * (if (c.ours) 1f else 0.85f)
            var line = flatten(smooth(xs, ys))
            if (c.outcome == RulerCampaigns.Outcome.Took) line = shortened(line, 15f) // the head rests against the ring of the city taken
            drawArrow(g, line, body, colorOf(c.whose), head = c.outcome != RulerCampaigns.Outcome.Broken, sea = c.sea)
            if (c.outcome == RulerCampaigns.Outcome.Broken) brokenAt(g, line, body)
            val home = c.homeward.filter { it in points }
            if (c.outcome == RulerCampaigns.Outcome.Back && home.size >= 2) wayBack(g, flatten(smooth(home.map { px(it) }, home.map { py(it) })), colorOf(c.whose))
        }
        // Cities
        for (tile in known) {
            if (!tile.isCityCenter()) continue
            val city = tile.getCity()!!
            val x = px(tile).toInt(); val y = py(tile).toInt()
            val c = city.civ.nation.getOuterColor()
            val size = if (city.isCapital()) 16 else 12
            g.color = Color(c.r, c.g, c.b)
            g.fillOval(x - size / 2, y - size / 2, size, size)
            g.color = ink
            g.stroke = BasicStroke(if (city.civ == civ) 2.5f else 1.5f)
            g.drawOval(x - size / 2, y - size / 2, size, size)
            if (city.isCapital()) { g.color = parchment; g.fillOval(x - 3, y - 3, 6, 6) }
            g.font = Font(Font.SERIF, if (city.civ == civ) Font.BOLD else Font.PLAIN, if (city.isCapital()) 14 else 12)
            val label = city.name // whose it is shows in the colour of its mark and its border
            val textWidth = g.fontMetrics.stringWidth(label)
            g.color = Color(parchment.red, parchment.green, parchment.blue, 200)
            g.fillRoundRect(x - textWidth / 2 - 3, y + size / 2 + 1, textWidth + 6, 15, 6, 6)
            g.color = ink
            g.drawString(label, x - textWidth / 2, y + size / 2 + 13)
        }
        // Cartouche
        val year = game.getYear()
        val yearText = if (year < 0) "${-year} BC" else "AD $year"
        g.font = Font(Font.SERIF, Font.BOLD, 18)
        val title = if (style == "campaign" || style == "trace") "The Marches of ${civ.civName}, $sinceYear to ${untilYear.ifEmpty { yearText }}" else "The Known World of ${civ.civName}, $yearText"
        g.color = Color(parchment.red, parchment.green, parchment.blue, 220)
        g.fillRoundRect(10, 10, g.fontMetrics.stringWidth(title) + 20, 30, 8, 8)
        g.color = ink; g.stroke = BasicStroke(1.2f)
        g.drawRoundRect(10, 10, g.fontMetrics.stringWidth(title) + 20, 30, 8, 8)
        g.drawString(title, 20, 31)
        g.font = Font(Font.SERIF, Font.ITALIC, if (style == "campaign") 13 else 11)
        if (style == "campaign") g.drawString("Arrows: the campaigns of these years, broader for a larger force. A bar and crossed swords: a force broken there. Dashed: the way home. A red ring: a city taken.", 14, height - 10)
        if (style == "trace") g.drawString("Lines trace the marches of these years. A cross: a company lost. A red ring: a city taken.", 14, height - 10)

        g.dispose()

        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    /** A smooth curve through the points (Catmull-Rom); two points get the gentle bow an atlas gives a straight march */
    private fun smooth(xs: List<Float>, ys: List<Float>): java.awt.geom.Path2D.Float {
        val path = java.awt.geom.Path2D.Float()
        path.moveTo(xs[0], ys[0])
        if (xs.size == 2) {
            val dx = xs[1] - xs[0]; val dy = ys[1] - ys[0]
            path.quadTo((xs[0] + xs[1]) / 2 - dy * 0.1f, (ys[0] + ys[1]) / 2 + dx * 0.1f, xs[1], ys[1])
            return path
        }
        for (i in 0 until xs.size - 1) {
            val a = maxOf(i - 1, 0); val d = minOf(i + 2, xs.size - 1)
            path.curveTo(xs[i] + (xs[i + 1] - xs[a]) / 6f, ys[i] + (ys[i + 1] - ys[a]) / 6f,
                xs[i + 1] - (xs[d] - xs[i]) / 6f, ys[i + 1] - (ys[d] - ys[i]) / 6f, xs[i + 1], ys[i + 1])
        }
        return path
    }

    private fun flatten(shape: java.awt.Shape): List<FloatArray> {
        val points = ArrayList<FloatArray>()
        val iterator = shape.getPathIterator(null, 0.5)
        val coords = FloatArray(6)
        while (!iterator.isDone) {
            if (iterator.currentSegment(coords) != java.awt.geom.PathIterator.SEG_CLOSE) points += floatArrayOf(coords[0], coords[1])
            iterator.next()
        }
        return points
    }

    private fun distance(a: FloatArray, b: FloatArray) = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]))

    /** The line with [by] pixels taken off its end */
    private fun shortened(line: List<FloatArray>, by: Float): List<FloatArray> {
        var left = by
        for (i in line.size - 1 downTo 1) {
            val step = distance(line[i - 1], line[i])
            if (step >= left) {
                val t = (step - left) / step
                return line.subList(0, i) + listOf(floatArrayOf(line[i - 1][0] + (line[i][0] - line[i - 1][0]) * t, line[i - 1][1] + (line[i][1] - line[i - 1][1]) * t))
            }
            left -= step
        }
        return listOf(line[0], line[0])
    }

    /** Which way the line is going at its end */
    private fun heading(line: List<FloatArray>): Pair<Float, Float> {
        val tip = line.last(); val back = shortened(line, 6f).last()
        val length = distance(back, tip).coerceAtLeast(0.001f)
        return Pair((tip[0] - back[0]) / length, (tip[1] - back[1]) / length)
    }

    /** An atlas arrow: a shaft that swells from its start to its head, in the realm's colour, inked round, with a soft shadow; a fleet's carries a white sea lane down its middle */
    private fun drawArrow(g: Graphics2D, line: List<FloatArray>, body: Float, color: Color, head: Boolean, sea: Boolean) {
        val total = line.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
        val headLength = if (head) (body * 1.7f).coerceAtMost(total * 0.45f) else 0f
        val shaft = if (head) shortened(line, headLength) else line
        val shaftLength = shaft.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat().coerceAtLeast(0.001f)
        val left = ArrayList<FloatArray>(); val right = ArrayList<FloatArray>()
        var run = 0f
        for (i in shaft.indices) {
            if (i > 0) run += distance(shaft[i - 1], shaft[i])
            val a = shaft[maxOf(i - 1, 0)]; val b = shaft[minOf(i + 1, shaft.size - 1)]
            val length = distance(a, b).coerceAtLeast(0.001f)
            val dx = (b[0] - a[0]) / length; val dy = (b[1] - a[1]) / length
            val half = body / 2 * (0.45f + 0.55f * run / shaftLength)
            left += floatArrayOf(shaft[i][0] - dy * half, shaft[i][1] + dx * half)
            right += floatArrayOf(shaft[i][0] + dy * half, shaft[i][1] - dx * half)
        }
        val shape = java.awt.geom.Path2D.Float()
        shape.moveTo(left[0][0], left[0][1])
        for (p in left) shape.lineTo(p[0], p[1])
        if (head) {
            val (dx, dy) = heading(line)
            val base = shaft.last(); val tip = line.last(); val wing = body * 1.15f
            shape.lineTo(base[0] - dy * wing, base[1] + dx * wing)
            shape.lineTo(tip[0], tip[1])
            shape.lineTo(base[0] + dy * wing, base[1] - dx * wing)
        }
        for (p in right.asReversed()) shape.lineTo(p[0], p[1])
        shape.closePath()
        g.color = Color(40, 30, 20, 60)
        g.fill(java.awt.geom.AffineTransform.getTranslateInstance(2.0, 2.5).createTransformedShape(shape))
        // A pale margin round the ink, so that a dark realm's arrow stands off the land and off another arrow it crosses
        g.color = Color(parchment.red, parchment.green, parchment.blue, 230); g.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(shape)
        g.color = color; g.fill(shape)
        if (sea) {
            g.color = Color(255, 255, 255, 220)
            g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(2f, 6f), 0f)
            g.draw(polyline(shortened(shaft, 4f)))
        }
        g.color = ink; g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(shape)
    }

    private fun polyline(points: List<FloatArray>) = java.awt.geom.Path2D.Float().apply {
        moveTo(points[0][0], points[0][1]); for (p in points.drop(1)) lineTo(p[0], p[1])
    }

    /** Where a force was broken: a bar across its arrow's end and crossed swords beyond it */
    private fun brokenAt(g: Graphics2D, line: List<FloatArray>, body: Float) {
        val (dx, dy) = heading(line)
        val end = line.last(); val half = body * 1.3f
        val bar = java.awt.geom.Line2D.Float(end[0] - dy * half, end[1] + dx * half, end[0] + dy * half, end[1] - dx * half)
        val cx = end[0] + dx * 17f; val cy = end[1] + dy * 17f; val s = 10f
        val blades = listOf(floatArrayOf(cx - s, cy + s, cx + s, cy - s), floatArrayOf(cx + s, cy + s, cx - s, cy - s)) // hilt to point
        for ((width, color) in listOf(7.5f to parchment, 3.6f to ink)) {
            g.color = color
            g.stroke = BasicStroke(width + 1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND); g.draw(bar)
            g.stroke = BasicStroke(width - 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (b in blades) {
                g.draw(java.awt.geom.Line2D.Float(b[0], b[1], b[2], b[3]))
                // the crossguard, a third of the way up from the hilt
                val gx = b[0] + (b[2] - b[0]) * 0.3f; val gy = b[1] + (b[3] - b[1]) * 0.3f
                val ux = (b[2] - b[0]) / (2 * s); val uy = (b[3] - b[1]) / (2 * s)
                g.draw(java.awt.geom.Line2D.Float(gx - uy * 5f, gy + ux * 5f, gx + uy * 5f, gy - ux * 5f))
            }
        }
    }

    /** A force's way home: a dashed line in its colour, with a small head */
    private fun wayBack(g: Graphics2D, line: List<FloatArray>, color: Color) {
        if (line.size < 2) return
        val shaft = shortened(line, 8f)
        val dash = floatArrayOf(9f, 6f)
        g.color = ink; g.stroke = BasicStroke(5.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, dash, 0f); g.draw(polyline(shaft))
        g.color = color; g.stroke = BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, dash, 0f); g.draw(polyline(shaft))
        val (dx, dy) = heading(line)
        val tip = line.last(); val base = shaft.last()
        val head = java.awt.geom.Path2D.Float().apply {
            moveTo(tip[0], tip[1]); lineTo(base[0] - dy * 6f, base[1] + dx * 6f); lineTo(base[0] + dy * 6f, base[1] - dx * 6f); closePath()
        }
        g.color = color; g.fill(head); g.color = ink; g.stroke = BasicStroke(1.4f); g.draw(head)
    }

    /** Three box blurs, separable: close to a gaussian and fast */
    private fun blur(source: BufferedImage, radius: Int): BufferedImage {
        if (radius < 1) return source
        val size = radius * 2 + 1
        val weights = FloatArray(size) { 1f / size }
        val horizontal = java.awt.image.ConvolveOp(java.awt.image.Kernel(size, 1, weights), java.awt.image.ConvolveOp.EDGE_NO_OP, null)
        val vertical = java.awt.image.ConvolveOp(java.awt.image.Kernel(1, size, weights), java.awt.image.ConvolveOp.EDGE_NO_OP, null)
        var result = source
        repeat(3) { result = vertical.filter(horizontal.filter(result, null), null) }
        return result
    }

    private fun lighter(rgb: Int): Int {
        val c = Color(rgb)
        return Color((c.red + 16).coerceAtMost(255), (c.green + 16).coerceAtMost(255), (c.blue + 12).coerceAtMost(255)).rgb
    }
}
