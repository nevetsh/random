package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ClickWait
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.api.noControlCodes
import com.meow.mod.skyblock.SlayerMenu
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.slf4j.LoggerFactory

// Maddox's menu, worked for the player while it is open: the finished quest's reward is
// collected, then the same quest is started again.
//
// The module does not open that menu. It never selects a hotbar slot, it does not press the
// Maddox Batphone and it does not use an item: the player opens the menu (the Batphone, the
// SkyBlock menu, whatever they prefer) and this only clicks inside it. No menu, nothing to do,
// and a container that is not Maddox's menu is left exactly as it is.
//
// One run per menu: the start button ends it, and the menu is then left alone until it has been
// closed, so a menu the server opens next cannot be clicked through a second time by accident.
//
// Which item to press is decided from lore alone (see SlayerMenu): Hypixel gives the finished
// quest, the quest list and the start button as the slayer's own drop, so the item is no help.
object AutoSlayer : Feature(
	"autoSlayer",
	"Clicks through Maddox's menu.",
	subcategory = "Automation",
	heading = "Auto Slayer",
	displayName = "Auto Slayer",
	enabledByDefault = false
) {
	private val delayData = addSlider(
		"delay",
		300.0,
		50.0,
		1000.0,
		"Delay between clicks, plus 10-20%.",
		"Click Delay (ms)"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// How long a single run may take, how long a click is given to change the menu before it is
	// pressed again, and how often that may happen.
	private const val RUN_TIMEOUT = 25_000L
	private const val RETRY_MS = 1_500L
	private const val MAX_RETRIES = 3

	// The menu closes itself once the quest starts; if it is still there a moment later it is
	// closed so the player is not left standing in it.
	private const val CLOSE_MS = 700L

	@Volatile
	private var enabled = false
	private var initialized = false

	// The boss the finished quest was against, read off its completion item, so the quest that
	// is started again is the same tier rather than whichever one the menu lists first.
	private var wantedBoss: String? = null

	private val clickWait = ClickWait(RETRY_MS, MAX_RETRIES)
	private var lastSlot = -1
	private var lastAction = ""
	private var lastBoss = ""

	// How long to wait before the next click, rolled once when the last one happened.
	private var lastClick = 0L
	private var nextDelay = 0L

	private var deadline = 0L

	// Set once a run has ended, one way or another: the menu is not worked again until the
	// player has closed it. Without this the menu the server opens after the start button - or
	// the very same menu, while it is still on screen - would be clicked through a second time.
	private var done = false

	private var closeScreen: AbstractContainerScreen<*>? = null
	private var closeAt = 0L

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
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
		done = false
		forgetRun()
		closeScreen = null
		closeAt = 0L
	}

	// Everything a single run carries, dropped when there is no menu being worked. The screen
	// that was just clicked through is left alone: the close it is waiting for is not part of
	// the run, and clearing it here would leave the player standing in the menu.
	private fun forgetRun() {
		wantedBoss = null
		clickWait.reset()
		lastSlot = -1
		lastAction = ""
		lastBoss = ""
		lastClick = 0L
		nextDelay = 0L
		deadline = 0L
	}

	private fun tick() {
		if (!enabled) return
		val mc = Minecraft.getInstance()
		if (mc.player == null) return

		// Runs whatever else is happening: the menu is closed a moment after the quest starts,
		// and it goes before the run state is looked at.
		if (closeAt > 0L && System.currentTimeMillis() >= closeAt) {
			closeAt = 0L
			val screen = mc.gui.screen()
			if (screen != null && screen === closeScreen) screen.onClose()
			closeScreen = null
		}

		val container = mc.gui.screen() as? AbstractContainerScreen<*>
		if (container == null) {
			// Nothing open: the next menu the player opens is a fresh run.
			done = false
			forgetRun()
			return
		}
		// The menu the last run was finished in is left alone until it is closed.
		if (done) return

		val entries = entriesOf(container)
		if (entries.isEmpty() && lastAction.isEmpty()) {
			// Maddox's menu carries items this module recognises. Any other menu on screen -
			// including Maddox's before it has filled in - is not clicked in.
			forgetRun()
			return
		}
		stepMenu(container, entries)
	}

	private fun stepMenu(container: AbstractContainerScreen<*>, entries: List<SlayerMenu.Entry>) {
		val now = System.currentTimeMillis()
		// The clock starts when the menu is first worked rather than when it was opened: the
		// player may have been standing in it for a while before the module was switched on.
		if (deadline == 0L) deadline = now + humanDelay(RUN_TIMEOUT)
		if (now > deadline) {
			giveUp("ran out of time in Maddox's menu")
			return
		}

		val signature = ScreenUtils.signature(container)
		// A click is answered by the menu changing, so while the menu is still the one that was
		// clicked in, nothing else is pressed: the same click is repeated instead, a bounded
		// number of times, in case it never reached the server.
		if (clickWait.unanswered(signature)) {
			if (clickWait.retryDue(signature) && lastSlot >= 0 && now - lastClick >= nextDelay) {
				LOGGER.info("[MeowMod] Auto Slayer: the menu did not change, pressing \"$lastAction\" again (slot $lastSlot)")
				ScreenUtils.click(lastSlot)
				lastClick = now
				nextDelay = humanDelay(delayData.get().toLong())
			}
			return
		}

		val entry = SlayerMenu.choose(entries, wantedBoss)
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

		if (now - lastClick < nextDelay) return

		LOGGER.info(
			"[MeowMod] Auto Slayer: clicking \"${entry.action}\" at slot ${entry.slot}" +
				if (entry.boss.isEmpty()) "" else " (\"${entry.boss}\")"
		)
		ScreenUtils.click(entry.slot)
		lastClick = now
		nextDelay = humanDelay(delayData.get().toLong())
		clickWait.clicked(signature)
		lastSlot = entry.slot
		lastAction = entry.action
		lastBoss = entry.boss

		if (entry.action == SlayerMenu.START) {
			LOGGER.info("[MeowMod] Auto Slayer: the start button was pressed")
			// The run is over: its state goes first, since finishRun() clears the closing screen
			// too, and arming the close before it would leave the menu open indefinitely.
			finishRun()
			closeScreen = container
			closeAt = System.currentTimeMillis() + humanDelay(CLOSE_MS)
		}
	}

	// Ends the run without touching what is on screen. The menu stays up (it may still be the
	// one that was being clicked, or the one the server opened next) and is not worked again
	// until the player closes it.
	private fun finishRun() {
		done = true
		forgetRun()
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

	// Gives up on the run but leaves the module on: a menu that cannot be worked is not
	// something to switch the module off over, and the next menu the player opens gets a fresh
	// attempt.
	private fun giveUp(why: String) {
		LOGGER.warn("[MeowMod] Auto Slayer: $why, giving up" + if (lastAction.isEmpty()) "" else " after \"$lastAction\"${bossSuffix()}")
		ChatUtils.sendMessage("&cAuto Slayer: gave up - $why.", true)
		finishRun()
	}

	private fun bossSuffix(): String = if (lastBoss.isEmpty()) "" else " (\"$lastBoss\")"
}
