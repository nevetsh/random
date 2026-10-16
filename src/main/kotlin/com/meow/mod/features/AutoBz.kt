package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.noControlCodes
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

// Auto BZ - re-placing a Bazaar order you cancelled.
//
// Re-pricing a flip is one click to cancel and then seven screens to get back: the
// search sign, the product in the results, the create button, the amount sign, the price
// button and submit. This module remembers what you cancelled and walks that path for
// you, for buy orders and for sell offers alike - the two differ only in which create
// button and which submit item they use.
//
// The whole thing is gated on CTRL: hold it while clicking cancel and the order is
// re-placed, click cancel without it (or with the module off) and the cancel is left
// completely alone. /mm restore re-places the last cancelled order either way.
//
// Capture: clicking an order entry in your orders list ("BUY Sprawl Shard" / "SELL
// Sprawl Shard", lore "Order amount: 55x") stages the product and amount. Clicking
// "Cancel Order" then arms the restore, and the refund line ("... from 55x missing
// items", or the items themselves for a sell offer) is used as the amount, since those
// are what still has to be placed again.
//
// The two sign prompts are played back exactly the way pressing ESC plays them back: the
// text is written into the sign screen's line buffer just before it closes, which is the
// moment vanilla sends the sign update packet (see SignEditScreenMixin).
object AutoBz : Feature(
	"autoBz",
	"Re-places a Bazaar buy order, or re-lists a sell offer, that you cancelled while holding CTRL: searches the product, opens the create button, types the amount and submits it.",
	subcategory = "Auto BZ",
	displayName = "Auto BZ",
	enabledByDefault = true
) {
	private val autoRestore = addSwitch(
		"autoRestore",
		true,
		"Re-place the order as soon as the cancel is confirmed. Turn this off to do it yourself with /mm restore.",
		"Auto Re-place"
	)
	private val startDelay = addSlider(
		"startDelay",
		600.0,
		0.0, 3000.0,
		"How long to wait after the cancel before starting to click.",
		"Start Delay (ms)"
	)
	private val stepDelay = addSlider(
		"stepDelay",
		550.0,
		100.0, 1500.0,
		"Delay between each click and each sign submission.",
		"Step Delay (ms)"
	)
	private val priceOptionBuy = addText(
		"priceOptionBuy",
		"Top Order +0.1",
		"Which price button to pick when re-placing a buy order.",
		"Buy Price Option"
	)
	private val priceOptionSell = addText(
		"priceOptionSell",
		"Top Order -0.1",
		"Which price button to pick when re-listing a sell offer.",
		"Sell Price Option"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// An order as it appears in the Bazaar: which side it is on, the product name, how
	// many items, how much of it already filled, and the unit price the tooltip
	// advertised.
	private data class Order(
		val kind: String,
		val product: String,
		val amount: Int?,
		val filled: Int,
		val unitPrice: String?
	) {
		// What still has to be placed: the order minus what already filled. Cancelling a
		// half-filled order hands back the unfilled part, so placing the whole amount
		// again would buy that part twice.
		val remaining: Int? get() = amount?.let { (it - filled).coerceAtLeast(0) }
	}

	private enum class Step { SEARCH, QUERY_SIGN, RESULT, CREATE, QUANTITY, AMOUNT_SIGN, PRICE, SUBMIT }

	private const val BUY = "BUY"
	private const val SELL = "SELL"

	@Volatile
	private var enabled = false
	private var initialized = false

	// Most recent order entry the player clicked, waiting to be cancelled.
	private var staged: Order? = null
	private var stagedAt = 0L

	// The order that was actually cancelled, kept so /mm restore can be run later.
	private var lastCancelled: Order? = null

	// The restore in progress.
	private var active: Order? = null
	private var step = Step.SEARCH
	private var stepAt = 0L
	private var stepStart = 0L
	private var deadline = 0L
	private var totalPrice: String? = null

	// Read by SignEditScreenMixin when the sign screen closes.
	@Volatile
	var pendingSignText: String? = null
		private set

	private const val STAGE_TTL = 300_000L
	private const val STEP_TIMEOUT = 6_000L
	private const val TOTAL_TIMEOUT = 45_000L

	private const val SEARCH_NAME = "Search"
	private const val CUSTOM_AMOUNT = "Custom Amount"
	private const val BUY_CREATE = "Create Buy Order"
	private const val SELL_CREATE = "Create Sell Offer"
	private const val BUY_SUBMIT = "Buy Order"
	private const val SELL_SUBMIT = "Sell Offer"
	private const val SUBMIT_LORE = "submit"

	// "BUY Sprawl Shard" / "SELL Rejuvenate V"
	private val ORDER_ENTRY = Regex("^(BUY|SELL)\\s+(.+)$", RegexOption.IGNORE_CASE)

	// "Order amount: 55x", "Price per unit: 31,633.7 coins", "Total: 1,992,992 coins"
	private val ORDER_AMOUNT = Regex("Order amount:\\s*([\\d,]+)x", RegexOption.IGNORE_CASE)

	// "Filled: 7/32 (21.9%)" - how much of the order is already bought, against the
	// order's own amount.
	private val FILLED_AMOUNT = Regex("Filled:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
	private val UNIT_PRICE = Regex("Price per unit:\\s*([\\d,.]+)\\s*coins", RegexOption.IGNORE_CASE)
	private val TOTAL_PRICE = Regex("Total:?\\s*([\\d,.]+)\\s*coins", RegexOption.IGNORE_CASE)

	// The "Nx" in a refund line: "from 55x missing items" for a buy order, and the
	// uncollected items themselves for a sell offer.
	private val REFUND_AMOUNT = Regex("([\\d,]+)x", RegexOption.IGNORE_CASE)

	// "Rejuvenate V" -> search for "Rejuvenate" and pick the V out of the results.
	private val TIER = Regex("\\s+(VIII|VII|VI|IV|IX|III|II|I|V|X)$")

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

	// Called from ContainerClickMixin for every slot the player clicks. This is where the
	// cancel is noticed: the order entry stages the details, the cancel button arms the
	// restore.
	fun onContainerSlotClicked(stack: ItemStack) {
		if (!enabled || !Access.authorized) return
		val name = itemName(stack)
		if (name.isEmpty()) return
		val lore = ItemUtils.lore(stack)

		val entry = ORDER_ENTRY.find(name)
		if (entry != null && orderAmount(lore) != null) {
			val filled = filledAmount(lore)?.first ?: 0
			val order = Order(
				entry.groupValues[1].uppercase(),
				entry.groupValues[2].trim(),
				orderAmount(lore),
				filled,
				unitPrice(lore)
			)
			staged = order
			stagedAt = System.currentTimeMillis()
			LOGGER.info(
				"[MeowMod] AutoBZ: staged {} order \"{}\" x{} ({} already filled, re-placing {}, unit {})",
				order.kind, order.product, order.amount, order.filled, order.remaining, order.unitPrice
			)
			return
		}

		if (!isCancelItem(name, lore)) return

		// Only step in when the player asks for it, by holding CTRL on the cancel click.
		// A cancel without the modifier is just a normal cancel and is left alone, and
		// the staged order is kept so the next CTRL cancel still has something to use.
		if (!isControlDown()) {
			LOGGER.info("[MeowMod] AutoBZ: cancel clicked without CTRL held, leaving it alone")
			return
		}

		val order = staged?.takeIf { System.currentTimeMillis() - stagedAt <= STAGE_TTL }
		if (order == null) {
			LOGGER.info("[MeowMod] AutoBZ: a cancel was clicked but no order was staged")
			dumpCurrentScreen("cancel clicked without a staged order")
			ChatUtils.sendMessage("&7Cancelled an order but I don't know which one. Click your order entry first (the one showing &fOrder amount&7).", true)
			return
		}

		// Whatever the cancel screen says was never filled is exactly what has to be
		// placed again; without that, the order's own filled count is the best reading.
		staged = null
		val cancelled = freshCounts(lore, order)
		lastCancelled = cancelled
		LOGGER.info(
			"[MeowMod] AutoBZ: cancel confirmed for {} \"{}\" (order {}, filled {}), re-placing {}",
			cancelled.kind, cancelled.product, cancelled.amount, cancelled.filled, cancelled.remaining
		)

		val amount = cancelled.remaining
		if (amount == null) {
			LOGGER.warn("[MeowMod] AutoBZ: could not read the cancelled amount, not re-placing")
			ChatUtils.sendMessage("&cCouldn't read the cancelled amount, so I won't re-place it.", true)
			return
		}
		if (amount <= 0) {
			LOGGER.info("[MeowMod] AutoBZ: nothing left to re-place, the order was already filled")
			ChatUtils.sendMessage("&7That ${cancelled.product} order was already filled &f${cancelled.filled}&7/&f${cancelled.amount}&7, so there is nothing to re-place.", true)
			return
		}
		if (!autoRestore.get()) {
			ChatUtils.sendMessage("&7Cancelled ${cancelled.product} &7x&f$amount&7. Run &f/mm restore&7 to put it back.", true)
			return
		}
		start(cancelled)
	}

	// /mm restore - re-place the last cancelled order on demand.
	fun restoreLast() {
		if (!enabled || !Access.authorized) return
		if (active != null) {
			ChatUtils.sendMessage("&7Already working on an order.", true)
			return
		}
		val order = lastCancelled
		if (order == null) {
			ChatUtils.sendMessage("&7Nothing has been cancelled yet.", true)
			return
		}
		if (order.remaining == null || order.remaining!! <= 0) {
			ChatUtils.sendMessage("&cI don't know how many items that order still needed.", true)
			return
		}
		start(order)
	}

	private fun start(order: Order) {
		active = order
		step = Step.SEARCH
		totalPrice = null
		val now = System.currentTimeMillis()
		stepStart = now
		stepAt = now + startDelay.get().toLong()
		deadline = now + TOTAL_TIMEOUT
		LOGGER.info("[MeowMod] AutoBZ: {} {}x {}", verb(order), order.remaining, order.product)
		ChatUtils.sendMessage("&7${verb(order).replaceFirstChar { it.uppercase() }} &f${order.remaining}x ${order.product}&7...&8 (${order.filled} of ${order.amount} already filled)", true)
	}

	private fun reset() {
		active = null
		step = Step.SEARCH
		stepAt = 0L
		stepStart = 0L
		deadline = 0L
		totalPrice = null
		pendingSignText = null
	}

	private fun tick() {
		if (!enabled) return
		val order = active ?: return

		val now = System.currentTimeMillis()
		if (now < stepAt) return
		if (now > deadline) {
			fail(order, "it took too long")
			return
		}

		val minecraft = Minecraft.getInstance()
		if (minecraft.player == null) {
			fail(order, "the player is gone")
			return
		}
		val screen = minecraft.gui.screen()

		when (step) {
			Step.SEARCH -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "no Bazaar menu is open")
					return
				}
				val slot = slotWhere(container) { stack, _ -> itemName(stack).equals(SEARCH_NAME, true) }
				if (slot == null) {
					waitOrFail(order, "no \"$SEARCH_NAME\" button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: opening the search sign (slot $slot)")
				ScreenUtils.click(slot, false)
				advance(Step.QUERY_SIGN)
			}

			Step.QUERY_SIGN -> {
				val sign = screen as? AbstractSignEditScreen
				if (sign == null) {
					waitOrFail(order, "the search sign never opened")
					return
				}
				submitSign(sign, searchQuery(order.product))
				advance(Step.RESULT)
			}

			Step.RESULT -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the search results never opened")
					return
				}
				// A query that matches a single product goes straight to its page.
				if (slotWhere(container) { stack, _ -> isCreateButton(order, itemName(stack)) } != null) {
					advance(Step.CREATE)
					return
				}
				val slot = slotWhere(container) { stack, _ -> itemName(stack).equals(order.product, true) }
				if (slot == null) {
					waitOrFail(order, "no \"${order.product}\" among the results in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: opening \"${order.product}\" (slot $slot)")
				ScreenUtils.click(slot, false)
				advance(Step.CREATE)
			}

			Step.CREATE -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the product page never opened")
					return
				}
				val slot = slotWhere(container) { stack, _ -> isCreateButton(order, itemName(stack)) }
				if (slot == null) {
					waitOrFail(order, "no \"${createButton(order)}\" button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: clicking \"${createButton(order)}\" (slot $slot)")
				ScreenUtils.click(slot, false)
				advance(Step.QUANTITY)
			}

			// "How many do you want?" shows preset amounts plus a sign for a custom one;
			// the amount we want is rarely one of the presets, so go through the sign.
			Step.QUANTITY -> {
				val sign = screen as? AbstractSignEditScreen
				if (sign != null) {
					// Items without a preset menu ask for the amount straight away.
					submitSign(sign, order.remaining.toString())
					advance(Step.PRICE)
					return
				}
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the quantity menu never opened")
					return
				}
				val slot = slotWhere(container) { stack, lore ->
					itemName(stack).contains(CUSTOM_AMOUNT, ignoreCase = true) ||
						lore.any { it.contains("Click to specify", ignoreCase = true) }
				}
				if (slot == null) {
					waitOrFail(order, "no \"$CUSTOM_AMOUNT\" button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: clicking \"$CUSTOM_AMOUNT\" (slot $slot)")
				ScreenUtils.click(slot, false)
				advance(Step.AMOUNT_SIGN)
			}

			Step.AMOUNT_SIGN -> {
				val sign = screen as? AbstractSignEditScreen
				if (sign == null) {
					waitOrFail(order, "the amount sign never opened")
					return
				}
				submitSign(sign, order.remaining.toString())
				advance(Step.PRICE)
			}

			Step.PRICE -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the price menu never opened")
					return
				}
				val wanted = priceOption(order)
				val slot = slotWhere(container) { stack, _ -> itemName(stack).contains(wanted, ignoreCase = true) }
					?: slotWhere(container) { _, lore -> lore.any { it.contains("Click to proceed", ignoreCase = true) } }
				if (slot == null) {
					waitOrFail(order, "no \"$wanted\" price option in \"${screenTitle(container)}\"")
					return
				}
				val stack = container.menu.slots.first { it.index == slot }.item
				totalPrice = totalPrice(ItemUtils.lore(stack))
				LOGGER.info(
					"[MeowMod] AutoBZ: picking \"${itemName(stack)}\" (slot $slot, total ${totalPrice ?: "unknown"})"
				)
				ScreenUtils.click(slot, false)
				advance(Step.SUBMIT)
			}

			Step.SUBMIT -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the confirm menu never opened")
					return
				}
				val slot = slotWhere(container) { stack, lore ->
					itemName(stack).contains(submitName(order), ignoreCase = true) &&
						lore.any { it.contains(SUBMIT_LORE, ignoreCase = true) }
				}
				if (slot == null) {
					waitOrFail(order, "no \"${submitName(order)}\" submit button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: submitting at slot $slot")
				ScreenUtils.click(slot, false)
				finish(order)
			}
		}
	}

	// Writes the text into the sign screen's buffer and closes it, which is what makes
	// vanilla send the sign update packet.
	private fun submitSign(sign: AbstractSignEditScreen, text: String) {
		LOGGER.info("[MeowMod] AutoBZ: submitting \"$text\" to the sign")
		pendingSignText = text
		sign.onClose()
		pendingSignText = null
	}

	private fun advance(next: Step) {
		step = next
		stepStart = System.currentTimeMillis()
		stepAt = stepStart + stepDelay.get().toLong()
	}

	// A step that has not seen the screen it wants yet is fine; one that never does gives
	// up with a dump of whatever was on screen.
	private fun waitOrFail(order: Order, why: String) {
		if (System.currentTimeMillis() - stepStart > STEP_TIMEOUT) fail(order, why)
	}

	private fun fail(order: Order, why: String) {
		LOGGER.warn("[MeowMod] AutoBZ: gave up at $step - $why")
		dumpCurrentScreen("gave up at $step")
		ChatUtils.sendMessage("&cCouldn't ${verb(order)} &f${order.product}&c ($why). Check the log.", true)
		reset()
	}

	private fun finish(order: Order) {
		LOGGER.info(
			"[MeowMod] AutoBZ: submitted {}x {} for {} (order {} minus {} filled)",
			order.remaining, order.product, totalPrice ?: "an unknown total", order.amount, order.filled
		)
		ChatUtils.sendMessage("&a${verb(order).replaceFirstChar { it.uppercase() }} &f${order.remaining}x ${order.product}&a for &f${totalPrice ?: "?"}&a coins.", true)
		reset()
	}

	// Logs every slot of the current screen, which is what makes a failed step fixable
	// from the log alone.
	private fun dumpCurrentScreen(why: String) {
		val screen = Minecraft.getInstance().gui.screen()
		when (screen) {
			null -> LOGGER.info("[MeowMod] AutoBZ: $why - no screen is open")
			is AbstractContainerScreen<*> -> {
				LOGGER.info("[MeowMod] AutoBZ: $why - menu \"${screenTitle(screen)}\"")
				for (slot in screen.menu.slots) {
					val stack = slot.item
					if (stack.isEmpty) continue
					val lore = ItemUtils.lore(stack).filter { it.isNotBlank() }
					LOGGER.info("[MeowMod] AutoBZ:   slot ${slot.index} = \"${itemName(stack)}\" $lore")
				}
			}
			else -> LOGGER.info("[MeowMod] AutoBZ: $why - screen ${screen.javaClass.simpleName}")
		}
	}

	private fun slotWhere(
		container: AbstractContainerScreen<*>,
		predicate: (ItemStack, List<String>) -> Boolean
	): Int? {
		for (slot in container.menu.slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			if (predicate(stack, ItemUtils.lore(stack))) return slot.index
		}
		return null
	}

	// CTRL is the "yes, put this one back" modifier; the cancel click itself stays the
	// trigger, so nothing happens unless the player is asking for it.
	private fun isControlDown(): Boolean {
		val window = Minecraft.getInstance().getWindow()
		return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL) ||
			InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL)
	}

	private fun screenTitle(container: AbstractContainerScreen<*>) =
		container.title.string.noControlCodes()

	private fun itemName(stack: ItemStack) = stack.hoverName.string.noControlCodes().trim()

	// Deliberately not just "cancel": the confirm screens have a plain Cancel button, and
	// clicking that is not a cancellation of an order. The refund line is the reliable
	// signal, in both wordings.
	private fun isCancelItem(name: String, lore: List<String>) =
		name.contains("cancel order", ignoreCase = true) ||
			name.contains("cancel offer", ignoreCase = true) ||
			lore.any {
				it.contains("missing item", ignoreCase = true) || it.contains("refund", ignoreCase = true)
			}

	private fun orderAmount(lore: List<String>) = amountFrom(ORDER_AMOUNT, lore)

	// "Filled: 7/32 (21.9%)" -> 7 filled out of 32.
	private fun filledAmount(lore: List<String>): Pair<Int, Int>? {
		for (line in lore) {
			val match = FILLED_AMOUNT.find(line) ?: continue
			val filled = digits(match.groupValues[1])
			val total = digits(match.groupValues[2])
			if (filled != null && total != null) return filled to total
		}
		return null
	}

	// A cancel screen reports what came back rather than what is left to place, so take
	// the freshest reading it offers and fall back to the order's own counts: a
	// "25x missing items" refund line, then a "Filled: 7/32" line the menu still shows
	// for the order, then what the order entry said when it was clicked.
	private fun freshCounts(lore: List<String>, order: Order): Order {
		val amount = order.amount
		val missing = refundAmount(lore)
		if (missing != null && amount != null) {
			return order.copy(filled = (amount - missing).coerceIn(0, amount))
		}
		val fresh = screenFilled(order) ?: filledAmount(lore)
		if (fresh != null && (amount == null || fresh.second == amount)) {
			return order.copy(amount = fresh.second, filled = fresh.first.coerceIn(0, fresh.second))
		}
		return order
	}

	// The order is usually still on screen next to the cancel button, and its tooltip is
	// refreshed as the order fills, so it is the freshest count available.
	private fun screenFilled(order: Order): Pair<Int, Int>? {
		val container = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return null
		for (slot in container.menu.slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			val filled = filledAmount(ItemUtils.lore(stack)) ?: continue
			if (order.amount == null || filled.second == order.amount) return filled
		}
		return null
	}

	// The refund line is the amount that still has to be placed: "from 55x missing items"
	// for a buy order, or the items coming back for a sell offer. The screen can mention
	// the refund on a line without a count of its own ("Refunded coins...") before the
	// line that has one, so every candidate line is read, not just the first.
	private fun refundAmount(lore: List<String>): Int? =
		lore.filter {
			it.contains("missing item", ignoreCase = true) || it.contains("refund", ignoreCase = true)
		}.firstNotNullOfOrNull { amountFrom(REFUND_AMOUNT, listOf(it)) }

	private fun amountFrom(regex: Regex, lore: List<String>): Int? =
		lore.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1)?.let(::digits) }

	private fun digits(text: String) = text.filter { it.isDigit() }.toIntOrNull()

	private fun unitPrice(lore: List<String>) =
		lore.firstNotNullOfOrNull { UNIT_PRICE.find(it)?.groupValues?.get(1) }

	private fun totalPrice(lore: List<String>) =
		lore.firstNotNullOfOrNull { TOTAL_PRICE.find(it)?.groupValues?.get(1) }

	private fun isBuy(order: Order) = order.kind != SELL

	private fun createButton(order: Order) = if (isBuy(order)) BUY_CREATE else SELL_CREATE

	private fun submitName(order: Order) = if (isBuy(order)) BUY_SUBMIT else SELL_SUBMIT

	private fun priceOption(order: Order) =
		if (isBuy(order)) priceOptionBuy.get().trim() else priceOptionSell.get().trim()

	private fun isCreateButton(order: Order, name: String) =
		name.contains(createButton(order), ignoreCase = true)

	private fun verb(order: Order) = if (isBuy(order)) "re-placing" else "re-listing"

	// Hypixel's search matches part of a name, so a tiered product is searched by its base
	// name and then picked out of the results by its full name.
	private fun searchQuery(product: String): String {
		val base = TIER.replace(product, "").trim()
		return base.ifEmpty { product }
	}
}
