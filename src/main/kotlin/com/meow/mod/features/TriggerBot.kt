package com.meow.mod.features

import com.meow.mod.api.PlayerUtils
import com.meow.mod.api.noControlCodes
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.DungeonUtils
import com.meow.mod.skyblock.M7Phases
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
object TriggerBot : Feature(
	"triggerBot",
	"Trigger bots for crystals and dungeon secrets.",
	subcategory = "Trigger Bot",
	displayName = "Trigger Bot",
	enabledByDefault = true
) {
	private val crystal = addSwitch(
		"crystal", false,
		"Automatically takes and places crystals.", "Crystal"
	)
	private val take = addSwitch(
		"take", true,
		"Takes crystals.", "Take"
	)
	private val place = addSwitch(
		"place", true,
		"Places crystals.", "Place"
	)
	private val secret = addSwitch(
		"secret", true,
		"Automatically clicks secrets.", "Secret"
	)
	private val delay = addSlider(
		"delay", 200.0, 0.0, 1000.0,
		"The delay between each secret click.", "Delay"
	)

	private var click = 0L
	private var click0 = 0L
	private val clicked = mutableMapOf<BlockPos, Long>()

	@Volatile
	private var enabled = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register {
			if (!enabled) return@register
			tick()
		}
		MeowEvents.onWorldLoad { clicked.clear() }
	}

	private fun tick() {
		val minecraft = Minecraft.getInstance()
		if (crystal.get()) crystalTick(minecraft)
		if (secret.get()) secretTick(minecraft)
	}

	private fun crystalTick(minecraft: Minecraft) {
		if (!DungeonUtils.inBoss) return
		if (DungeonUtils.getF7Phase() != M7Phases.P1) return
		if (System.currentTimeMillis() - click < 500) return

		val hit = minecraft.hitResult ?: return
		if (hit.type != HitResult.Type.ENTITY) return

		val entity = (hit as? EntityHitResult)?.entity ?: return
		val player = minecraft.player ?: return
		if (!take.get() && !place.get()) return

		if (take.get() && entity is EndCrystal) {
			PlayerUtils.rightClick()
			click = System.currentTimeMillis()
			return
		}

		if (!place.get()) return
		if (entity.name.string.noControlCodes() != "Energy Crystal Missing") return
		if (player.mainHandItem.displayName.string.noControlCodes() != "Energy Crystal") return

		PlayerUtils.rightClick()
		click = System.currentTimeMillis()
	}

	private fun secretTick(minecraft: Minecraft) {
		if (!DungeonUtils.inDungeons) return
		if (DungeonUtils.inBoss) return
		if (minecraft.gui.screen() != null) return

		val now = System.currentTimeMillis()
		clicked.entries.removeIf { it.value + 1000L <= now }

		if (now - click0 < delay.get().toLong()) return

		val hit = minecraft.hitResult ?: return
		if (hit.type != HitResult.Type.BLOCK) return

		val pos = (hit as? BlockHitResult)?.blockPos ?: return
		val level = minecraft.level ?: return
		val state = level.getBlockState(pos)

		if (clicked.containsKey(pos)) return
		if (!DungeonUtils.isSecret(state, pos)) return

		PlayerUtils.rightClick()
		click0 = now
		clicked[pos] = now
	}

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		clicked.clear()
	}
}
