package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ClickWait
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.api.noControlCodes
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
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
// A cancel screen looks the same for a buy order and a sell offer, and a sell offer does
// not even show an order amount to stage, so nothing is placed from the click: the
// confirmation message is what decides.
//
// Capture: CTRL-clicking "Cancel Order" arms a re-place. What gets placed is then taken
// from the Bazaar's own cancel confirmation, which names the side of the market and, for
// a sell offer, the item and how many came back. A buy order only reports the coins it
// refunded, so its item comes from the order entry clicked just before ("BUY Sprawl
// Shard", lore "Order amount: 55x" / "Filled: 7/32") and its count is that refund
// divided by the entry's unit price.
//
// The two sign prompts are played back exactly the way pressing ESC plays them back: the
// text is written into the sign screen's line buffer just before it closes, which is the
// moment vanilla sends the sign update packet (see SignEditScreenMixin).
//
// The steps are paced by the menus themselves, not by a clock. Every menu a click is made in
// is remembered, and no step touches that menu again: it waits for the menu it actually needs
// to be on screen. A click that changes nothing for a few seconds was most likely lost, so it
// is pressed again - a bounded number of times - and a step that never sees its screen gives
// up and logs what was on screen instead. That is what keeps a slow tick from acting on a
// menu that is already on its way out.
object AutoBz : Feature(
	"autoBz",
	"Re-places a Bazaar order you cancelled while holding CTRL.",
	subcategory = "Auto BZ",
	displayName = "Auto BZ",
	enabledByDefault = true
) {
	private val autoRestore = addSwitch(
		"autoRestore",
		true,
		"Re-place as soon as the cancel is confirmed. Off: use /mm restore.",
		"Auto Re-place"
	)
	private val fastRelist = addSwitch(
		"fastRelist",
		true,
		"Click the cancelled product in the open menu instead of searching.",
		"Re-place From Open Menu"
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
		"Minimum spacing between clicks. Steps also wait for their menu.",
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
		"Best Offer -0.1",
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

	private enum class Step { LIST, SEARCH, QUERY_SIGN, RESULT, CREATE, QUANTITY, AMOUNT_SIGN, PRICE, SUBMIT }

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

	// The price option that was clicked, so the confirmation screen can be checked
	// against it before anything is submitted.
	private var chosenUnitPrice: Double? = null

	// The menu the last click was made in (see ClickWait) and which step made it, so a waiting
	// step can tell "that click has not been answered yet" from "here is the menu I wanted", and
	// can re-run the step that clicked when the click turns out to have been lost.
	private val clickWait = ClickWait(RETRY_MS, MAX_RETRIES)
	private var pendingFrom: Step? = null

	// How many times a confirmation screen has sent us back to the pricing selection.
	private var priceHops = 0

	// Set while the short way is being tried, so a page that turns out not to carry the
	// create button can still fall back to the search instead of giving up.
	private var canFallbackToSearch = false

	// When a CTRL cancel click happened, so the Bazaar's confirmation that follows can be
	// tied to it.
	private var armedAt = 0L

	// Read by SignEditScreenMixin when the sign screen closes.
	@Volatile
	var pendingSignText: String? = null
		private set

	private const val STAGE_TTL = 300_000L

	// How long a step waits for the screen it needs before giving up on the restore. It is a
	// "the menu never arrived" deadline rather than a per-step pause, so a slow tick costs
	// nothing as long as the menu does turn up.
	private const val SCREEN_TIMEOUT = 12_000L

	// A click that has not changed the screen by this much was most likely lost, so it is
	// pressed again, at most MAX_RETRIES times, so a menu that simply never changes still ends
	// up failing instead of being clicked over and over.
	private const val RETRY_MS = 3_000L
	private const val MAX_RETRIES = 3

	// How long a menu that has just opened is given to finish filling in before a step decides
	// it is the wrong menu.
	private const val SETTLE_MS = 2_500L

	private const val TOTAL_TIMEOUT = 90_000L

	// How long after a CTRL cancel click the Bazaar's confirmation still counts as that
	// click's. Hypixel sends the message within the same second.
	private const val CANCEL_ARM_WINDOW = 10_000L

	// A sell offer's confirmation screen opens on Hypixel's own default price and only
	// offers "Go Back" to the pricing selection, so a couple of hops are expected; more
	// than that means the menus are sending us in circles.
	private const val MAX_PRICE_HOPS = 3

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

	// "Unit price: 501.8 coins" on a price option, as opposed to the confirmation
	// screen's "Price per unit: 501.8 coins".
	private val OPTION_UNIT_PRICE = Regex("Unit price:\\s*([\\d,.]+)\\s*coins", RegexOption.IGNORE_CASE)

	// Hypixel names the same 0.1-step option differently on each side of the market, so
	// when the configured name is not in the menu at all these are tried in order.
	private val BUY_PRICE_FALLBACKS = listOf("Top Order +0.1", "Best Offer +0.1")
	private val SELL_PRICE_FALLBACKS = listOf("Best Offer -0.1", "Top Order -0.1")
	private val TOTAL_PRICE = Regex("Total:?\\s*([\\d,.]+)\\s*coins", RegexOption.IGNORE_CASE)

	// The coins a cancelled buy order hands back: "13,306 coins".
	private val REFUNDED_COINS = Regex("^([\\d,.]+)\\s*coins?$", RegexOption.IGNORE_CASE)

	// The Bazaar naming the order it actually cancelled, which is the only place the side
	// of the market appears: "Cancelled! Refunded 1x Rejuvenate V from cancelling Sell
	// Offer!" and "Cancelled! Refunded 13,306 coins from cancelling Buy Order!".
	private val CANCELLED = Regex(
		"Cancelled!\\s*Refunded\\s+(.+?)\\s+from cancelling\\s+(Buy Order|Sell Offer)",
		RegexOption.IGNORE_CASE
	)

	// The "1x Rejuvenate V" a cancelled sell offer hands back.
	private val REFUNDED_ITEM = Regex("^([\\d,]+)x\\s+(.+)$", RegexOption.IGNORE_CASE)

	// The amount a screen states for the order it is about to place. The confirmation says
	// "Selling: 4x Rejuvenate V" on its submit button, and a price selection that skipped the
	// amount menu says "Selling: 1x" on each of its options. Anchored, so a product page's
	// "13x in 1 order" is never read as an amount.
	private val STATED_AMOUNT = Regex("^(?:Selling|Buying):\\s*([\\d,]+)x(?:\\s+(.+))?$", RegexOption.IGNORE_CASE)

	// The line that only a price option carries, which is how a screen is recognised as the
	// price selection (the confirmation screen says "Price per unit" instead).
	private const val PRICE_LINE = "Unit price"

	// "Rejuvenate V" -> search for "Rejuvenate" and pick the V out of the results.
	private val TIER = Regex("\\s+(VIII|VII|VI|IV|IX|III|II|I|V|X)$")

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

		// Nothing is decided here: the Bazaar says what it actually cancelled in the
		// message that follows, and that is what gets re-placed.
		armedAt = System.currentTimeMillis()
		LOGGER.info("[MeowMod] AutoBZ: cancel clicked with CTRL held, waiting for the Bazaar to confirm what it cancelled")
	}

	// The Bazaar's own confirmation is what Auto BZ acts on: it names the side of the
	// market and, for a sell offer, the item and how many came back.
	private fun onChatMessage(text: String) {
		if (!enabled || !Access.authorized) return
		val match = CANCELLED.find(text) ?: return
		val kind = if (match.groupValues[2].startsWith("buy", ignoreCase = true)) BUY else SELL
		val refund = match.groupValues[1].trim()
		val item = REFUNDED_ITEM.find(refund)
		val product = item?.groupValues?.get(2)?.trim()
		val count = item?.groupValues?.get(1)?.let(::digits)

		if (System.currentTimeMillis() - armedAt > CANCEL_ARM_WINDOW) {
			LOGGER.info(
				"[MeowMod] AutoBZ: the Bazaar cancelled a {} ({}), but no CTRL cancel was clicked for it",
				side(kind), refund
			)
			return
		}
		armedAt = 0L

		val order = confirmedOrder(kind, refund, product, count) ?: return
		val amount = order.remaining ?: return
		lastCancelled = order
		if (!autoRestore.get()) {
			ChatUtils.sendMessage("&7Cancelled &f${order.product}&7 x&f$amount&7. Run &f/mm restore&7 to put it back.", true)
			return
		}
		LOGGER.info(
			"[MeowMod] AutoBZ: Bazaar confirmed cancelling a {}, re-placing {}x {}",
			side(kind), amount, order.product
		)
		start(order)
	}

	// A sell offer is described completely by the confirmation, so nothing has to have been
	// staged for one. A buy order is not, so its item comes from the staged entry and its
	// count from the coins refunded, which also has to be something that entry could still
	// have had unfilled.
	private fun confirmedOrder(kind: String, refund: String, product: String?, count: Int?): Order? {
		if (kind == SELL) {
			if (product == null || count == null) {
				LOGGER.warn("[MeowMod] AutoBZ: could not read the item out of the confirmation ({}), nothing re-listed", refund)
				return null
			}
			if (count <= 0) {
				LOGGER.info("[MeowMod] AutoBZ: the Bazaar refunded nothing for that cancel, so there is nothing to re-list")
				ChatUtils.sendMessage("&7The Bazaar refunded nothing for that cancel, so there is nothing to re-place.", true)
				return null
			}
			return Order(SELL, product, count, 0, null)
		}

		val buy = staged?.takeIf { System.currentTimeMillis() - stagedAt <= STAGE_TTL && it.kind == BUY }
		if (buy == null) {
			LOGGER.warn("[MeowMod] AutoBZ: a buy order was cancelled but no buy order entry was staged, so its item is unknown")
			dumpCurrentScreen("cancelled buy order without a staged entry")
			ChatUtils.sendMessage("&cA buy order was cancelled, but I don't know which item it was. Click the order's entry first (the one showing &fOrder amount&c), then cancel it.", true)
			return null
		}

		// The refund is (order amount - filled) x price per unit, which is the freshest
		// reading of what is still unfilled; the staged tooltip can be seconds stale.
		val fromCoins = buyCount(refund, buy.unitPrice)
		val expected = buy.remaining
		if (fromCoins != null && expected != null && fromCoins > expected + 1) {
			LOGGER.warn(
				"[MeowMod] AutoBZ: a refund of {} is {} items at the staged price, more than the {} \"{}\" left - not re-placing",
				refund, fromCoins, side(buy.kind), buy.product
			)
			ChatUtils.sendMessage("&cThat refund (${refund}) is bigger than the &f${buy.product}&c order I had staged, so I won't re-place it.", true)
			return null
		}
		val place = fromCoins ?: expected
		if (place == null) {
			LOGGER.warn("[MeowMod] AutoBZ: could not work out how much of the staged order was still unfilled ({}), not re-placing", refund)
			return null
		}
		if (place <= 0) {
			LOGGER.info("[MeowMod] AutoBZ: nothing was left unfilled on that buy order, so there is nothing to re-place")
			ChatUtils.sendMessage("&7That &f${buy.product}&7 buy order was already filled, so there is nothing to re-place.", true)
			return null
		}
		return buy.copy(amount = place + buy.filled, filled = buy.filled)
	}

	// "13,306 coins" against the staged order's "Price per unit", i.e. how many items the
	// refund covers.
	private fun buyCount(refund: String, unitPrice: String?): Int? {
		val coins = REFUNDED_COINS.find(refund)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
		val unit = unitPrice?.replace(",", "")?.toDoubleOrNull() ?: return null
		if (unit <= 0.0) return null
		return Math.round(coins / unit).toInt()
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
		step = if (fastRelist.get()) Step.LIST else Step.SEARCH
		canFallbackToSearch = fastRelist.get()
		clearPending()
		totalPrice = null
		chosenUnitPrice = null
		priceHops = 0
		val now = System.currentTimeMillis()
		stepStart = now
		stepAt = now + humanDelay(startDelay.get().toLong())
		deadline = now + TOTAL_TIMEOUT
		LOGGER.info("[MeowMod] AutoBZ: {} {}x {}", verb(order), order.remaining, order.product)
		ChatUtils.sendMessage("&7${verb(order).replaceFirstChar { it.uppercase() }} &f${order.remaining}x ${order.product}&7...&8 (${order.filled} of ${order.amount} already filled)", true)
	}

	private fun reset() {
		active = null
		step = Step.LIST
		stepAt = 0L
		stepStart = 0L
		deadline = 0L
		totalPrice = null
		chosenUnitPrice = null
		priceHops = 0
		armedAt = 0L
		pendingSignText = null
		canFallbackToSearch = false
		clearPending()
	}

	// A run starts with no click to wait on: whatever menu is open is the one the first step
	// should be working from.
	private fun clearPending() {
		clickWait.reset()
		pendingFrom = null
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

		// Waiting on the menu rather than on a delay: the menu the last click was made in is
		// never the menu this step is looking for, so while it is still up unchanged that click
		// has not been answered and acting now would act on the wrong menu. On a slow tick the
		// swap can easily take longer than any delay we could have picked.
		val signature = ScreenUtils.signature(screen)
		if (clickWait.unanswered(signature)) {
			// A click that has changed nothing for a while was most likely lost, so the step that
			// made it presses the same button again.
			val from = pendingFrom
			if (from == null || !clickWait.retryDue(signature)) return
			if (from != step) {
				LOGGER.info("[MeowMod] AutoBZ: the menu has not changed since the last click, trying ${from.name.lowercase()} again")
				step = from
				stepAt = 0L
				return
			}
			// The step that clicked is this step, so letting it run again is the retry.
		}

		when (step) {
			// The cancel has just happened, so the Bazaar is usually still showing the menu it
			// came from and the cancelled product is one left-click away from its own page on
			// there - the same page the search would end on, without the sign, the typing and
			// the result list. That is the short way to the create button; the search stays as
			// the fallback for when the product is not on screen.
			Step.LIST -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					advance(Step.SEARCH)
					return
				}
				val slot = productEntrySlot(container, order.product)
				if (slot == null) {
					LOGGER.info("[MeowMod] AutoBZ: no \"${order.product}\" in \"${screenTitle(container)}\", searching for it")
					advance(Step.SEARCH)
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: opening \"${order.product}\" from \"${screenTitle(container)}\" (slot $slot)")
				ScreenUtils.click(slot, false)
				noteClicked(signature)
				advance(Step.CREATE)
			}

			Step.SEARCH -> {
				canFallbackToSearch = false
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
				noteClicked(signature)
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
				noteClicked(signature)
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
					// The short way clicked a product name, not a button, so a page without a create
					// button means that click went nowhere useful - the search still can get there.
					// The new menu is given a moment first, since it may still be filling in.
					if (canFallbackToSearch) {
						if (clickWait.since() < SETTLE_MS) return
						canFallbackToSearch = false
						LOGGER.info("[MeowMod] AutoBZ: no \"${createButton(order)}\" button in \"${screenTitle(container)}\", searching instead")
						advance(Step.SEARCH)
						return
					}
					waitOrFail(order, "no \"${createButton(order)}\" button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: clicking \"${createButton(order)}\" (slot $slot)")
				ScreenUtils.click(slot, false)
				noteClicked(signature)
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
				// The amount menu is skipped whenever the amount is not a choice - a sell offer of
				// the one item the cancel handed back, say - and the price selection opens with the
				// amount already set up on it. That amount is on the option's own lore, and it is
				// checked here before any price is picked, since the alternative is listing a
				// different amount than the order that was cancelled. This is looked for before the
				// confirmation below, so a heading on a price selection cannot pass for one.
				if (hasPriceLine(container)) {
					val wanted = order.remaining
					val priceSlot = priceOptionSlot(container, order, priceOption(order))
					val shown = priceSlot?.let { statedAmount(container, order, it) }
					if (priceSlot != null && wanted != null && shown != wanted) {
							if (clickWait.since() < SETTLE_MS) return
						fail(order, "the amount menu was skipped and the price selection is set up for ${shown?.let { "${it}x" } ?: "an amount I can't read"} instead of ${wanted}x")
						return
					}
					LOGGER.info("[MeowMod] AutoBZ: the amount menu was skipped, picking the price for ${shown ?: wanted}x")
					advance(Step.PRICE)
					return
				}
				// A sell offer can skip the amount menu completely and open the confirmation with
				// an amount of the Bazaar's own choosing ("Selling: 4x Rejuvenate V"), so that
				// amount is checked against what came back before anything is submitted - placing
				// a different amount than the order that was cancelled is the one thing that must
				// not happen quietly.
				val submit = submitSlot(container, order)
				if (submit != null) {
					val wanted = order.remaining
					val shown = statedAmount(container, order, submit)
					if (wanted != null && shown == wanted) {
						LOGGER.info("[MeowMod] AutoBZ: the Bazaar already has the amount at ${shown}x, continuing to the price")
						advance(Step.PRICE)
						return
					}
					// A confirmation that is still filling in reads as the wrong amount, so let it
					// settle before deciding that it really is a different one.
					if (clickWait.since() < SETTLE_MS) return
					fail(order, "the amount menu never opened and the confirmation reads ${shown?.let { "${it}x" } ?: "an amount I can't read"} instead of ${wanted}x")
					return
				}
				// "Click to specify" is how a sign option describes itself, but a custom price
				// is a sign too, so anything that talks about a price is not the amount.
				val slot = slotWhere(container) { stack, lore ->
					val name = itemName(stack)
					name.contains(CUSTOM_AMOUNT, ignoreCase = true) ||
						(lore.any { it.contains("Click to specify", ignoreCase = true) } &&
							!name.contains("price", ignoreCase = true) &&
							lore.none { it.contains("price", ignoreCase = true) })
				}
				if (slot == null) {
					waitOrFail(order, "no \"$CUSTOM_AMOUNT\" button in \"${screenTitle(container)}\"")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: clicking \"$CUSTOM_AMOUNT\" (slot $slot)")
				ScreenUtils.click(slot, false)
				noteClicked(signature)
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

			// The pricing selection lists the options Hypixel offers for this side of the
			// market ("Top Order +0.1" for buy orders, "Best Offer -0.1" for sell offers).
			// A sell offer can open its confirmation screen first on Hypixel's own default
			// price with only "Go Back" to the selection, so that detour is walked here
			// instead of submitting at whatever price the screen started on.
			Step.PRICE -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the price menu never opened")
					return
				}
				val wanted = priceOption(order)
				val slot = priceOptionSlot(container, order, wanted)
				if (slot == null) {
					// Only a confirmation screen offers the way back to the pricing selection. On
					// anything else - the amount menu the sign closed onto, say - a "Go Back" would
					// walk the restore backwards instead of forwards.
					val back = if (submitSlot(container, order) != null) {
						slotWhere(container) { stack, lore ->
							itemName(stack).contains("Go Back", ignoreCase = true) ||
								lore.any { it.contains("pricing selection", ignoreCase = true) }
						}
					} else null
					if (back != null && priceHops < MAX_PRICE_HOPS) {
						priceHops++
						LOGGER.info("[MeowMod] AutoBZ: no price is picked yet, opening the pricing selection (slot $back)")
						ScreenUtils.click(back, false)
						noteClicked(signature)
						advance(Step.PRICE)
						return
					}
					// The menu may still be filling in; only give up once the step has had its
					// full time, and say what it does offer so the option can be corrected.
					if (now - stepStart <= SCREEN_TIMEOUT) return
					val offered = priceOptionNames(container)
					if (offered.isNotEmpty()) {
						ChatUtils.sendMessage("&cNo \"$wanted\" price option here. This menu offers: &f${offered.joinToString(", ")}&c - set your price option in &f/mm&c.", true)
					}
					fail(order, "no \"$wanted\" price option in \"${screenTitle(container)}\"")
					return
				}
				val stack = container.menu.slots.first { it.index == slot }.item
				totalPrice = totalPrice(ItemUtils.lore(stack))
				chosenUnitPrice = priceFrom(OPTION_UNIT_PRICE, ItemUtils.lore(stack))
				LOGGER.info(
					"[MeowMod] AutoBZ: picking \"${itemName(stack)}\" (slot $slot, unit ${chosenUnitPrice ?: "unknown"}, total ${totalPrice ?: "unknown"})"
				)
				ScreenUtils.click(slot, false)
				noteClicked(signature)
				priceHops = 0
				advance(Step.SUBMIT)
			}

			Step.SUBMIT -> {
				val container = screen as? AbstractContainerScreen<*>
				if (container == null) {
					waitOrFail(order, "the confirm menu never opened")
					return
				}
				val slot = submitSlot(container, order)
				if (slot == null) {
					waitOrFail(order, "no \"${submitName(order)}\" submit button in \"${screenTitle(container)}\"")
					return
				}
				val stack = container.menu.slots.first { it.index == slot }.item
				val confirmed = priceFrom(UNIT_PRICE, ItemUtils.lore(stack))
				val picked = chosenUnitPrice
				if (confirmed != null && picked != null && picked > 0.0 &&
					(confirmed < picked * 0.9 || confirmed > picked * 1.1)
				) {
					fail(order, "the confirmation is at $confirmed coins per unit but I picked $picked")
					return
				}
				LOGGER.info("[MeowMod] AutoBZ: submitting at slot $slot (${confirmed ?: "unknown"} coins per unit)")
				ScreenUtils.click(slot, false)
				finish(order)
			}
		}
	}

	// Writes the text into the sign screen's buffer and closes it, which is what makes
	// vanilla send the sign update packet.
	private fun submitSign(sign: AbstractSignEditScreen, text: String) {
		LOGGER.info("[MeowMod] AutoBZ: submitting \"$text\" to the sign")
		noteClicked(ScreenUtils.signature(sign))
		pendingSignText = text
		sign.onClose()
		pendingSignText = null
	}

	// Remembers which menu a click was made in, so the step that follows can tell "the menu has
	// not been replaced yet" from "here is the menu I was waiting for".
	private fun noteClicked(signature: String) {
		clickWait.clicked(signature)
		pendingFrom = step
	}

	private fun advance(next: Step) {
		// A step that is re-entered is a retry rather than progress (the Go Back detour stays in
		// PRICE), so its deadline is left alone - otherwise a step could keep restarting its own
		// timeout and never give up.
		if (next != step) stepStart = System.currentTimeMillis()
		step = next
		stepAt = System.currentTimeMillis() + humanDelay(stepDelay.get().toLong())
	}

	// A step that has not seen the screen it wants yet is fine; one that never does gives
	// up with a dump of whatever was on screen.
	private fun waitOrFail(order: Order, why: String) {
		if (System.currentTimeMillis() - stepStart > SCREEN_TIMEOUT) fail(order, why)
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

	// The confirmation screen's own button: named "Buy Order" / "Sell Offer" and carrying
	// the submit line. "Cancel Sell Offer" also contains "Sell Offer", so the cancel button
	// has to be ruled out by name as well as by its missing submit line.
	private fun submitSlot(container: AbstractContainerScreen<*>, order: Order): Int? =
		slotWhere(container) { stack, lore ->
			val name = itemName(stack)
			name.contains(submitName(order), ignoreCase = true) &&
				!name.contains("cancel", ignoreCase = true) &&
				lore.any { it.contains(SUBMIT_LORE, ignoreCase = true) }
		}

	// How many of the product the item at that slot says it is about to place, read out of
	// "Selling: 4x Rejuvenate V" or "Selling: 1x". A line that names no product is taken as
	// this order's own, since the screen it is on was reached through that product's page.
	private fun statedAmount(container: AbstractContainerScreen<*>, order: Order, slot: Int): Int? {
		val stack = container.menu.slots.firstOrNull { it.index == slot }?.item ?: return null
		for (line in ItemUtils.lore(stack)) {
			val match = STATED_AMOUNT.find(line) ?: continue
			val product = match.groupValues[2].trim()
			if (product.isEmpty() || product.equals(order.product, ignoreCase = true)) {
				return digits(match.groupValues[1])
			}
		}
		return null
	}

	// A price selection, as opposed to the amount menu that comes before it.
	private fun hasPriceLine(container: AbstractContainerScreen<*>) =
		slotWhere(container) { _, lore -> lore.any { it.contains(PRICE_LINE, ignoreCase = true) } } != null

	// A Bazaar order entry is named "BUY Rejuvenate I" / "SELL Rejuvenate V", and the page
	// behind it is the product's own page on either side, so only the name has to match. The
	// prefix is stripped before comparing, since an entry for "Rejuvenate IX" also contains
	// "Rejuvenate I".
	private fun productEntrySlot(container: AbstractContainerScreen<*>, product: String): Int? {
		for (slot in container.menu.slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			val name = itemName(stack)
			val stripped = ORDER_ENTRY.find(name)?.groupValues?.get(2)?.trim() ?: name
			if (stripped.equals(product, ignoreCase = true)) return slot.index
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

	// The option the player configured, then the name Hypixel gives the same 0.1-step
	// option on this side of the market when the configured one is not in the menu.
	private fun priceOptionSlot(container: AbstractContainerScreen<*>, order: Order, wanted: String): Int? {
		val candidates = (listOf(wanted) + if (isBuy(order)) BUY_PRICE_FALLBACKS else SELL_PRICE_FALLBACKS)
			.filter { it.isNotBlank() }
			.distinctBy { normaliseOption(it) }
		for (name in candidates) {
			val slot = slotWhere(container) { stack, _ -> sameOption(itemName(stack), name) }
			if (slot != null) return slot
		}
		return null
	}

	// Case, spacing and the dash itself differ between the menu and the setting, so
	// "Best  Offer — 0.1" and "Best Offer -0.1" are the same option. The sign is kept: a
	// "-0.1" option is not the "+0.1" one.
	private fun sameOption(name: String, wanted: String): Boolean {
		val actual = normaliseOption(name)
		val expected = normaliseOption(wanted)
		return expected.isNotEmpty() && (actual == expected || actual.startsWith(expected))
	}

	private fun normaliseOption(text: String) = text.lowercase()
		.replace('\u2212', '-')
		.replace('\u2013', '-')
		.replace('\u2014', '-')
		.filter { !it.isWhitespace() }

	// What the price menu is offering, for when the configured option is not among it.
	private fun priceOptionNames(container: AbstractContainerScreen<*>): List<String> {
		val items = container.menu.slots.mapNotNull { slot ->
			val stack = slot.item
			if (stack.isEmpty) return@mapNotNull null
			val name = itemName(stack)
			if (name.isEmpty() || name.contains("cancel", ignoreCase = true)) return@mapNotNull null
			name to ItemUtils.lore(stack)
		}
		val options = items.filter { (_, lore) -> lore.any { it.contains("Unit price", ignoreCase = true) } }
		return (options.ifEmpty { items }).map { it.first }.distinct()
	}

	private fun priceFrom(regex: Regex, lore: List<String>): Double? =
		lore.firstNotNullOfOrNull {
			regex.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
		}

	private fun isCreateButton(order: Order, name: String) =
		name.contains(createButton(order), ignoreCase = true)

	private fun verb(order: Order) = if (isBuy(order)) "re-placing" else "re-listing"

	private fun side(kind: String) = if (kind == BUY) "buy order" else "sell offer"

	// Hypixel's search matches part of a name, so a tiered product is searched by its base
	// name and then picked out of the results by its full name.
	private fun searchQuery(product: String): String {
		val base = TIER.replace(product, "").trim()
		return base.ifEmpty { product }
	}
}
