package com.retrivedmods.wclient.game.module.visual

import android.annotation.SuppressLint
import android.graphics.*
import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.entity.Player
import com.retrivedmods.wclient.render.RenderOverlayView
import org.cloudburstmc.math.matrix.Matrix4f
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import kotlin.math.cos
import kotlin.math.sin

class ESPModule : Module("esp", ModuleCategory.Visual) {

    companion object {
        private var renderView: RenderOverlayView? = null
        fun setRenderView(view: RenderOverlayView) {
            renderView = view
        }

        /** Shared, reusable paint objects to avoid per-frame allocations on low-end devices. */
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val nametagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val nametagBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val nametagHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val nameShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        val distShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        val armorBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val armorBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        /** Reusable scratch objects to avoid allocations in the render loop. */
        val screenPts = ArrayList<Vector2f?>()
        val tempRect = Rect()
        val tempRectF = RectF()
        val tempInnerRect = RectF()
        val tempBarBgRect = RectF()
        val tempBarFillRect = RectF()
    }

    enum class BoxMode { None, Box2D, Box3D, Corner }
    enum class TracerPosition { Bottom, Top, Center }

    private val fov by floatValue("fov", 110f, 40f..110f)
    private val strokeWidth by floatValue("stroke_width", 2.5f, 1f..10f)

    private val colorRed by intValue("color_red", 230, 0..255)
    private val colorGreen by intValue("color_green", 57, 0..255)
    private val colorBlue by intValue("color_blue", 70, 0..255)

    private val showAllEntities by boolValue("show_all_entities", false)
    private val ignoreBots by boolValue("ignore_bots", true)
    private val boxMode by enumValue("box_mode", BoxMode.Box3D, BoxMode::class.java)

    private val tracers by boolValue("tracers", false)
    private val tracerPosition by enumValue("tracer_position", TracerPosition.Bottom, TracerPosition::class.java)

    private val showNames by boolValue("nametags", true)
    private val showDistance by boolValue("show_distance", true)
    private val showArmor by boolValue("show_armor", true)

    // Cached rendering state — avoids recomputing matrices every frame when nothing changed.
    private var cachedViewProj: Matrix4f? = null
    private var cachedLocalPlayerPos: Vector3f? = null
    private var cachedLocalPlayerYaw: Float = 0f
    private var cachedLocalPlayerPitch: Float = 0f
    private var cachedCanvasW: Int = -1
    private var cachedCanvasH: Int = -1
    private var cachedFov: Float = -1f

    private var namePaint: TextPaint? = null
    private var distancePaint: TextPaint? = null

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false

        val playerListEntry = session.level.playerMap[this.uuid] ?: return true

        val name = playerListEntry.name?.toString() ?: ""
        if (name.isBlank()) return true

        val xuid = playerListEntry.xuid ?: ""
        if (xuid.isEmpty() || xuid == "0") return true

