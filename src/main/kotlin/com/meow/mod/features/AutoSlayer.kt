package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ClickWait
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.api.noControlCodes
import com.meow.mod.skyblock.SlayerMenu
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.InteractionHand
import org.slf4j.LoggerFactory

// The slayer loop after a boss dies: Hypixel announces the kill, the Maddox Batphone in the
// hotbar opens Maddox's menu, and that menu is where the finished quest's reward is collected
// and the same quest is started again.
//
// Which item to press is decided from lore alone (see SlayerMenu): Hypixel gives the finished
// quest, the quest list and the start button as the slayer's own drop, so the item is no help.
object AutoSlayer : Feature(
	"autoSlayer",
	"Collects, then requeues the quest.",
	subcategory = "Auto Slayer",
	displayName = "Auto Slayer",
	enabledByDefault = false
) {
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// Hypixel's line for a slayer boss kill, and the only trigger: there is nothing to do here
	// when a boss has not just died.
	private const val BOSS_SLAIN = "NICE! SLAYER BOSS SLAIN!"

	// The Batphone, matched on the part of its name that is its own ("Maddox Batphone").
	private const val BATPHONE = "batphone"

	// The hotbar, which is all this module will look at: an item anywhere else would have to be
	// moved first, and moving the player's items around is not worth doing on their behalf.
	private val HOTBAR = 0 until 9

	// How long the menu is waited for once the Batphone is used, how long the whole run may
	// take, how long a click is given to change the menu before it is pressed again, and how
	// often that may happen.
	private const val MENU_TIMEOUT = 8_000L
	private const val RUN_TIMEOUT = 25_000L
	private const val RETRY_MS = 1_500L
	private const val MAX_RETRIES = 3

	// The held slot has to be the Batphone before the right click lands on it, and a screen
	// that was open has just been closed, so the use is left a moment longer.
	private const val SWITCH_MS = 200L
	private const val AFTER_CLOSE_MS = 600L

	// The menu closes itself once the quest starts; if it is still there a moment later it is
	// closed so the player is not left standing in it.
	private const val CLOSE_MS = 700L

	private enum class Stage { IDLE, USE_PHONE, MENU }

	@Volatile
	private var enabled = false
	private var initialized = false

	private var stage = Stage.IDLE

	// Set by the chat line and picked up by the next tick, so nothing clicks from inside a
	// chat callback.
	private var armed = false

	// The boss the finished quest was against, read off its completion item, so the quest that
	// is started again is the same tier rather than whichever one the menu lists first.
	private var wantedBoss: String? = null

	private val clickWait = ClickWait(RETRY_MS, MAX_RETRIES)
	private var lastSlot = -1
	private var lastAction = ""
	private var lastBoss = ""

	private var useAt = 0L
	private var menuDeadline = 0L
	private var deadline = 0L

	private var closeScreen: AbstractContainerScreen<*>? = null
	private var closeAt = 0L

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
		ClientReceiveMessageEvents.GAME.register { message, _ -> onChatMessage(message.string) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChatMessage(message.string) }
	}

	override fun onEnable() {
		enabled = true
		reset()
	}

	override fun onDisable() {
		enabled = false
		reset()
	}

	private fun reset() {
		stage = Stage.IDLE
		armed = false
		wantedBoss = null
		clickWait.reset()
		lastSlot = -1
		lastAction = ""
		lastBoss = ""
		useAt = 0L
		menuDeadline = 0L
		deadline = 0L
		closeScreen = null
		closeAt = 0L
	}

	private fun onChatMessage(text: String) {
		if (!enabled) return
		if (!text.contains(BOSS_SLAIN, ignoreCase = true)) return
		if (stage != Stage.IDLE) {
			LOGGER.info("[MeowMod] Auto Slayer: a boss died while the last one is still being handled, ignoring it")
			return
		}
		armed = true
	}

	private fun tick() {
		if (!enabled) return
		val mc = Minecraft.getInstance()
		if (mc.player == null) return

		if (closeAt > 0L && System.currentTimeMillis() >= closeAt) {
			closeAt = 0L
			val screen = mc.gui.screen()
			if (screen != null && screen === closeScreen) screen.onClose()
			closeScreen = null
		}

		if (armed) {
			armed = false
			openBatphone(mc)
			return
		}

		when (stage) {
			Stage.IDLE -> return
			Stage.USE_PHONE -> useBatphone(mc)
			Stage.MENU -> stepMenu(mc)
		}
	}

	// Looks the Batphone up in the hotbar and holds it. Not finding it is the one failure that
	// turns the module off: without the phone there is no menu to work, so leaving it on would
	// only mean it fails again on the next boss.
	private fun openBatphone(mc: Minecraft) {
		val player = mc.player ?: return
		val inventory = player.inventory
		val slot = HOTBAR.firstOrNull { i ->
			val stack = inventory.getItem(i)
			!stack.isEmpty && stack.hoverName.string.noControlCodes().contains(BATPHONE, ignoreCase = true)
		}

		if (slot == null) {
			LOGGER.warn("[MeowMod] Auto Slayer: no Maddox Batphone in the hotbar, turning the module off")
			ChatUtils.sendMessage("&cAuto Slayer: no Maddox Batphone in the hotbar, so it turned itself off.", true)
			reset()
			configSwitch.set(false)
			return
		}

		// A screen on top swallows the right click, so anything that is still open is closed
		// first - the player may well have been typing when the boss died.
		val open = mc.gui.screen()
		if (open != null) {
			LOGGER.info("[MeowMod] Auto Slayer: closing {} first, it would swallow the right click", open.javaClass.simpleName)
			open.onClose()
		}

		inventory.setSelectedSlot(slot)
		LOGGER.info("[MeowMod] Auto Slayer: holding the Batphone from hotbar slot $slot")
		stage = Stage.USE_PHONE
		useAt = System.currentTimeMillis() + humanDelay(if (open != null) AFTER_CLOSE_MS else SWITCH_MS)
	}

	private fun useBatphone(mc: Minecraft) {
		if (System.currentTimeMillis() < useAt) return
		val player = mc.player ?: return
		mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
		LOGGER.info("[MeowMod] Auto Slayer: called Maddox, waiting for his menu")
		stage = Stage.MENU
		val now = System.currentTimeMillis()
		menuDeadline = now + humanDelay(MENU_TIMEOUT)
		deadline = now + humanDelay(RUN_TIMEOUT)
	}

	private fun stepMenu(mc: Minecraft) {
		val now = System.currentTimeMillis()
		val container = mc.gui.screen() as? AbstractContainerScreen<*>
		if (container == null) {
			// Waiting is only worth it for as long as the menu could still be on its way.
			if (now > menuDeadline) giveUp("Maddox's menu never opened")
			return
		}
		if (now > deadline) {
			giveUp("ran out of time in Maddox's menu")
			return
		}

		val signature = ScreenUtils.signature(container)
		// A click is answered by the menu changing, so while the menu is still the one that was
		// clicked in, nothing else is pressed: the same click is repeated instead, a bounded
		// number of times, in case it never reached the server.
		if (clickWait.unanswered(signature)) {
			if (clickWait.retryDue(signature) && lastSlot >= 0) {
				LOGGER.info("[MeowMod] Auto Slayer: the menu did not change, pressing \"$lastAction\" again (slot $lastSlot)")
				ScreenUtils.click(lastSlot)
			}
			return
		}

		val entry = SlayerMenu.choose(entriesOf(container), wantedBoss)
		if (entry == null) {
			// Nothing recognisable on the menu. Before anything has been clicked the menu may
			// simply not have filled in yet, so it gets a moment; once a click has happened the
			// menu is telling us there is nothing left to do.
			if (lastAction.isEmpty()) return
			giveUp("nothing left to click in Maddox's menu")
			return
		}

		if (entry.action == SlayerMenu.COLLECT && entry.quest != null) {
			wantedBoss = entry.quest
			LOGGER.info("[MeowMod] Auto Slayer: the finished quest was \"${entry.quest}\", that is what will be started again")
		}

		LOGGER.info(
			"[MeowMod] Auto Slayer: clicking \"${entry.action}\" at slot ${entry.slot}" +
				if (entry.boss.isEmpty()) "" else " (\"${entry.boss}\")"
		)
		ScreenUtils.click(entry.slot)
		clickWait.clicked(signature)
		lastSlot = entry.slot
		lastAction = entry.action
		lastBoss = entry.boss

		if (entry.action == SlayerMenu.START) {
			LOGGER.info("[MeowMod] Auto Slayer: the start button was pressed")
			// The run is over, so its state goes first: reset() clears the closing screen too, and
			// arming the close before it would leave the menu to close itself or not at all.
			reset()
			closeScreen = container
			closeAt = System.currentTimeMillis() + humanDelay(CLOSE_MS)
		}
	}

	private fun entriesOf(container: AbstractContainerScreen<*>): List<SlayerMenu.Entry> {
		// The player's own inventory is the last 36 slots of the menu and is never the menu.
		val playerSlots = ScreenUtils.inventorySlots(container).mapTo(HashSet()) { it.index }
		val entries = mutableListOf<SlayerMenu.Entry>()
		for (slot in container.menu.slots) {
			if (slot.index in playerSlots) continue
			val stack = slot.item
			if (stack.isEmpty) continue
			val lore = ItemUtils.lore(stack)
			val action = SlayerMenu.actionOf(lore) ?: continue
			val quest = SlayerMenu.bossOf(lore)
			entries.add(
				SlayerMenu.Entry(
					slot.index,
					action,
					quest ?: stack.hoverName.string.noControlCodes().trim(),
					quest
				)
			)
		}
		return entries
	}

	// Gives up on the run but leaves the module on: a menu that did not open is not something
	// to switch the module off over, unlike a Batphone that is simply not there.
	private fun giveUp(why: String) {
		LOGGER.warn("[MeowMod] Auto Slayer: $why, giving up" + if (lastAction.isEmpty()) "" else " after \"$lastAction\"${bossSuffix()}")
		ChatUtils.sendMessage("&cAuto Slayer: gave up - $why.", true)
		reset()
	}

	private fun bossSuffix(): String = if (lastBoss.isEmpty()) "" else " (\"$lastBoss\")"
}
