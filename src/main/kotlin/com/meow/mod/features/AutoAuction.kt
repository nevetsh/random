package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ClickWait
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
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
//
// The picker is waited for rather than timed: the menu the clock was clicked in is remembered
// (see ClickWait), so while it is still up unchanged the picker has not opened and nothing is
// picked in it - and if that click was lost, the clock is pressed again rather than sitting
// there until a timer runs out.
object AutoAuction : Feature(
	"autoAuction",
	"Sets your auction duration (default \"2 Days\") by picking it in the picker.",
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

	// How long to wait before the next click, rolled once when the last one happened.
	private var nextDelay = 0L
	private val clickWait = ClickWait(CLICK_RETRY_MS, CLICK_RETRIES)
	private var pickedClock = false
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
		nextDelay = 0L
		pickedClock = false
		clickWait.reset()
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
		if (now - lastClick < nextDelay) return

		val items = screen.menu.items
		val wanted = durationData.get().trim()

		val signature = ScreenUtils.signature(screen)

		if (pickedClock) {
			// The picker is what is being waited for, not a timer: while the menu has not changed
			// since the clock was clicked the picker has not opened, so nothing is picked in the
			// menu that is on screen, and the clock is pressed again after a moment in case that
			// click was lost.
			val option = pickerSlot(items, wanted)
			if (option == null) {
				if (clickWait.retryDue(signature)) {
					val again = clockSlot(items)
					if (again != null) {
						LOGGER.info("[MeowMod] AutoAuction: the duration picker has not opened, clicking the clock again")
						click(again)
						return
					}
				}
				if (clickWait.since() > PICKER_TIMEOUT) {
					val why = if (clickWait.unanswered(signature)) "the duration picker never opened"
					else "the picker has no \"$wanted\" option"
					LOGGER.warn("[MeowMod] AutoAuction: giving up - $why")
					ChatUtils.sendMessage("&cCouldn't set the auction duration: $why. Check the name in &f/mm&c.", true)
					reset()
				}
				return
			}
			LOGGER.info("[MeowMod] AutoAuction: picking \"$wanted\" at slot $option")
			click(option)
			reset()
			return
		}

		val clock = clockSlot(items) ?: return

		// Already on the wanted duration, so there is nothing to change.
		if (items[clock].hoverName.string.contains(wanted, ignoreCase = true)) return

		LOGGER.info("[MeowMod] AutoAuction: opening the duration picker (slot $clock, target \"$wanted\")")
		click(clock)
		pickedClock = true
	}

	// The clock in the create-auction menu, whose tooltip is the listing duration.
	private fun clockSlot(items: List<ItemStack>): Int? =
		items.indices.firstOrNull { i ->
			val stack = items[i]
			stack.item == Items.CLOCK && stack.hoverName.string.contains("duration", ignoreCase = true)
		}

	// The duration option in the picker: one white terracotta per length.
	private fun pickerSlot(items: List<ItemStack>, wanted: String): Int? =
		items.indices.firstOrNull { i ->
			val stack = items[i]
			stack.item == Items.DYED_TERRACOTTA.white() &&
				stack.hoverName.string.contains(wanted, ignoreCase = true)
		}

	private fun click(slot: Int) {
		// The menu this click was made in, so that the wait for the picker can be a wait for the
		// menu to actually change rather than a pause.
		clickWait.clicked(ScreenUtils.signature())
		ScreenUtils.click(slot, false)
		lastClick = System.currentTimeMillis()
		nextDelay = humanDelay(delayData.get().toLong())
	}

	// How long the module stays armed after the "Create Auction" click, how long it waits for
	// the duration picker to appear once the clock has been clicked, and how soon a click that
	// changed nothing is pressed again.
	private const val ARM_TIMEOUT = 10000L
	private const val PICKER_TIMEOUT = 5000L
	private const val CLICK_RETRY_MS = 1200L
	private const val CLICK_RETRIES = 2
	private const val CREATE_AUCTION = "create auction"
}
