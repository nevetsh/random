package com.meow.mod.features

import com.meow.mod.api.PlayerUtils
import com.meow.mod.api.humanDelay
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
	"Trigger bots for crystals and secrets.",
	// Its own page: the auto clicker that used to share it was retired as unsafe.
	subcategory = "Trigger Bot",
	displayName = "Trigger Bot",
	enabledByDefault = true,
	heading = "Trigger Bot"
) {
	private val crystal = addSwitch(
		"crystal", false,
		"Takes and places crystals.", "Crystal",
		heading = "Trigger Bot"
	)
	private val take = addSwitch(
		"take", true,
		"Takes crystals.", "Take",
		heading = "Trigger Bot"
	)
	private val place = addSwitch(
		"place", true,
		"Places crystals.", "Place",
		heading = "Trigger Bot"
	)
	private val secret = addSwitch(
		"secret", true,
		"Clicks secrets.", "Secret",
		heading = "Trigger Bot"
	)
	private val delay = addSlider(
		"delay", 200.0, 0.0, 1000.0,
		"Delay between secret clicks.", "Delay",
		heading = "Trigger Bot"
	)

	// Cooldown between crystal clicks, before its human wobble.
	private const val CRYSTAL_DELAY = 500L

	private var click = 0L
	private var click0 = 0L

	// The waits until the next crystal click and the next secret click, rolled once each
	// time one happens.
	private var crystalWait = 0L
	private var secretWait = 0L
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
		if (System.currentTimeMillis() - click < crystalWait) return

		val hit = minecraft.hitResult ?: return
		if (hit.type != HitResult.Type.ENTITY) return

		val entity = (hit as? EntityHitResult)?.entity ?: return
		val player = minecraft.player ?: return
		if (!take.get() && !place.get()) return

		if (take.get() && entity is EndCrystal) {
			PlayerUtils.rightClick()
			click = System.currentTimeMillis()
			crystalWait = humanDelay(CRYSTAL_DELAY)
			return
		}

		if (!place.get()) return
		if (entity.name.string.noControlCodes() != "Energy Crystal Missing") return
		if (player.mainHandItem.displayName.string.noControlCodes() != "Energy Crystal") return

		PlayerUtils.rightClick()
		click = System.currentTimeMillis()
		crystalWait = humanDelay(CRYSTAL_DELAY)
	}

	private fun secretTick(minecraft: Minecraft) {
		if (!DungeonUtils.inDungeons) return
		if (DungeonUtils.inBoss) return
		if (minecraft.gui.screen() != null) return

		val now = System.currentTimeMillis()
		clicked.entries.removeIf { it.value + 1000L <= now }

		if (now - click0 < secretWait) return

		val hit = minecraft.hitResult ?: return
		if (hit.type != HitResult.Type.BLOCK) return

		val pos = (hit as? BlockHitResult)?.blockPos ?: return
		val level = minecraft.level ?: return
		val state = level.getBlockState(pos)

		if (clicked.containsKey(pos)) return
		if (!DungeonUtils.isSecret(state, pos)) return

		PlayerUtils.rightClick()
		click0 = now
		secretWait = humanDelay(delay.get().toLong())
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
