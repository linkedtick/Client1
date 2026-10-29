package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

enum class RotMode {
    None, Strafe
}

enum class AuraMode {
    Single, Multi, Switch
}

enum class AutoWeaponMode {
    None, Switch, Spoof
}

class KillauraModule : Module("killaura", ModuleCategory.Combat) {

    // ---------- Targeting ----------
    private var rangeValue by floatValue("range", 7f, 2f..10f)
    private var cpsValue by intValue("cps", 20, 5..30)
    private var attackTick by intValue("attack_tick", 0, 0..20)
    private var packets by intValue("packets", 1, 1..50)
    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var antiBot by boolValue("anti_bot", true)

    // ---------- Rotation ----------
    private var rotMode by enumValue("rot_mode", RotMode.None, RotMode::class.java)
    private var rotSpeed by floatValue("rot_speed", 1.0f, 0.1f..50.0f)

    // ---------- Attack mode ----------
    private var mode by enumValue("mode", AuraMode.Single, AuraMode::class.java)

    // ---------- Auto weapon ----------
    private var autoWeaponMode by enumValue("auto_weapon", AutoWeaponMode.None, AutoWeaponMode::class.java)

    // ---------- TP aura ----------
    private var tpAuraEnabled by boolValue("tp_aura", false)
    private var teleportBehind by boolValue("tp_behind", false)
    private var tpSpeed by intValue("tp_speed", 100, 10..500)
    private var tpYOffset by intValue("tp_y_offset", 1, -10..10)
    private var keepDistance by floatValue("keep_distance", 1.2f, 0.5f..10f)

    // ---------- Strafe movement ----------
    private var strafe by boolValue("strafe", false)
    private val strafeSpeed by floatValue("strafe_speed", 2.5f, 1f..4f)
    private val strafeRadius by floatValue("strafe_radius", 2.5f, 1f..6f)

    // ---------- State ----------
    private var lastAttackTime = 0L
    private var ticksSinceLastAttack = 0
    private var tpCooldown = 0L
    private var strafeAngle = 0f
    private var switchTargetIndex = 0

    // Strafe rotation state
    private var currentRot = Vector3f.ZERO
    private var nukkitRotBodyRot = Vector3f.ZERO
    private var nukkitRotInitialized = false

    private fun Player.isBot(): Boolean {

        if (this is LocalPlayer) return false

        val playerListEntry = session.level.playerMap[this.uuid] ?: return true

        val name = playerListEntry.name?.toString() ?: ""
        if (name.isBlank()) return true

        val xuid = playerListEntry.xuid ?: ""
        if (xuid.isEmpty() || xuid == "0") return true
        if (name.trim().isEmpty()) return true

        return false
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (interceptablePacket.packet !is PlayerAuthInputPacket) return

        val packet = interceptablePacket.packet as PlayerAuthInputPacket
        val player = session.localPlayer
        val now = System.currentTimeMillis()

        // ---------- Rotation: aim at closest target ----------
        val targets = searchForTargets()
        if (targets.isNotEmpty()) {
            val target = targets[0]
            val aimPos = getTargetAimPosition(target)
            val angle = calculateAngle(player.vec3Position.add(0f, 1.62f, 0f), aimPos)

            when (rotMode) {
                RotMode.Strafe -> {
                    applyStrafeRotation(angle)
                }
                RotMode.None -> {
                    currentRot = angle
                    nukkitRotInitialized = false
                }
            }

            // Apply rotation to the PlayerAuthInputPacket
            packet.rotation = Vector3f.from(currentRot.x, currentRot.y, 0f)
        }

        // ---------- Attack timing ----------
        ticksSinceLastAttack++

        val cpsDelay = 1000L / cpsValue.coerceAtLeast(1)
        val tickReady = if (attackTick > 0) ticksSinceLastAttack >= attackTick else true
        val timeReady = now - lastAttackTime >= cpsDelay

        if (!tickReady || !timeReady) return

        if (targets.isEmpty()) return

        // ---------- Select target(s) based on mode ----------
        val attackTargets: List<Entity> = when (mode) {
            AuraMode.Single -> listOf(targets[0])
            AuraMode.Multi -> targets.take(3)
            AuraMode.Switch -> {
                if (switchTargetIndex >= targets.size) switchTargetIndex = 0
                listOf(targets[switchTargetIndex])
            }
        }

        // ---------- Auto weapon ----------
        if (autoWeaponMode != AutoWeaponMode.None) {
            val attackTarget = attackTargets.firstOrNull() ?: targets[0]
            val bestSlot = getBestWeaponSlot(attackTarget)
            if (bestSlot != -1) {
                spoofWeapon(bestSlot)
            }
        }

        // ---------- Perform attacks ----------
        for (target in attackTargets) {
            if (target is Player && FriendManager.isFriend(target.uuid)) continue

            if (tpAuraEnabled && now - tpCooldown >= tpSpeed) {
                teleportTo(target)
                tpCooldown = now
            }

            repeat(packets) {
                session.localPlayer.attack(target)
            }
        }

        if (mode == AuraMode.Switch) {
            switchTargetIndex = (switchTargetIndex + 1) % targets.size
        }

        lastAttackTime = now
        ticksSinceLastAttack = 0
    }

