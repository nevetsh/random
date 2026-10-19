package com.meow.mod.features

import com.meow.mod.api.ClickWait
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
object AutoExperiments : Feature(
	"autoExperiments",
	"Solves Chronomatron and Ultrasequencer.",
	subcategory = "Automation",
	heading = "Auto Experiments",
	displayName = "Auto Experiments",
	enabledByDefault = true
) {
	private val clickDelay = addSlider(
		"clickDelay", 150.0, 100.0, 1000.0,
		"Delay between clicks.", "Click Delay"
	)
	private val delayVariety = addSlider(
		"delayVariety", 50.0, 0.0, 1000.0,
		"Random variance on the delay.", "Delay Variety"
	)
	private val autoClose = addSwitch(
		"autoClose", true,
		"Close the GUI when done.", "Auto Close"
	)
	private val serumCount = addSlider(
		"serumCount", 0.0, 0.0, 3.0,
		"Serum used, fewer rounds needed.", "Serum Count"
	)
	private val getMaxXp = addSwitch(
		"getMaxXp", false,
		"Solve both to their max rounds.", "Get Max XP"
	)
	// Off by default: the module's own pacing is the ported OdinClient behaviour and it is left
	// exactly as it is. This only adds a second opinion on top of it for a slow connection.
	private val verifyClicks = addSwitch(
		"verifyClicks",
		false,
		"Wait for the game to answer each click.",
		"UI-Based Clicks"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	private var handler: ExperimentHandler? = null
	private var lastClick: Long = 0

	// Whether the claim head of the results menu is still to be clicked: one head per menu,
	// so a menu that stays open after claiming is not clicked again every tick.
	private var claimArmed = true

	// Used by the "UI-Based Clicks" option: the menu each click was made in, so the next
	// click can wait for that menu to change - and in these experiments that change is the game
	// saying it took the click. No retry is asked of it: sending the same block twice could be
	// read as a wrong answer, so a click is never repeated here.
	private val clickWait = ClickWait(0L, 0)
	private var stallWarned = false

	// How long to wait before the next click, rolled once when the last one happened.
	private var nextDelay = 0L

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
	}

	private fun tick() {
		val minecraft = Minecraft.getInstance()
		val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
		if (screen == null) {
			handler = null
			claimArmed = true
			return
		}

		val title = screen.title.string

		// The results menu, whichever of the two experiments it came from: both hand their
		// rewards through a player head to claim, so the head is clicked and the run is
		// over. Nothing beyond the head being a head is read.
		if (title.contains(EXPERIMENT_OVER, ignoreCase = true)) {
			claimRewards(screen)
			return
		}
		claimArmed = true

		var current = handler
		if (current == null) {
			current = when {
				title.startsWith("Chronomatron (") -> ChronomatronHandler({ getMaxXp.get() }, { serumCount.get().toInt() })
				title.startsWith("Ultrasequencer (") -> UltrasequencerHandler({ getMaxXp.get() }, { serumCount.get().toInt() })
				else -> null
			}
			handler = current
			// A new experiment is a new menu, so it starts with nothing to wait on.
			resetClicks()
			if (current == null) return
		} else if (!title.startsWith("Chronomatron (") && !title.startsWith("Ultrasequencer (")) {
			handler = null
			resetClicks()
			return
		}

		current.onSlotUpdate(screen)

		// Optional second opinion on the pacing: wait for the game to answer the last click before
		// sending the next one. A click is answered by the menu changing - the watched block losing
		// its glint, the center item turning from the clock into the lit block - so waiting on that
		// instead of on the delay is what keeps a slow tick from pushing the next step of the
		// sequence into a menu that has not caught up yet. With the option off, nothing here runs
		// and the ported pacing is untouched.
		var answered = true
		if (verifyClicks.get()) {
			val signature = ScreenUtils.signature(screen)
			answered = !clickWait.unanswered(signature)
			if (!answered) {
				val waited = clickWait.since()
				if (!stallWarned && waited > STALL_WARN_MS) {
					stallWarned = true
					LOGGER.warn("[MeowMod] AutoExperiments: nothing has changed since the last click (${waited}ms) in \"$title\"")
				}
				// An answer that never comes must not freeze the experiment for good: after a long
				// enough wait the sequence is allowed to carry on, since a late click is better than
				// no experiment at all.
				if (waited > STALL_RELEASE_MS) {
					LOGGER.warn("[MeowMod] AutoExperiments: carrying on without an answer (${waited}ms)")
					resetClicks()
					answered = true
				}
			}
		}

		val now = System.currentTimeMillis()
		if (answered && now - lastClick >= nextDelay) {
			current.nextClick()?.let { slotId ->
				ScreenUtils.click(slotId, button = "MIDDLE")
				if (verifyClicks.get()) clickWait.clicked(ScreenUtils.signature(screen))
				stallWarned = false
				lastClick = now
				nextDelay = nextWait()
			}
		}

		if (current.shouldClose(autoClose.get())) {
			minecraft.player?.closeContainer()
			handler = null
			resetClicks()
		}
	}

	// The configured click delay plus its own variety, with the human wobble on top.
	private fun nextWait(): Long =
		humanDelay((clickDelay.get() + (0..delayVariety.get().toInt()).random()).toLong())

	// How long a click is waited on before the wait is logged, and how long before the sequence
	// is allowed past it.
	private const val STALL_WARN_MS = 2500L
	private const val STALL_RELEASE_MS = 6000L

	private fun resetClicks() {
		clickWait.reset()
		stallWarned = false
	}

	// The claim head of the results menu: a player head in the menu's own slots, clicked
	// once for this menu and then left alone - claiming closes it, and a menu that stays
	// open must not be clicked again.
	private fun claimRewards(screen: AbstractContainerScreen<*>) {
		handler = null
		if (!claimArmed) return
		val now = System.currentTimeMillis()
		if (now - lastClick < nextDelay) return
		val slots = screen.menu.slots
		val own = (slots.size - PLAYER_SLOTS).coerceAtLeast(0)
		val head = (0 until own).firstOrNull { slots[it].item.item == Items.PLAYER_HEAD } ?: return
		ScreenUtils.click(head)
		LOGGER.info("[MeowMod] AutoExperiments: claiming the rewards at slot {}", head)
		claimArmed = false
		lastClick = now
		nextDelay = nextWait()
	}

	private const val EXPERIMENT_OVER = "Experiment Over"
	private const val PLAYER_SLOTS = 36

	private abstract class ExperimentHandler {
		protected var clicks = 0
		protected var hasData = false

		abstract fun onSlotUpdate(screen: AbstractContainerScreen<*>)
		abstract fun nextClick(): Int?
		abstract fun shouldClose(autoClose: Boolean): Boolean
	}

	private class ChronomatronHandler(
		private val maxXp: () -> Boolean,
		private val serum: () -> Int
	) : ExperimentHandler() {
		private val order = mutableListOf<Int>()
		private var lastAddedSlot = -1
		private var close = false

		override fun onSlotUpdate(screen: AbstractContainerScreen<*>) {
			val slots = screen.menu.slots
			val center = slots.getOrNull(49)?.item ?: return

			if (
				lastAddedSlot != -1 &&
				center.item == Items.GLOWSTONE &&
				slots.getOrNull(lastAddedSlot)?.item?.hasFoil() == false
			) {
				close = order.size > if (maxXp()) 15 else 11 - serum()
				hasData = false
				return
			}

			if (hasData || center.item != Items.CLOCK) return

			val slot = slots.firstOrNull { it.index in 10..43 && it.item.hasFoil() } ?: return
			order.add(slot.index)
			lastAddedSlot = slot.index
			hasData = true
			clicks = 0
		}

		override fun nextClick(): Int? = if (hasData && clicks < order.size) order[clicks++] else null

		override fun shouldClose(autoClose: Boolean): Boolean {
			if (!autoClose || !close) return false
			if (clicks < order.size) return false
			close = false
			return true
		}
	}

	private class UltrasequencerHandler(
		private val maxXp: () -> Boolean,
		private val serum: () -> Int
	) : ExperimentHandler() {
		private val order = ConcurrentHashMap<Int, Int>()

		override fun onSlotUpdate(screen: AbstractContainerScreen<*>) {
			val slots = screen.menu.slots
			val center = slots.getOrNull(49)?.item ?: return

			if (center.item == Items.CLOCK) {
				hasData = false
				return
			}

			if (hasData || center.item != Items.GLOWSTONE) return

			order.clear()
			for (slot in slots) {
				if (slot.index in 9..44 && slot.item.hoverName.string.noControlCodes().matches(Regex("\\d+"))) {
					order[slot.item.count - 1] = slot.index
				}
			}
			hasData = true
			clicks = 0
		}

		override fun nextClick(): Int? = if (!hasData) order[clicks++] else null

		override fun shouldClose(autoClose: Boolean): Boolean =
			autoClose && order.size > if (maxXp()) 20 else 9 - serum()
	}

	override fun onEnable() {
		enabled = true
		lastClick = 0
		nextDelay = 0L
		resetClicks()
	}

	override fun onDisable() {
		enabled = false
		handler = null
		resetClicks()
	}
}