        return false
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        when (packet) {
            is MobArmorEquipmentPacket -> {
                val entity = session.level.entityMap.values.firstOrNull {
                    it.runtimeEntityId == packet.runtimeEntityId
                } ?: return

                entity.inventory.onPacketBound(packet)
            }

            is MobEquipmentPacket -> {
                val entity = session.level.entityMap.values.firstOrNull {
                    it.runtimeEntityId == packet.runtimeEntityId
                } ?: return

                entity.inventory.onPacketBound(packet)
            }
        }
    }

    override fun onEnabled() {
        renderView?.invalidate()
    }

    override fun onDisabled() {
        renderView?.invalidate()
    }

    /** Call this whenever the local player moves/rotates or canvas size changes. */
    fun invalidateRenderCache() {
        cachedViewProj = null
    }

    fun render(canvas: Canvas) {
        if (!isEnabled || !isSessionCreated) return

        val localPlayer = session.localPlayer

        // Recompute view-projection matrix only when something actually changed.
        val needsMatrixUpdate =
            cachedViewProj == null ||
            cachedLocalPlayerPos != localPlayer.vec3Position ||
            cachedLocalPlayerYaw != localPlayer.rotationYaw ||
            cachedLocalPlayerPitch != localPlayer.rotationPitch ||
            cachedCanvasW != canvas.width ||
            cachedCanvasH != canvas.height ||
            cachedFov != fov

        if (needsMatrixUpdate) {
            val viewProj = Matrix4f.createPerspective(
                fov,
                canvas.width.toFloat() / canvas.height,
                0.1f,
                128f
            ).mul(
                Matrix4f.createTranslation(localPlayer.vec3Position)
                    .mul(rotateY(-localPlayer.rotationYaw - 180f))
                    .mul(rotateX(-localPlayer.rotationPitch))
                    .invert()
            )
            cachedViewProj = viewProj
            cachedLocalPlayerPos = localPlayer.vec3Position
            cachedLocalPlayerYaw = localPlayer.rotationYaw
            cachedLocalPlayerPitch = localPlayer.rotationPitch
            cachedCanvasW = canvas.width
            cachedCanvasH = canvas.height
            cachedFov = fov
        }

        val viewProj = cachedViewProj ?: return

        val entities = if (showAllEntities) {
            session.level.entityMap.values
        } else {
            session.level.entityMap.values.filterIsInstance<Player>()
        }

        if (entities.isEmpty()) return

        val filteredEntities = if (ignoreBots) {
            entities.filter { entity ->
                if (entity is Player) !entity.isBot() else true
            }
        } else {
            entities
        }

        if (filteredEntities.isEmpty()) return

        // Configure shared paints once per frame.
        companion.boxPaint.apply {
            strokeWidth = this@ESPModule.strokeWidth
            color = Color.rgb(colorRed, colorGreen, colorBlue)
        }

        filteredEntities.forEach { entity ->
            if (entity != localPlayer) {
                drawEntityBox(entity, viewProj, canvas)
            }
        }
    }

    private fun drawEntityBox(
        entity: Entity,
        matrix: Matrix4f,
        canvas: Canvas
    ) {
        val vertices = getEntityBoxVertices(entity)
        companion.screenPts.clear()
        vertices.mapNotNull {
            worldToScreen(it, matrix, canvas.width, canvas.height)
        }.forEach { companion.screenPts.add(it) }

        if (companion.screenPts.size < 8) return

        val minX = companion.screenPts.minOf { it?.x ?: Float.MAX_VALUE } ?: return
        val maxX = companion.screenPts.maxOf { it?.x ?: Float.MIN_VALUE } ?: return
        val minY = companion.screenPts.minOf { it?.y ?: Float.MAX_VALUE } ?: return
        val maxY = companion.screenPts.maxOf { it?.y ?: Float.MIN_VALUE } ?: return

        // Draw box based on mode
        when (boxMode) {
            BoxMode.None -> {} // Don't draw any box
            BoxMode.Box2D -> canvas.drawRect(minX, minY, maxX, maxY, companion.boxPaint)
            BoxMode.Corner -> drawCornerBox(canvas, companion.boxPaint, minX, minY, maxX, maxY)
            BoxMode.Box3D -> draw3DBox(canvas, companion.boxPaint, companion.screenPts)
        }

        if (tracers) drawTracer(canvas, minX, minY, maxX, maxY)
        if (showNames) drawEntityInfo(canvas, entity, minX, minY, maxX)
    }

    private data class ArmorPiece(val type: String, val durability: Float, val material: String)

    private fun getArmorInfo(player: Player): List<ArmorPiece> {
        val inv = player.inventory
        val list = mutableListOf<ArmorPiece>()

        if (inv.helmet != ItemData.AIR)
            list += ArmorPiece("helmet", getDurability(inv.helmet), getMaterial(inv.helmet))
        if (inv.chestplate != ItemData.AIR)
            list += ArmorPiece("chestplate", getDurability(inv.chestplate), getMaterial(inv.chestplate))
        if (inv.leggings != ItemData.AIR)
            list += ArmorPiece("leggings", getDurability(inv.leggings), getMaterial(inv.leggings))
        if (inv.boots != ItemData.AIR)
            list += ArmorPiece("boots", getDurability(inv.boots), getMaterial(inv.boots))

        return list
    }

    private fun getMaterial(item: ItemData): String {
        val id = item.definition?.identifier?.lowercase() ?: ""
        return when {
            "netherite" in id -> "netherite"
            "diamond" in id -> "diamond"
            "iron" in id -> "iron"
            "gold" in id -> "gold"
            "chain" in id -> "chain"
            "leather" in id -> "leather"
            "turtle" in id -> "turtle"
            else -> "unknown"
        }
    }

    private fun getDurability(item: ItemData): Float {
        val max = 400f
        return ((max - item.damage) / max).coerceIn(0f, 1f)
    }

    private fun getMaterialColor(material: String): Int {
        return when (material) {
            "netherite" -> Color.rgb(68, 58, 59)
            "diamond" -> Color.rgb(92, 219, 213)
            "iron" -> Color.rgb(211, 211, 213)
            "gold" -> Color.rgb(250, 238, 77)
            "chain" -> Color.rgb(127, 127, 127)
            "leather" -> Color.rgb(139, 90, 43)
            "turtle" -> Color.rgb(87, 166, 78)
            else -> Color.rgb(120, 120, 120)
        }
    }

    private fun getMaterialColorDark(material: String): Int {
        return when (material) {
            "netherite" -> Color.rgb(41, 35, 36)
            "diamond" -> Color.rgb(55, 131, 127)
            "iron" -> Color.rgb(127, 127, 129)
            "gold" -> Color.rgb(150, 143, 46)
            "chain" -> Color.rgb(76, 76, 76)
            "leather" -> Color.rgb(83, 54, 26)
            "turtle" -> Color.rgb(52, 100, 47)
            else -> Color.rgb(72, 72, 72)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun drawEntityInfo(
        canvas: Canvas,
        entity: Entity,
        minX: Float,
        minY: Float,
        maxX: Float
    ) {
        val name = if (entity is Player) entity.username else "Entity"
        val distance = entity.distance(session.localPlayer)

        val x = (minX + maxX) / 2
        val y = minY - 50

        val armor = if (showArmor && entity is Player) getArmorInfo(entity) else emptyList()

        // Lazily create text paints (they're relatively expensive).
        if (namePaint == null) {
            namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 36f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
        if (distancePaint == null) {
            distancePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 32f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
        }

        val np = namePaint!!
        val dp = distancePaint!!

        companion.tempRect.set()
        np.getTextBounds(name, 0, name.length, companion.tempRect)

        val distText = if (showDistance) "[%.1fm]".format(distance) else ""
        val distBounds = Rect()
        if (showDistance) {
            dp.getTextBounds(distText, 0, distText.length, distBounds)
        }

        val spacing = if (showDistance) 14f else 0f
        val totalWidth = companion.tempRect.width() + (if (showDistance) distBounds.width() + spacing else 0f)
        val maxHeight = if (showDistance) maxOf(companion.tempRect.height(), distBounds.height()) else companion.tempRect.height()

        val padding = 16f
        companion.tempRectF.set(
            x - totalWidth / 2 - padding,
            y - maxHeight - padding * 2.2f,
            x + totalWidth / 2 + padding,
            y + padding
        )

        companion.nametagBgPaint.color = Color.argb(160, 10, 10, 15)
        companion.nametagBorderPaint.color = Color.argb(100, 255, 255, 255)
        companion.nametagHighlightPaint.color = Color.argb(60, 255, 255, 255)

        val cornerRadius = 8f
        canvas.drawRoundRect(companion.tempRectF, cornerRadius, cornerRadius, companion.nametagBgPaint)

        companion.tempInnerRect.set(
            companion.tempRectF.left + 1f,
            companion.tempRectF.top + 1f,
            companion.tempRectF.right - 1f,
            companion.tempRectF.bottom - 1f
        )
        canvas.drawRoundRect(companion.tempInnerRect, cornerRadius - 0.5f, cornerRadius - 0.5f, companion.nametagHighlightPaint)
        canvas.drawRoundRect(companion.tempRectF, cornerRadius, cornerRadius, companion.nametagBorderPaint)

        val nameX = if (showDistance) {
            x - (distBounds.width() + spacing) / 2
        } else {
            x
        }
        val distX = x + (companion.tempRect.width() + spacing) / 2
        val textY = y - 8f

        companion.nameShadowPaint.color = Color.argb(180, 0, 0, 0)
        np.color = Color.rgb(255, 255, 255)
        canvas.drawText(name, nameX, textY + 1f, companion.nameShadowPaint)
        canvas.drawText(name, nameX, textY, np)

        if (showDistance) {
            companion.distShadowPaint.color = Color.argb(180, 0, 0, 0)
            dp.color = Color.rgb(180, 180, 190)
            canvas.drawText(distText, distX, textY + 1f, companion.distShadowPaint)
            canvas.drawText(distText, distX, textY, dp)
        }

        if (armor.isNotEmpty()) {
            val iconSize = 40f
            val iconSpacing = 6f
            val totalArmorWidth = armor.size * (iconSize + iconSpacing) - iconSpacing
            var ax = x - totalArmorWidth / 2
            val ay = y + 20

            armor.forEach {
                drawArmorIcon(canvas, it, ax, ay, iconSize)
                ax += iconSize + iconSpacing
            }
        }
    }

    private fun drawArmorIcon(
        canvas: Canvas,
        armor: ArmorPiece,
        x: Float,
        y: Float,
        size: Float
    ) {
        val matColor = getMaterialColor(armor.material)
        val matColorDark = getMaterialColorDark(armor.material)

        when (armor.type) {
            "helmet" -> drawHelmetIcon(canvas, x, y, size, matColor, matColorDark)
            "chestplate" -> drawChestplateIcon(canvas, x, y, size, matColor, matColorDark)
            "leggings" -> drawLeggingsIcon(canvas, x, y, size, matColor, matColorDark)
            "boots" -> drawBootsIcon(canvas, x, y, size, matColor, matColorDark)
        }

        val barHeight = 3f
        val barY = y + size + 2f
        val barWidth = size * armor.durability

        companion.armorBarBgPaint.color = Color.argb(100, 50, 50, 50)
        companion.armorBarFillPaint.color = when {
            armor.durability > 0.6f -> Color.argb(200, 100, 255, 100)
            armor.durability > 0.3f -> Color.argb(200, 255, 200, 100)
            else -> Color.argb(200, 255, 100, 100)
        }

        companion.tempBarBgRect.set(x, barY, x + size, barY + barHeight)
        companion.tempBarFillRect.set(x, barY, x + barWidth, barY + barHeight)

        canvas.drawRoundRect(companion.tempBarBgRect, 1.5f, 1.5f, companion.armorBarBgPaint)
        canvas.drawRoundRect(companion.tempBarFillRect, 1.5f, 1.5f, companion.armorBarFillPaint)
    }

    private fun drawHelmetIcon(canvas: Canvas, x: Float, y: Float, size: Float, mainColor: Int, darkColor: Int) {
        val pixelSize = size / 16f
        val startX = x + size * 0.125f
        val startY = y + size * 0.125f

        val helmetPattern = listOf(
            listOf(0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(1,1,1,2,2,1,1,1,1,1,1,2,2,1,1,1),
            listOf(1,1,2,2,2,2,1,1,1,1,2,2,2,2,1,1),
            listOf(1,1,2,2,2,2,1,1,1,1,2,2,2,2,1,1),
            listOf(1,1,1,2,2,1,1,1,1,1,1,2,2,1,1,1),
            listOf(1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1),
            listOf(1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1),
            listOf(1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1),
            listOf(1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0)
        )

        drawPixelArtWithColors(canvas, startX, startY, pixelSize, helmetPattern, mainColor, darkColor)
    }

    private fun drawChestplateIcon(canvas: Canvas, x: Float, y: Float, size: Float, mainColor: Int, darkColor: Int) {
        val pixelSize = size / 16f
        val startX = x + size * 0.125f
        val startY = y + size * 0.125f

        val chestplatePattern = listOf(
            listOf(0,1,1,1,1,0,0,0,0,0,0,1,1,1,1,0),
            listOf(1,1,1,1,1,1,0,0,0,0,1,1,1,1,1,1),
            listOf(1,1,2,2,1,1,1,0,0,1,1,1,2,2,1,1),
            listOf(1,1,2,2,1,1,1,1,1,1,1,1,2,2,1,1),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,0,0,1,1,1,1,1,1,1,1,1,1,0,0,0),
            listOf(0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0),
            listOf(0,0,0,0,0,0,1,1,1,1,0,0,0,0,0,0)
        )

        drawPixelArtWithColors(canvas, startX, startY, pixelSize, chestplatePattern, mainColor, darkColor)
    }

    private fun drawLeggingsIcon(canvas: Canvas, x: Float, y: Float, size: Float, mainColor: Int, darkColor: Int) {
        val pixelSize = size / 16f
        val startX = x + size * 0.125f
        val startY = y + size * 0.125f

        val leggingsPattern = listOf(
            listOf(0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,2,2,1,1,0,0,1,1,2,2,1,1,0),
            listOf(0,1,1,2,2,1,1,0,0,1,1,2,2,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,0,1,1,1,1,0,0,0,0,1,1,1,1,0,0),
            listOf(0,0,0,1,1,0,0,0,0,0,0,1,1,0,0,0)
        )

        drawPixelArtWithColors(canvas, startX, startY, pixelSize, leggingsPattern, mainColor, darkColor)
    }

    private fun drawBootsIcon(canvas: Canvas, x: Float, y: Float, size: Float, mainColor: Int, darkColor: Int) {
        val pixelSize = size / 16f
        val startX = x + size * 0.125f
        val startY = y + size * 0.125f

        val bootsPattern = listOf(
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            listOf(0,0,1,1,1,1,0,0,0,0,1,1,1,1,0,0),
            listOf(0,1,1,2,2,1,1,0,0,1,1,2,2,1,1,0),
            listOf(0,1,1,2,2,1,1,0,0,1,1,2,2,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(0,1,1,1,1,1,1,0,0,1,1,1,1,1,1,0),
            listOf(1,1,1,1,1,1,1,0,0,1,1,1,1,1,1,1),
            listOf(1,1,1,1,1,1,0,0,0,0,1,1,1,1,1,1),
            listOf(0,1,1,1,1,0,0,0,0,0,0,1,1,1,1,0)
        )

        drawPixelArtWithColors(canvas, startX, startY, pixelSize, bootsPattern, mainColor, darkColor)
    }

    private fun drawPixelArtWithColors(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        pixelSize: Float,
        pattern: List<List<Int>>,
        mainColor: Int,
        darkColor: Int
    ) {
        val pp = companion.pixelPaint
        for (row in pattern.indices) {
            val patternRow = pattern[row]
            for (col in patternRow.indices) {
                val pixel = patternRow[col]
                if (pixel == 0) continue

                val px = startX + col * pixelSize
                val py = startY + row * pixelSize

                pp.color = when (pixel) {
                    1 -> mainColor
                    2 -> darkColor
                    else -> mainColor
                }

                companion.tempRectF.set(px, py, px + pixelSize, py + pixelSize)
                canvas.drawRect(companion.tempRectF, pp)
            }
        }
    }

    private fun getEntityBoxVertices(entity: Entity): Array<Vector3f> {
        val w = 0.6f
        val h = if (entity is Player) 1.8f else 1.0f
        val hw = w / 2
        val p = entity.vec3Position

        return arrayOf(
            Vector3f.from(p.x - hw, p.y, p.z - hw),
            Vector3f.from(p.x + hw, p.y, p.z - hw),
            Vector3f.from(p.x + hw, p.y + h, p.z - hw),
            Vector3f.from(p.x - hw, p.y + h, p.z - hw),
            Vector3f.from(p.x - hw, p.y, p.z + hw),
            Vector3f.from(p.x + hw, p.y, p.z + hw),
            Vector3f.from(p.x + hw, p.y + h, p.z + hw),
            Vector3f.from(p.x - hw, p.y + h, p.z + hw)
        )
    }

    private fun worldToScreen(
        pos: Vector3f,
        m: Matrix4f,
        w: Int,
        h: Int
    ): Vector2f? {
        val rw = m.get(3, 0) * pos.x + m.get(3, 1) * pos.y + m.get(3, 2) * pos.z + m.get(3, 3)
        if (rw <= 0.01f) return null

        val inv = 1f / rw
        val x = w / 2f + (m.get(0, 0) * pos.x + m.get(0, 1) * pos.y + m.get(0, 2) * pos.z + m.get(0, 3)) * inv * w / 2f
        val y = h / 2f - (m.get(1, 0) * pos.x + m.get(1, 1) * pos.y + m.get(1, 2) * pos.z + m.get(1, 3)) * inv * h / 2f
        return Vector2f.from(x, y)
    }

    private fun rotateX(a: Float): Matrix4f {
        val r = Math.toRadians(a.toDouble())
        return Matrix4f.from(
            1f, 0f, 0f, 0f,
            0f, cos(r).toFloat(), -sin(r).toFloat(), 0f,
            0f, sin(r).toFloat(), cos(r).toFloat(), 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun rotateY(a: Float): Matrix4f {
        val r = Math.toRadians(a.toDouble())
        return Matrix4f.from(
            cos(r).toFloat(), 0f, sin(r).toFloat(), 0f,
            0f, 1f, 0f, 0f,
            -sin(r).toFloat(), 0f, cos(r).toFloat(), 0f,
            0f, 0f, 0f, 1f
        )
    }


    private fun drawCornerBox(c: Canvas, p: Paint, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val len = (maxX - minX) / 4
        c.drawLine(minX, minY, minX + len, minY, p)
        c.drawLine(minX, minY, minX, minY + len, p)
        c.drawLine(maxX, minY, maxX - len, minY, p)
        c.drawLine(maxX, minY, maxX, minY + len, p)
        c.drawLine(minX, maxY, minX + len, maxY, p)
        c.drawLine(minX, maxY, minX, maxY - len, p)
        c.drawLine(maxX, maxY, maxX - len, maxY, p)
        c.drawLine(maxX, maxY, maxX, maxY - len, p)
    }

    private fun draw3DBox(c: Canvas, p: Paint, pts: List<Vector2f?>) {
        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )
        edges.forEach { (a, b) ->
            val pa = pts[a] ?: return@forEach
            val pb = pts[b] ?: return@forEach
            c.drawLine(pa.x, pa.y, pb.x, pb.y, p)
        }
    }

    private fun drawTracer(c: Canvas, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val start = when (tracerPosition) {
            TracerPosition.Bottom -> Vector2f.from(c.width / 2f, c.height.toFloat())
            TracerPosition.Top -> Vector2f.from(c.width / 2f, 0f)
            TracerPosition.Center -> Vector2f.from(c.width / 2f, c.height / 2f)
        }
        val end = Vector2f.from((minX + maxX) / 2f, maxY)

        val tp = Paint(companion.boxPaint).apply {
            shader = LinearGradient(
                start.x, start.y, end.x, end.y,
                Color.argb(80, colorRed, colorGreen, colorBlue),
                Color.argb(255, colorRed, colorGreen, colorBlue),
                Shader.TileMode.CLAMP
            )
            strokeWidth *= 0.7f
        }

        c.drawLine(start.x, start.y, end.x, end.y, tp)
    }
}
