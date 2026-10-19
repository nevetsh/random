package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ClickWait
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

// Auction house QoL: picks the listing duration for you, clicks the item price of a placed
// BIN listing (Auction Helper - the sign it opens is yours to type into), confirms a BIN
// listing, and can type a custom duration into the sign when the create menu is opened with
// CTRL held.
//
// Every click is spaced by the Click Delay plus or minus the Jitter, and moving from one
// menu to the next waits for that menu to actually be on screen (see ClickWait) rather than
// for a timer to run out.
//
// Nothing happens until the player clicks an auction item (reported by ContainerClickMixin),
// so browsing the auction house is never interfered with. From then on:
//   - "Create Auction" (a bid listing): in the create-auction menu the duration is a clock
//     ("Duration: 6 Hours", "Click to edit!") - click it, then in the picker that opens one
//     white terracotta per length ("2 Days", "Click to pick!") - click the configured one.
//     With CTRL held when the item was clicked, the picker instead gets "Custom Duration",
//     whose sign wants the length in hours: Auto Duration's days are typed in and the sign is
//     closed with ESC, which is what makes vanilla send it (see SignEditScreenMixin).
//   - "Create BIN Auction" (a BIN listing): the confirmation that follows ("Confirm BIN
//     Auction", "Click to confirm!") is clicked for you.
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
	"Picks your auction duration.",
	subcategory = "Automation",
	heading = "Auto Auction",
	displayName = "Auto Auction",
	enabledByDefault = true
) {
	private val durationData = addText(
		"duration",
		"2 Days",
		"Must match the item name.",
		"Duration"
	)
	private val delayData = addSlider(
		"delay",
		200.0,
		50.0,
		1000.0,
		"Delay between clicks, plus 10-20%.",
		"Click Delay (ms)"
	)

	// Auction Helper: once an item is placed in the Create BIN menu the gold ingot that
	// prices it appears, and this clicks it for the player - the sign that opens is theirs
	// to type the price into. It is clicked once per placed item: the guard only rearms
	// when the menu is back to its empty state, so returning from the sign is never a
	// second click.
	private val auctionHelper = addSwitch(
		"helper", true,
		"Clicks the item price button for you.", "Auction Helper"
	)

	private val autoConfirm = addSwitch(
		"autoConfirm", true,
		"Confirms a BIN auction listing.", "Auto Confirm BIN"
	)

	private val autoDuration = addSwitch(
		"autoDuration", true,
		"Ctrl click sets a custom duration.", "Auto Duration"
	)

	private val durationDays = addSlider(
		"durationDays",
		14.0,
		1.0, 14.0,
		"Days, entered into the sign as hours.",
		"Duration (Days)",
		step = 0.5
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

	// Which of the two sequences the arm started: the picker for a named length, or CTRL held
	// while clicking "Create Auction" - the custom length typed into a sign instead.
	private var custom = false

	// The custom length was picked and its sign is what is being waited for.
	private var waitingSign = false
	private var signAt = 0L

	// A BIN listing was submitted and its confirmation is what is being waited for.
	private var confirmArmed = false
	private var confirmAt = 0L

	// The item price has been clicked for the item that is currently placed. Rearmed only
	// by the menu's own empty state, never by a reset - see the note in the setting.
	private var priceArmed = true

	// The hours to type into the custom duration sign, held here while the sign screen closes
	// itself: the mixin reads it in removed(), which is where vanilla sends the sign text.
	var pendingSignText: String? = null

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
	}

	// Called from ContainerClickMixin for every slot the player clicks. Arming on the
	// "Create Auction" item is what starts the duration sequence, and on "Create BIN
	// Auction" what starts the confirmation.
	fun onContainerSlotClicked(itemName: String) {
		if (!configSwitch.get() || !Access.authorized) return
		if (itemName.contains(SUBMIT_BIN, ignoreCase = true)) {
			if (!autoConfirm.get()) return
			confirmArmed = true
			confirmAt = System.currentTimeMillis()
			LOGGER.info("[MeowMod] AutoAuction: armed to confirm the BIN listing clicked as \"$itemName\"")
			return
		}
		if (!itemName.contains(CREATE_AUCTION, ignoreCase = true)) return
		armed = true
		armedAt = System.currentTimeMillis()
		// A CTRL click replaces whatever was armed - including the plain picker above - so
		// the two lengths can never both be set from one click.
		custom = autoDuration.get() && controlDown()
		waitingSign = false
		LOGGER.info(
			"[MeowMod] AutoAuction: armed by clicking \"{}\" (target \"{}\")",
			itemName, if (custom) "Custom Duration, ${durationDays.get()} days" else durationData.get().trim()
		)
	}

	override fun onEnable() {
		enabled = true
		priceArmed = true
		reset()
	}

	override fun onDisable() {
		enabled = false
		priceArmed = true
		reset()
	}

	private fun reset() {
		lastClick = 0L
		nextDelay = 0L
		pickedClock = false
		clickWait.reset()
		armed = false
		armedAt = 0L
		custom = false
		waitingSign = false
		signAt = 0L
		confirmArmed = false
		confirmAt = 0L
	}

	private fun tick() {
		if (!enabled) return
		val screen = Minecraft.getInstance().gui.screen()

		// The custom length is typed into a sign, which is not a container: while that sign is
		// what is being waited for nothing else runs, since the menu it opens from is still
		// the one on screen.
		if (waitingSign) {
			val now = System.currentTimeMillis()
			if (screen is AbstractSignEditScreen) {
				submitSign(screen, hoursText())
				reset()
				return
			}
			if (now - signAt > SIGN_TIMEOUT) {
				LOGGER.warn("[MeowMod] AutoAuction: giving up - the custom duration sign never opened")
				ChatUtils.sendMessage("&cCouldn't set the auction duration: the custom duration sign never opened.", true)
				reset()
			}
			return
		}

		// A BIN listing's confirmation replaces the create menu on screen, so it is looked
		// for before the menu is required to still be the one the sequence started in.
		if (confirmArmed) {
			val container = screen as? AbstractContainerScreen<*> ?: return
			val now = System.currentTimeMillis()
			if (now - confirmAt > ARM_TIMEOUT) {
				reset()
				return
			}
			if (now - lastClick < nextDelay) return
			val confirm = confirmSlot(container.menu.items) ?: return
			LOGGER.info("[MeowMod] AutoAuction: confirming the BIN listing (slot $confirm)")
			click(confirm)
			reset()
			return
		}

		val container = screen as? AbstractContainerScreen<*> ?: run {
			// Outside a container there is nothing to do, and any half-finished picker
			// state is stale.
			reset()
			return
		}

		val items = container.menu.items
		val own = (container.menu.slots.size - PLAYER_SLOTS).coerceAtLeast(0)
		val now = System.currentTimeMillis()

		// Auction Helper: the price button of the item that was just placed, clicked once for
		// this item. The price button is in the menu from the moment it opens, so it is only
		// clicked once the item slot is no longer the "Click an item in your inventory!"
		// placeholder - i.e. the player has actually picked what to sell. That same empty
		// state is what rearms it for the next listing, so coming back from the price sign is
		// never a second click.
		if (auctionHelper.get() && now - lastClick >= nextDelay) {
			if (emptySlot(items, own) != null) {
				// Nothing placed yet: hold the click until an item is chosen.
				priceArmed = true
			} else if (priceArmed) {
				val price = priceSlot(items, own)
				if (price != null) {
					LOGGER.info("[MeowMod] AutoAuction: clicking the item price (slot $price)")
					click(price)
					priceArmed = false
					return
				}
			}
		}

		// Wait for the player to click "Create Auction" before touching anything.
		if (!armed) return
		if (now - armedAt > ARM_TIMEOUT) {
			reset()
			return
		}
		if (now - lastClick < nextDelay) return

		val wanted = if (custom) customLabel() else durationData.get().trim()

		val signature = ScreenUtils.signature(container)

		if (pickedClock) {
			// The picker is what is being waited for, not a timer: while the menu has not changed
			// since the clock was clicked the picker has not opened, so nothing is picked in the
			// menu that is on screen, and the clock is pressed again after a moment in case that
			// click was lost.
			val option = if (custom) customPickerSlot(items) else pickerSlot(items, wanted)
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
					val why = when {
						custom -> "the duration menu has no \"Custom Duration\" option"
						clickWait.unanswered(signature) -> "the duration picker never opened"
						else -> "the picker has no \"$wanted\" option"
					}
					LOGGER.warn("[MeowMod] AutoAuction: giving up - $why")
					val hint = if (custom) "" else " Check the name in &f/mm&c."
					ChatUtils.sendMessage("&cCouldn't set the auction duration: $why.$hint", true)
					reset()
				}
				return
			}
			LOGGER.info("[MeowMod] AutoAuction: picking \"${if (custom) "Custom Duration" else wanted}\" at slot $option")
			click(option)
			if (custom) {
				waitingSign = true
				signAt = System.currentTimeMillis()
				pickedClock = false
			} else {
				reset()
			}
			return
		}

		val clock = clockSlot(items) ?: return

		// Already on the wanted duration, so there is nothing to change.
		if (items[clock].hoverName.string.contains(wanted, ignoreCase = true)) return

		LOGGER.info("[MeowMod] AutoAuction: opening the duration picker (slot $clock, target \"$wanted\")")
		click(clock)
		pickedClock = true
	}

	// Whether CTRL is held right now, which is what turns the "Create Auction" click into a
	// custom duration instead of the picker's named length.
	private fun controlDown(): Boolean {
		val window = Minecraft.getInstance().getWindow()
		return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
			InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
	}

	// Writes the hours into the sign screen's buffer and closes it, which is what makes
	// vanilla send the sign update (see SignEditScreenMixin).
	private fun submitSign(sign: AbstractSignEditScreen, text: String) {
		LOGGER.info("[MeowMod] AutoAuction: submitting \"$text\" hours to the custom duration sign")
		pendingSignText = text
		sign.onClose()
		pendingSignText = null
	}

	// The sign wants hours, and Hypixel's custom duration stops at 14 days - anything longer
	// is asked for as the 336 hours that is.
	private fun hoursText(): String = hours().toString()

	private fun hours(): Int = (durationDays.get() * 24.0).roundToInt().coerceAtMost(MAX_HOURS)

	// How the clock words the length the setting asks for ("14 Days", "36 Hours"), so the
	// "already on it?" check compares the same way the menu writes it.
	private fun customLabel(): String {
		val hours = hours()
		return if (hours % 24 == 0) "${hours / 24} Days" else "$hours Hours"
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

	// The custom length option in the picker, which is what opens the sign.
	private fun customPickerSlot(items: List<ItemStack>): Int? =
		items.indices.firstOrNull { items[it].hoverName.string.contains(CUSTOM_DURATION, ignoreCase = true) }

	// The confirmation button of a BIN listing's confirm menu.
	private fun confirmSlot(items: List<ItemStack>): Int? =
		items.indices.firstOrNull { items[it].hoverName.string.contains(CONFIRM_BIN, ignoreCase = true) }

	// Auction Helper's button: the gold item that prices the placed item ("Item price: 38
	// coins, Click to edit!"), only in the menu's own slots so gold the player is carrying is
	// never mistaken for it. Hypixel has shown it as both a gold ingot and a gold nugget.
	private fun priceSlot(items: List<ItemStack>, own: Int): Int? =
		(0 until own).firstOrNull { i ->
			val stack = items[i]
			(stack.item == Items.GOLD_INGOT || stack.item == Items.GOLD_NUGGET) &&
				stack.hoverName.string.contains("Item price", ignoreCase = true)
		}

	// The empty Create BIN menu's "Click an item in your inventory!" button: a menu showing
	// it has no item placed, which is what rearms Auction Helper for the next listing.
	private fun emptySlot(items: List<ItemStack>, own: Int): Int? =
		(0 until own).firstOrNull {
			items[it].hoverName.string.contains("Click an item in your inventory", ignoreCase = true)
		}

	private fun click(slot: Int) {
		// The menu this click was made in, so that the wait for the picker can be a wait for the
		// menu to actually change rather than a pause.
		clickWait.clicked(ScreenUtils.signature())
		ScreenUtils.click(slot, false)
		lastClick = System.currentTimeMillis()
		nextDelay = humanDelay(delayData.get().toLong())
	}

	// The configured delay randomized around its value (±10-20%): every click the module
	// makes - price, clock, picker, confirm - is spaced by this rather than one fixed beat.

	// How long the module stays armed after the "Create Auction" click, how long it waits for
	// the duration picker to appear once the clock has been clicked, how long the custom
	// duration sign is waited for, and how soon a click that changed nothing is pressed again.
	private const val ARM_TIMEOUT = 10000L
	private const val PLAYER_SLOTS = 36
	private const val PICKER_TIMEOUT = 5000L
	private const val SIGN_TIMEOUT = 5000L
	private const val CLICK_RETRY_MS = 1200L
	private const val CLICK_RETRIES = 2
	private const val CREATE_AUCTION = "create auction"
	private const val SUBMIT_BIN = "create bin auction"
	private const val CONFIRM_BIN = "confirm bin auction"
	private const val CUSTOM_DURATION = "custom duration"

	// 14 days, the longest Hypixel's custom duration takes.
	private const val MAX_HOURS = 14 * 24
}
