package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.PlayerUtils
import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
//
// One deliberate difference: Odin binds two keys of its own to drive the left and right click
// and waits for them to be held, while this uses the mouse buttons themselves as the trigger -
// hold the button you want spammed and it clicks at the chosen rate. The Terminator mode, the
// click timing with its random wobble, the whitelists and the Dungeon Breaker guard are theirs.
object AutoClick : Feature(
	"autoClick",
	"Clicks at your chosen rate while held.",
	subcategory = "Auto Click",
	displayName = "Auto Click"
) {
	private val settingWhiteListOnly = addSwitch(
		"whiteListOnly", false,
		"Only clicks a whitelisted item.", "Whitelist Only"
	)
	private val settingAllowBreaking = addSwitch(
		"allowBreaking", false,
		"Keeps breaking blocks while clicking.", "Allow Breaking"
	)
	private val settingBlockBreaker = addSwitch(
		"blockBreaker", true,
		"Never clicks with a Dungeon Breaker.", "Block Dungeon Breaker"
	)
	private val settingTerminatorOnly = addSwitch(
		"terminatorOnly", true,
		"Terminator and right click only.", "Terminator Only"
	)
	private val settingCps = addSlider(
		"cps", 5.0, 3.0, 15.0,
		"Terminator clicks per second.", "Clicks Per Second", step = 0.5
	)
	private val settingEnableLeftClick = addSwitch(
		"enableLeftClick", true,
		"Clicks with the left button.", "Enable Left Click"
	)
	private val settingEnableRightClick = addSwitch(
		"enableRightClick", true,
		"Clicks with the right button.", "Enable Right Click"
	)
	private val settingLeftCps = addSlider(
		"leftCps", 5.0, 3.0, 15.0,
		"Left clicks per second.", "Left Clicks Per Second", step = 0.5
	)
	private val settingRightCps = addSlider(
		"rightCps", 5.0, 3.0, 15.0,
		"Right clicks per second.", "Right Clicks Per Second", step = 0.5
	)
	// The whitelists are edited by the commands below and stored here as comma separated item
	// ids, so they are saved with the rest of the settings (see Config).
	private val settingLeftWhitelist = addText(
		"leftWhitelist", "",
		"Left whitelist, edited by /mm ac.", "Left Whitelist", maxLength = 512
	)
	private val settingRightWhitelist = addText(
		"rightWhitelist", "",
		"Right whitelist, edited by /mm ac.", "Right Whitelist", maxLength = 512
	)

	@Volatile
	private var enabled = false
	private var initialized = false

	// When the next left or right click may happen, rolled once each time one does: the gap is
	// the click rate plus a random wobble, which is what stops it sounding like a metronome.
	private var nextLeft = 0L
	private var nextRight = 0L

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

		val now = System.currentTimeMillis()

		if (settingTerminatorOnly.get()) {
			// The mode Odin ships on: hold the use button with a Terminator in hand and the
			// left clicks come out at the set rate.
			if (itemId(player.mainHandItem) != "TERMINATOR") return
			if (!mc.options.keyUse.isDown) return
			if (now < nextRight) return
			nextRight = now + clickDelay(settingCps.get())
			PlayerUtils.leftClick()
			return
		}

		if (settingBlockBreaker.get() && itemId(player.mainHandItem) == "DUNGEONBREAKER") return
		val held = held()
		val leftAllowed = !settingWhiteListOnly.get() || (held != null && held in whitelist(settingLeftWhitelist.get()))
		val rightAllowed = !settingWhiteListOnly.get() || (held != null && held in whitelist(settingRightWhitelist.get()))
		if (!leftAllowed && !rightAllowed) return

		val level = mc.level ?: return
		val hit = mc.hitResult as? BlockHitResult
		val left = leftAllowed && settingEnableLeftClick.get() && mc.options.keyAttack.isDown
		val right = rightAllowed && settingEnableRightClick.get() && mc.options.keyUse.isDown

		// Odin holds the attack key down here so block breaking keeps working while it clicks.
		// That key is the trigger in this port, so it is already down and the game is already
		// breaking the block - there is nothing to add.
		if (hit != null && !level.getBlockState(hit.blockPos).isAir && left && settingAllowBreaking.get()) return

		if (left && now >= nextLeft) {
			nextLeft = now + clickDelay(settingLeftCps.get())
			PlayerUtils.leftClick()
		}
		if (right && now >= nextRight) {
			nextRight = now + clickDelay(settingRightCps.get())
			PlayerUtils.rightClick()
		}
	}

	// The same wobble Odin uses: the click rate in milliseconds, give or take 60.
	private fun clickDelay(cps: Double): Long =
		(1000.0 / cps + (Random.nextDouble() - 0.5) * 60.0).toLong()

	// The SkyBlock id of an item ("TERMINATOR"), from the same place Odin reads it.
	private fun itemId(stack: ItemStack): String? =
		ItemUtils.extraAttributes(stack)?.get("id")?.asString()?.getOrNull()

	// What a whitelist entry names: the item's own uuid when it has one, then its SkyBlock id,
	// then its name - in that order, which is the order Odin looks in.
	private fun held(): String? {
		val stack = Minecraft.getInstance().player?.mainHandItem ?: return null
		val uuid = ItemUtils.extraAttributes(stack)?.get("uuid")?.asString()?.getOrNull()
		return uuid ?: itemId(stack) ?: stack.hoverName.string.noControlCodes()
	}

	private fun whitelist(raw: String): Set<String> =
		raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

	// --- The whitelist commands, run from MeowCommand while the item is held. ---

	fun addHeld(left: Boolean) {
		val item = held()
		if (item == null) {
			ChatUtils.sendMessage("Hold an item to whitelist.", true)
			return
		}
		val items = whitelist(setting(left).get())
		if (item in items) {
			ChatUtils.sendMessage("&f$item&7 is already in the ${side(left)} whitelist.", true)
			return
		}
		setting(left).set((items + item).joinToString(", "))
		ChatUtils.sendMessage("&f$item&7 added to the ${side(left)} whitelist.", true)
	}

	fun removeHeld(left: Boolean) {
		val item = held()
		if (item == null) {
			ChatUtils.sendMessage("Hold an item to remove from the whitelist.", true)
			return
		}
		val items = whitelist(setting(left).get())
		if (item !in items) {
			ChatUtils.sendMessage("&f$item&7 isn't in the ${side(left)} whitelist.", true)
			return
		}
		setting(left).set((items - item).joinToString(", "))
		ChatUtils.sendMessage("&f$item&7 removed from the ${side(left)} whitelist.", true)
	}

	fun clearWhitelist(which: String) {
		if (which != "right") setting(left = true).set("")
		if (which != "left") setting(left = false).set("")
		ChatUtils.sendMessage(
			when (which) {
				"left" -> "Left whitelist cleared."
				"right" -> "Right whitelist cleared."
				else -> "Both whitelists cleared."
			},
			true
		)
	}

	fun listWhitelists() {
		ChatUtils.sendMessage("Auto Click whitelist:", true)
		ChatUtils.sendMessage("&7Left: &f" + whitelist(settingLeftWhitelist.get()).joinToString(", ").ifEmpty { "empty" })
		ChatUtils.sendMessage("&7Right: &f" + whitelist(settingRightWhitelist.get()).joinToString(", ").ifEmpty { "empty" })
	}

	private fun setting(left: Boolean) = if (left) settingLeftWhitelist else settingRightWhitelist

	private fun side(left: Boolean) = if (left) "left" else "right"

	override fun onEnable() {
		enabled = true
		nextLeft = 0L
		nextRight = 0L
	}

	override fun onDisable() {
		enabled = false
	}
}
