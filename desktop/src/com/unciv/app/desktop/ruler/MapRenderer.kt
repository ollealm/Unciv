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

    fun render(civ: Civilization): ByteArray {
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
        // a faint mottle so the parchment is not flat
        val mottle = java.util.Random(7)
        g.color = Color(200, 185, 150, 40)
        repeat(width * height / 900) { val x = mottle.nextInt(width); val y = mottle.nextInt(height); g.fillOval(x, y, 3, 3) }

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

        // Terrain
        for (tile in known) {
            g.color = terrainColors[tile.baseTerrain] ?: Color(180, 170, 150)
            g.fillPolygon(hexagon(tile, RADIUS))
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
            tile.resource?.let { name ->
                val resource = game.ruleset.tileResources[name] ?: return@let
                g.color = when (resource.resourceType.name) { "Luxury" -> Color(190, 140, 30); "Strategic" -> Color(150, 50, 40); else -> Color(90, 80, 60, 120) }
                g.fillOval((x + RADIUS * 0.45f - 2.5f).toInt(), (y - RADIUS * 0.45f - 2.5f).toInt(), 5, 5)
            }
        }
        // Coastlines: ink where land meets water, and where the known world ends
        g.color = ink
        g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (tile in known) {
            if (tile.isWater) continue
            for (neighbor in tile.neighbors) if (neighbor in points && neighbor.isExplored(civ) && neighbor.isWater) line(g, edge(tile, neighbor))
        }
        g.color = Color(120, 100, 70, 110)
        g.stroke = BasicStroke(1f)
        for (tile in known) for (neighbor in tile.neighbors) if (neighbor in points && !neighbor.isExplored(civ)) line(g, edge(tile, neighbor))
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
        // Territory: a faint wash and a dashed border in the nation's colour
        for (tile in known) {
            val owner = tile.getOwner() ?: continue
            val c = owner.nation.getOuterColor()
            g.color = Color(c.r, c.g, c.b, 0.16f)
            g.fillPolygon(hexagon(tile, RADIUS))
        }
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(5f, 4f), 0f)
        for (tile in known) {
            val owner = tile.getOwner() ?: continue
            val c = owner.nation.getOuterColor()
            g.color = Color(c.r, c.g, c.b, 0.85f)
            for (neighbor in tile.neighbors) if (neighbor in points && neighbor.getOwner() != owner) line(g, edge(tile, neighbor))
        }
        // The fog of memory: known but out of sight fades towards parchment
        g.color = Color(parchment.red, parchment.green, parchment.blue, 70)
        for (tile in known) if (tile !in visible) g.fillPolygon(hexagon(tile, RADIUS))

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
            val label = if (city.civ == civ) city.name else "${city.name} · ${city.civ.civName}"
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
        val title = "The Known World of ${civ.civName}, $yearText"
        g.color = Color(parchment.red, parchment.green, parchment.blue, 220)
        g.fillRoundRect(10, 10, g.fontMetrics.stringWidth(title) + 20, 30, 8, 8)
        g.color = ink; g.stroke = BasicStroke(1.2f)
        g.drawRoundRect(10, 10, g.fontMetrics.stringWidth(title) + 20, 30, 8, 8)
        g.drawString(title, 20, 31)
        g.font = Font(Font.SERIF, Font.ITALIC, 11)
        g.drawString("Shields are soldiers, discs civilians, in their nation's colour. Faded land is remembered, not seen.", 12, height - 10)
        g.dispose()

        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }
}