    private fun searchForTargets(): List<Entity> {
        val player = session.localPlayer
        return session.level.entityMap.values
            .filter { it.distance(player) <= rangeValue && it.distance(player) > 0.1f }
            .filter { it.isTarget() }
            .sortedBy { it.distance(player) }
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> {
                if (!playersOnly) return false
                if (antiBot && isBot()) return false
                true
            }
            is EntityUnknown -> mobsOnly && isMob()
            else -> false
        }
    }

    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }

    // ---------- Rotation math ----------

    /** Aim position: target's feet, slightly below center. */
    private fun getTargetAimPosition(target: Entity): Vector3f {
        val pos = target.vec3Position
        return Vector3f.from(pos.x, pos.y - 0.1f, pos.z)
    }

    /** Calculate yaw/pitch angle from one position to another. */
    private fun calculateAngle(from: Vector3f, to: Vector3f): Vector3f {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dz * dz).toFloat()
        val yaw = (atan2(dx.toDouble(), dz.toDouble()) * 180 / PI).toFloat()
        val pitch = (atan2(-dy.toDouble(), dist.toDouble()) * 180 / PI).toFloat()
        return Vector3f.from(pitch, yaw, 0f)
    }

    /** Smoothly rotate toward the target angle. */
    private fun applyStrafeRotation(targetAngle: Vector3f) {
        if (!nukkitRotInitialized || (currentRot.x == 0f && currentRot.y == 0f)) {
            currentRot = targetAngle
            nukkitRotBodyRot = Vector3f.from(currentRot.x, currentRot.y, 0f)
            nukkitRotInitialized = true
        }

        var yawDiff = targetAngle.y - currentRot.y
        var pitchDiff = targetAngle.x - currentRot.x

        while (yawDiff > 180f) yawDiff -= 360f
        while (yawDiff < -180f) yawDiff += 360f

        val yawDistance = abs(yawDiff)
        val pitchDistance = abs(pitchDiff)

        var yawChange = yawDiff
        var pitchChange = pitchDiff

        // Speed up rotation when far away
        var yawRate = rotSpeed
        var pitchRate = rotSpeed
        if (yawDistance > 75f) yawRate *= 1.5f
        if (pitchDistance > 75f) pitchRate *= 1.5f

        currentRot.y += yawChange * yawRate * 0.1f
        currentRot.x += pitchChange * pitchRate * 0.1f

        while (currentRot.y > 180f) currentRot.y -= 360f
        while (currentRot.y < -180f) currentRot.y += 360f
        currentRot.x = coerce(currentRot.x, -90f, 90f)

        // Body yaw follow (like Nukkit)
        var bodyYawDiff = currentRot.y - nukkitRotBodyRot.y
        while (bodyYawDiff > 180f) bodyYawDiff -= 360f
        while (bodyYawDiff < -180f) bodyYawDiff += 360f

        val bodyFollowThreshold = 45f
        if (abs(bodyYawDiff) > bodyFollowThreshold) {
            var bodyFollowRate = 0.08f
            if (abs(bodyYawDiff) > 75f) bodyFollowRate = 0.15f
            nukkitRotBodyRot.y += bodyYawDiff * bodyFollowRate
            while (nukkitRotBodyRot.y > 180f) nukkitRotBodyRot.y -= 360f
            while (nukkitRotBodyRot.y < -180f) nukkitRotBodyRot.y += 360f
        }
    }

    // ---------- TP aura ----------

    private fun teleportTo(entity: Entity) {
        val player = session.localPlayer
        val pos = entity.vec3Position

        val yawRad = Math.toRadians(entity.vec3Rotation.y.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        val tpPos = if (teleportBehind) {
            Vector3f.from(
                pos.x + behind.x * keepDistance,
                pos.y + tpYOffset,
                pos.z + behind.z * keepDistance
            )
        } else {
            val dir = pos.sub(player.vec3Position).normalize()
            Vector3f.from(
                pos.x - dir.x * keepDistance,
                pos.y + tpYOffset,
                pos.z - dir.z * keepDistance
            )
        }

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = tpPos
                rotation = entity.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )
    }

    // ---------- Strafe movement ----------

    private fun strafeAroundTarget(entity: Entity) {
        val pos = entity.vec3Position
        strafeAngle += strafeSpeed
        if (strafeAngle >= 360f) strafeAngle -= 360f

        val x = strafeRadius * cos(strafeAngle)
        val z = strafeRadius * sin(strafeAngle)

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                position = pos.add(x.toFloat(), 0f, z.toFloat())
                rotation = Vector3f.ZERO
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = true
                tick = session.localPlayer.tickExists
            }
        )
    }

    // ---------- Auto weapon ----------

    /** Score a hotbar slot for how good a weapon it is. Higher = better. */
    private fun weaponScore(item: ItemData): Float {
        if (!item.isValid() || item == ItemData.AIR) return 0f
        val id = item.definition.identifier
        return when {
            id.contains("netherite_sword") -> 10f
            id.contains("diamond_sword") -> 9f
            id.contains("iron_sword") -> 8f
            id.contains("stone_sword") -> 7f
            id.contains("gold_sword") -> 6f
            id.contains("wooden_sword") || id.contains("wood_sword") -> 5f
            id.contains("sword") -> 4f
            id.contains("axe") -> 3f
            else -> 0f
        }
    }

    private fun getBestWeaponSlot(target: Entity): Int {
        val inventory = session.localPlayer.inventory

        // Rain / water crystal check (slot with a special item)
        for (i in 0 until 9) {
            val item = inventory.content[i]
            if (item.isValid() && item != ItemData.AIR && item.definition.identifier.contains("crystal")) {
                return i
            }
        }

        val bestSlot = inventory.content.indices.take(9).maxByOrNull { weaponScore(inventory.content[it]) } ?: -1
        return if (bestSlot != -1 && weaponScore(inventory.content[bestSlot]) > 0) bestSlot else -1
    }

    /** Spoof holding the best weapon by sending MobEquipmentPacket. */
    private fun spoofWeapon(slot: Int) {
        val player = session.localPlayer
        val item = player.inventory.content[slot]
        val packet = MobEquipmentPacket()
        packet.runtimeEntityId = player.runtimeEntityId
        packet.item = item
        packet.hotbarSlot = slot
        packet.selectedSlot = slot
        session.serverBound(packet)
    }
}
