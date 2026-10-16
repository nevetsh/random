package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ScreenUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory

// Auction house QoL: picks the listing duration for you.
//
// Nothing happens until the player clicks the "Create Auction" item (reported by
// ContainerClickMixin), so browsing the auction house is never interfered with. From then
// on, in the create-auction menu:
//   - the duration is a clock ("Duration: 6 Hours", "Click to edit!") - click it
//   - the picker that opens shows one white terracotta per length ("2 Days", "Click to
//     pick!") - click the one matching the configured duration
//
// Detection is by item + tooltip name rather than menu title, so it keeps working if
// Hypixel renames the menus.
object AutoAuction : Feature(
	"autoAuction",
	"Sets your auction listing duration: opens the duration picker (the clock in the create-auction menu) and picks the configured option (default \"2 Days\").",
	subcategory = "Auto Auction",
	displayName = "Auto Auction",
	enabledByDefault = true
) {
	private val durationData = addText(
		"duration",
		"2 Days",
		"Duration option to pick. Must match the item name in the picker.",
		"Duration"
	)

	private val delayData = addSlider(
		"delay",
		200.0,
		50.0, 1000.0,
		"Delay between the two clicks in milliseconds.",
		"Click Delay (ms)"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	@Volatile
	private var enabled = false

	private var initialized = false
	private var lastClick = 0L
	private var pickedClock = false
	private var clockAt = 0L
	private var armed = false
	private var armedAt = 0L

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
	}

	// Called from ContainerClickMixin for every slot the player clicks. Arming on the
	// "Create Auction" item is what starts the sequence.
	fun onContainerSlotClicked(itemName: String) {
		if (!configSwitch.get() || !Access.authorized) return
		if (!itemName.contains(CREATE_AUCTION, ignoreCase = true)) return
		armed = true
		armedAt = System.currentTimeMillis()
		LOGGER.info("[MeowMod] AutoAuction: armed by clicking \"$itemName\" (target \"${durationData.get().trim()}\")")
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
		lastClick = 0L
		pickedClock = false
		clockAt = 0L
		armed = false
		armedAt = 0L
	}

	private fun tick() {
		if (!enabled) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: run {
			// Outside a container there is nothing to do, and any half-finished picker
			// state is stale.
			reset()
			return
		}

		// Wait for the player to click "Create Auction" before touching anything.
		if (!armed) return
		val now = System.currentTimeMillis()
		if (now - armedAt > ARM_TIMEOUT) {
			reset()
			return
		}
		if (now - lastClick < delayData.get().toLong()) return

		val items = screen.menu.items
		val wanted = durationData.get().trim()

		if (pickedClock) {
			// We opened the picker, so look for the duration item now.
			if (System.currentTimeMillis() - clockAt > PICKER_TIMEOUT) {
				reset()
				return
			}
			val slot = items.indices.firstOrNull { i ->
				val stack = items[i]
				stack.item == Items.DYED_TERRACOTTA.white() &&
					stack.hoverName.string.contains(wanted, ignoreCase = true)
			} ?: return
			LOGGER.info("[MeowMod] AutoAuction: picking \"$wanted\" at slot $slot")
			click(slot)
			reset()
			return
		}

		val clock = items.indices.firstOrNull { i ->
			val stack = items[i]
			stack.item == Items.CLOCK && stack.hoverName.string.contains("duration", ignoreCase = true)
		} ?: return

		// Already on the wanted duration, so there is nothing to change.
		if (items[clock].hoverName.string.contains(wanted, ignoreCase = true)) return

		LOGGER.info("[MeowMod] AutoAuction: opening the duration picker (slot $clock, target \"$wanted\")")
		click(clock)
		pickedClock = true
		clockAt = System.currentTimeMillis()
	}

	private fun click(slot: Int) {
		ScreenUtils.click(slot, false)
		lastClick = System.currentTimeMillis()
	}

	// How long the module stays armed after the "Create Auction" click, and how long it
	// waits for the duration picker to appear after clicking the clock.
	private const val ARM_TIMEOUT = 10000L
	private const val PICKER_TIMEOUT = 5000L
	private const val CREATE_AUCTION = "create auction"
}
