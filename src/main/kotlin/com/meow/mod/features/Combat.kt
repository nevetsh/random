package com.meow.mod.features

import com.meow.mod.api.ClickLock
import com.meow.mod.api.PlayerUtils
import com.meow.mod.api.cpsDelay
import com.meow.mod.api.humanDelay
import com.meow.mod.skyblock.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.HitResult

// Attacks whatever living thing the player is aiming at, as long as it is inside the reach range,
// at the configured rate. It is Auto Click's pacing pointed at a target rather than a held
// button: the rate carries the same random wobble, and the first click after a target is picked
// up waits a random moment (its own slider) so the start never lands on a fixed beat. Losing the
// target rearms that first-click wait for the next one.
//
// Aim does not have to be exact: the aim ray is tested against each candidate's hitbox grown by a
// small tolerance, so an entity sitting just off the crosshair is still picked up and clicked at.
// The click itself is a real left click through the vanilla path, not a hand-made attack packet,
// so the game decides what is hit; nothing is clicked while the crosshair is on a block, where a
// real click would start mining.
//
// Players and armour stands are left alone: the trigger is for mobs, and swinging at the players
// around you (or the stands Hypixel builds its holograms out of) is never what was meant.
object Combat : Feature(
	"combat",
	"Left-clicks entities you look at.",
	subcategory = "Auto Click",
	displayName = "Combat",
	heading = "Combat",
	privateSwitch = true
) {
	// Off / Always is the module's own control, listed instead of the plain on/off switch.
	// Always clicks whatever is looked at, Off is off. Driving the real switch keeps the keybind
	// and the enable/disable wiring working unchanged.
	private val settingMode = addChoice(
		"mode",
		MODE_ALWAYS,
		listOf(MODE_OFF, MODE_ALWAYS),
		"Click whatever you look at.", "Mode",
		heading = "Combat"
	)
	private val settingCps = addSlider(
		"cps", 10.0, 1.0, 20.0,
		"Combat clicks per second.", "Combat CPS",
		heading = "Combat", step = 0.5
	)
	private val settingRange = addSlider(
		"range", 3.0, 1.0, 6.0,
		"How far to reach for a target.", "Range",
		heading = "Combat", step = 0.5
	)
	private val settingFirstDelay = addSlider(
		"firstDelay", 150.0, 0.0, 1000.0,
		"Wait before the first click.", "First Click (ms)",
		heading = "Combat"
	)

	@Volatile
	private var enabled = false

	private var initialized = false

	// When the next click may happen, and the entity the last one was aimed at: a target that
	// differs from it is new, and gets the first-click wait before anything is clicked.
	private var nextClick = 0L
	private var target: java.util.UUID? = null

	init {
		configSwitch.control = settingMode
		settingMode.onChange { onModeChanged(it) }
	}

	private fun onModeChanged(value: String) {
		configSwitch.set(value != MODE_OFF)
	}

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register {
			if (!enabled) return@register
			tick()
		}
	}

	private fun tick() {
		val mc = Minecraft.getInstance()
		if (mc.gui.screen() != null) return
		val player = mc.player ?: return
		if (player.isUsingItem) return
		if (mc.gameMode?.isDestroying == true) return

		val entity = findTarget(mc, player)
		if (entity == null) {
			target = null
			return
		}

		val now = System.currentTimeMillis()
		if (target != entity.uuid) {
			// A new target: hold the first click for the configured moment.
			target = entity.uuid
			nextClick = now + firstDelay()
			return
		}
		if (now < nextClick) return
		nextClick = now + cpsDelay(settingCps.get())
		click(mc)
	}

	// The nearest living thing the aim ray comes close enough to, or null. The ray is tested
	// against each candidate's box grown by the aim tolerance, so an entity does not have to sit
	// exactly on the crosshair to be picked up.
	private fun findTarget(mc: Minecraft, player: LocalPlayer): Entity? {
		val level = mc.level ?: return null
		val reach = settingRange.get()
		val eye = player.eyePosition
		val end = eye.add(player.getLookAngle().scale(reach))
		val search = player.boundingBox.inflate(reach + AIM_TOLERANCE)
		var best: Entity? = null
		var bestDist = Double.MAX_VALUE
		for (entity in level.getEntities(player, search) { it !== player && !isIgnored(it, player) }) {
			if (entity !is LivingEntity) continue
			val hit = entity.boundingBox.inflate(AIM_TOLERANCE).clip(eye, end)
			if (hit.isEmpty()) continue
			val dist = eye.distanceTo(hit.get())
			if (dist < bestDist) {
				bestDist = dist
				best = entity
			}
		}
		return best
	}

	// A real left click through the vanilla path: the game resolves it against the crosshair, so
	// no attack packet is made up here. A click while the crosshair is on a block would mine it
	// instead of attacking, so that is skipped.
	private fun click(mc: Minecraft) {
		if (mc.hitResult?.type == HitResult.Type.BLOCK) return
		PlayerUtils.leftClick()
	}

	// The random wait before the first click on a freshly picked-up target: the configured
	// first-click delay plus or minus a little.
	private fun firstDelay(): Long {
		val base = settingFirstDelay.get().toLong()
		if (base <= 0L) return 0L
		return humanDelay(base).coerceAtLeast(1L)
	}

	// What the trigger leaves alone. An armour stand is ignored when it is a marker, which is
	// how Hypixel builds the holograms and displays that are never a target; a visible armour
	// stand is a mob and is fair game. A player-shaped entity inside a dungeon is one of
	// Hypixel's custom mobs - it lists them in the tab list alongside the real players - so it
	// stays hittable there; outside a dungeon the shape is a real player and is left alone.
	private fun isIgnored(entity: Entity, self: LocalPlayer): Boolean {
		if (entity is ArmorStand) return entity.isMarker
		if (entity !is Player) return false
		if (DungeonUtils.inDungeons) return false
		if (entity.uuid == self.uuid) return true
		val listed = self.connection.getListedOnlinePlayers()
		return listed.any { it.profile.id == entity.uuid }
	}

	// The player's own left click is shut off the whole time the module is on: the only attacks
	// that go out are the module's own. It opens again on Off.
	private fun updateClickLock() {
		ClickLock.setBlocked(enabled)
	}

	override fun onEnable() {
		enabled = true
		nextClick = 0L
		target = null
		updateClickLock()
	}

	override fun onDisable() {
		enabled = false
		target = null
		updateClickLock()
	}

	// How far off the crosshair an entity may sit and still be picked up, in blocks.
	private const val AIM_TOLERANCE = 0.35

	// The dropdown's options, kept as constants so the comparisons are the values themselves.
	private const val MODE_OFF = "Off"
	private const val MODE_ALWAYS = "Always"
}
