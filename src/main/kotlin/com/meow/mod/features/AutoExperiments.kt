package com.meow.mod.features

import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
object AutoExperiments : Feature(
	"autoExperiments",
	"Automatically solves the Chronomatron and Ultrasequencer experiments.",
	subcategory = "Auto Experiments",
	displayName = "Auto Experiments",
	enabledByDefault = true
) {
	private val clickDelay = addSlider(
		"clickDelay", 150.0, 100.0, 1000.0,
		"Time in ms between automatic test clicks.", "Click Delay"
	)
	private val delayVariety = addSlider(
		"delayVariety", 50.0, 0.0, 1000.0,
		"Random variance added to the click delay.", "Delay Variety"
	)
	private val autoClose = addSwitch(
		"autoClose", true,
		"Automatically close the GUI after completing the experiment.", "Auto Close"
	)
	private val serumCount = addSlider(
		"serumCount", 0.0, 0.0, 3.0,
		"Consumed Metaphysical Serum count (reduces the required rounds).", "Serum Count"
	)
	private val getMaxXp = addSwitch(
		"getMaxXp", false,
		"Solve Chronomatron to 15 and Ultrasequencer to 20 for max XP.", "Get Max XP"
	)

	private var handler: ExperimentHandler? = null
	private var lastClick: Long = 0

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
			return
		}

		val title = screen.title.string
		var current = handler
		if (current == null) {
			current = when {
				title.startsWith("Chronomatron (") -> ChronomatronHandler({ getMaxXp.get() }, { serumCount.get().toInt() })
				title.startsWith("Ultrasequencer (") -> UltrasequencerHandler({ getMaxXp.get() }, { serumCount.get().toInt() })
				else -> null
			}
			handler = current
			if (current == null) return
		} else if (!title.startsWith("Chronomatron (") && !title.startsWith("Ultrasequencer (")) {
			handler = null
			return
		}

		current.onSlotUpdate(screen)

		val now = System.currentTimeMillis()
		if (now - lastClick >= delay()) {
			current.nextClick()?.let { slotId ->
				ScreenUtils.click(slotId, button = "MIDDLE")
				lastClick = now
			}
		}

		if (current.shouldClose(autoClose.get())) {
			minecraft.player?.closeContainer()
			handler = null
		}
	}

	private fun delay(): Long = (clickDelay.get() + (0..delayVariety.get().toInt()).random()).toLong()

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
	}

	override fun onDisable() {
		enabled = false
		handler = null
	}
}
