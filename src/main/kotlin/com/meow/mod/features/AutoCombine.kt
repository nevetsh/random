package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.humanDelay
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.optionals.getOrNull

object AutoCombine : Feature(
	"autoCombine",
	"Combines enchanted books to max level.",
	subcategory = "Automation",
	heading = "Auto Combine",
	displayName = "Auto Combine",
	enabledByDefault = true
) {
	private val SETTING_DELAY = addSlider(
		"delay",
		250.0,
		50.0, 1000.0,
		"Delay for each click.",
		"AutoCombine Delay"
	)
	private val SETTING_FORCE_ONLY = addSwitch(
		"forceAcceptOnly",
		false,
		"Skips book checks, clicks the result.",
		"Force Click Only"
	)
	private val SETTING_UI_CLICKS = addSwitch(
		"uiClicks",
		true,
		"Only click again after the menu changes.",
		"UI-Based Clicks"
	)
	private val books = mapOf(
		"ultimate_bank" to 5,
		"ultimate_bobbin_time" to 5,
		"ultimate_chimera" to 5,
		"ultimate_combo" to 5,
		"ultimate_crop_fever" to 5,
		"ultimate_fatal_tempo" to 5,
		"ultimate_first_impression" to 5,
		"ultimate_flash" to 5,
		"ultimate_flowstate" to 3,
		"ultimate_habanero_tactics" to 5,
		"ultimate_inferno" to 5,
		"ultimate_jerry" to 5,
		"ultimate_last_stand" to 5,
		"ultimate_legion" to 5,
		"ultimate_missile" to 5,
		"ultimate_no_pain_no_gain" to 5,
		"ultimate_refrigerate" to 5,
		"ultimate_reiterate" to 5,
		"ultimate_rend" to 5,
		"ultimate_soul_eater" to 5,
		"ultimate_sunset" to 5,
		"ultimate_swarm" to 5,
		"ultimate_the_one" to 5,
		"ultimate_wise" to 5,
		"ultimate_wisdom" to 5,
		"overload" to 5,
		"rejuvenate" to 5,
		"dragon_hunter" to 5,
		"smoldering" to 5,
		"green_thumb" to 5,
		"infinite_quiver" to 10
	)
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// The server refuses clicks it thinks are too fast with "Whoa! Slow down there!", and the
	// click it refused is the one that would have taken the combined book. When that arrives the
	// anvil button is pressed again instead of leaving the book sitting in the menu.
	private val RATE_LIMIT = Regex("slow down there", RegexOption.IGNORE_CASE)
	private var refusedAt = 0L

	@Volatile
	private var initialized = false

	private val data = CopyOnWriteArrayList<BookSlot>()
	private val sortedData = ConcurrentHashMap<String, MutableList<BookSlot>>()
	private var currentEnchant: String? = null
	private var lastClick = -1L

	// The menu's fingerprint when the last click went out. With UI-Based Clicks on the module
	// waits for that fingerprint to change before it acts again, so it moves on the moment the
	// menu answers instead of counting out the whole delay (which stays as the upper bound).
	private var lastActSig: String? = null

	// How long to wait after a click before the next one, rolled once per click.
	private var nextDelay = 0L
	private var lastProgress = System.currentTimeMillis()
	private val stash = mutableListOf<String>()
	private var lastInv = MutableList<ItemStack?>(90) { null }
	private val blocked = mutableSetOf<String>()

	@Volatile
	private var enabled = false

	data class BookSlot(val slot: Int, val enchantName: String, val level: Int, val uuid: String)

	fun initialize() {
		if (initialized) return
		initialized = true
		// Hypixel sends the refusal on the chat channel, so that is where it is picked up.
		ClientReceiveMessageEvents.GAME.register { message, _ -> onChatMessage(message.string) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChatMessage(message.string) }
		ClientTickEvents.END_CLIENT_TICK.register {
			if (!enabled) return@register
			val container = container()
			if (container == null) {
				if (data.isNotEmpty()) reset()
				blocked.clear()
				return@register
			}
			if (container.title.string != "Anvil" || !invLoaded(container) || !ready()) return@register
			onContainerTick(container)
			onEnchantTick(container)
		}
	}

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		refusedAt = 0L
		reset()
		blocked.clear()
	}

	private fun onChatMessage(text: String) {
		if (!enabled) return
		if (!RATE_LIMIT.containsMatchIn(text)) return
		refusedAt = System.currentTimeMillis()
		LOGGER.info("[MeowMod] AutoCombine: the server refused a click (\"${text.trim()}\")")
	}

	private fun onContainerTick(container: AbstractContainerScreen<*>) {
		if (stash.isNotEmpty() && System.currentTimeMillis() - lastProgress > stuckTimeout()) {
			recover(container)
			return
		}
		if (!ready()) return
		val items = container.menu.items

		val midItem = items.getOrNull(22) ?: return
		val isEnchanted = midItem.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
		val isSign = midItem.item == Items.OAK_SIGN

		if (midItem.item == Items.BARRIER) {
			LOGGER.info(
				"[MeowMod] AutoCombine BARRIER: cur=$currentEnchant stash=$stash " +
					"| 29=${describe(items.getOrNull(29))} 33=${describe(items.getOrNull(33))} blocked=$blocked"
			)
			ChatUtils.sendMessage("&c[&4Meow&c] &cCan't combine ${currentEnchant ?: "books"} - skipping it")
			currentEnchant?.let { blocked.add(it) }
			recover(container)
			return
		}

		// A click the server refused has not happened at all, so the book it was meant to take is
		// still in the anvil and the button has to be pressed again. The refusal is cleared here,
		// so one warning means one extra press rather than a stream of them, and it is pressed as
		// soon as the menu shows something to collect, since the refusal can land before the
		// result is on screen. The stash is left alone: the pair it describes is still in the anvil.
		if (refusedAt != 0L && System.currentTimeMillis() - refusedAt <= REFUSAL_GRACE && (isEnchanted == true || isSign)) {
			LOGGER.info("[MeowMod] AutoCombine: pressing the anvil button again to take the book the server refused")
			markClick()
			nextDelay += REFUSAL_PAUSE
			refusedAt = 0L
			ScreenUtils.click(22, false)
			return
		}
		if (SETTING_FORCE_ONLY.get()) {
			if (!ready()) return
			if (isEnchanted == true || isSign) {
				markClick()
				ScreenUtils.click(22, false)
			}
			return
		}
		if (isEnchanted == true || isSign) {
			if (stash.size != 2) return
			val uuid1 =
				if (isSign) stash.first()
				else items.getOrNull(29)?.let { ItemUtils.extraAttributes(it)?.get("uuid")?.asString()?.getOrNull() } ?: return
			val uuid2 =
				if (isSign) stash[1]
				else items.getOrNull(33)?.let { ItemUtils.extraAttributes(it)?.get("uuid")?.asString()?.getOrNull() } ?: return
			if (!stash.contains(uuid1) || !stash.contains(uuid2)) {
				reset()
				return
			}
			markClick()
			ScreenUtils.click(22, false)
			if (isSign) stash.clear()
			return
		}
		if (data.isEmpty() || stash.size == 2) return
		if (currentEnchant != null && !sortedData.containsKey(currentEnchant)) {
			if (stash.isEmpty()) {
				currentEnchant = null
			} else {
				// A book of the current enchant sits in the anvil but its partner is gone,
				// so this group can never be completed. Put the stashed book back and
				// rescan instead of spinning on this branch forever.
				LOGGER.info("[MeowMod] AutoCombine: no partner left for $currentEnchant, returning the stashed book")
				recover(container)
				return
			}
		}

		sortedData.forEach sort@{ (key, v) ->
			if (key in blocked) return@sort
			if (currentEnchant == null) currentEnchant = v.firstOrNull()?.let { "${it.enchantName}:${it.level}" }

			v.reversed().forEach {
				val containerItems = container.menu.items
				if (
					containerItems.getOrNull(29)?.item == Items.ENCHANTED_BOOK &&
					containerItems.getOrNull(33)?.item == Items.ENCHANTED_BOOK
				) return@forEach
				val enchantName = it.enchantName
				val level = it.level
				if (currentEnchant != "$enchantName:$level") return@forEach
				if (!ready() || stash.size == 2) return@forEach

				markClick()
				ScreenUtils.click(it.slot, true)
				if (!stash.contains(it.uuid)) stash.add(it.uuid)
				v.remove(it)
			}
		}
	}

	private fun onEnchantTick(container: AbstractContainerScreen<*>) {
		val items = container.menu.items
		val inv = MutableList(90) { items.getOrNull(it) }
		if (inv == lastInv) return
		lastInv = inv
		lastProgress = System.currentTimeMillis()
		data.clear()
		sortedData.clear()

		items.forEachIndexed { idx, itemStack ->
			if (idx < 54) return@forEachIndexed
			if (itemStack == null || itemStack.item != Items.ENCHANTED_BOOK) return@forEachIndexed
			val extraAttributes = ItemUtils.extraAttributes(itemStack) ?: return@forEachIndexed
			val enchs = extraAttributes.getCompound("enchantments")
			if (!enchs.isPresent || enchs.isEmpty) return@forEachIndexed
			val enchants = mutableMapOf<String, Int>()

			enchs.get().forEach { k, v ->
				val level = v.asInt().getOrNull() ?: return@forEach
				enchants[k] = level
			}
			if (enchants.size > 1) return@forEachIndexed
			val (k, v) = enchants.entries.first()
			val max = books[k] ?: return@forEachIndexed
			if (v == max) return@forEachIndexed
			val uuid = ItemUtils.extraAttributes(itemStack)?.get("uuid")?.asString()?.getOrNull() ?: return@forEachIndexed

			data.add(BookSlot(idx, k, v, uuid))
		}

		data.forEach { sortedData.getOrPut("${it.enchantName}:${it.level}") { mutableListOf() }.add(it) }
		// A group needs two distinct books, but a book that is already in the anvil is no longer
		// in the inventory - so the group we are currently working on looks like a single book
		// and used to get dropped here, leaving that book stuck in the anvil with no partner to
		// place next to it. Keep the in-flight group alive until its second book has been placed.
		val inFlight = currentEnchant.takeIf { stash.isNotEmpty() }
		sortedData.entries.forEach { (k, v) ->
			if (v.distinctBy { it.uuid }.size < 2 && k != inFlight) sortedData.remove(k)
		}
	}

	// Whether it is time to click again. The delay is always the least time between clicks;
	// beyond that, with UI-Based Clicks on, the module waits for the menu to answer the last
	// click, and only gives up on an answer after ACT_TIMEOUT so a refused click cannot stall it.
	private fun ready(): Boolean {
		val now = System.currentTimeMillis()
		if (lastClick == -1L) return true
		if (now - lastClick <= nextDelay) return false
		if (!SETTING_UI_CLICKS.get()) return true
		if (ScreenUtils.signature() != lastActSig) return true
		return now - lastClick > maxOf(nextDelay, ACT_TIMEOUT)
	}

	// Every click rolls the wait until the next one, so the pacing varies the way a hand
	// rather than a metronome would.
	private fun markClick() {
		lastClick = System.currentTimeMillis()
		lastActSig = ScreenUtils.signature()
		nextDelay = humanDelay(SETTING_DELAY.get().toLong())
	}

	// How long after a refusal the anvil button is still worth pressing again, and how much
	// extra wait a refused click adds before the next one.
	private const val REFUSAL_GRACE = 4000L
	private const val REFUSAL_PAUSE = 1200L

	// How long to wait for the menu to answer a click before moving on anyway, so a click the
	// server ignored cannot stall the loop.
	private const val ACT_TIMEOUT = 1000L

	private fun reset(full: Boolean = true) {
		data.clear()
		sortedData.clear()
		if (full) stash.clear()
		currentEnchant = null
	}

	private fun stuckTimeout(): Long =
		maxOf(3500L, (SETTING_DELAY.get() * 8).toLong())

	private fun recover(container: AbstractContainerScreen<*>) {
		val items = container.menu.items
		if (items.getOrNull(29)?.item == Items.ENCHANTED_BOOK) ScreenUtils.click(29, true)
		if (items.getOrNull(33)?.item == Items.ENCHANTED_BOOK) ScreenUtils.click(33, true)
		reset()
		lastInv = MutableList<ItemStack?>(90) { null }
		lastProgress = System.currentTimeMillis()
		markClick()
	}

	private fun container(): AbstractContainerScreen<*>? =
		Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>

	private fun invLoaded(container: AbstractContainerScreen<*>?): Boolean {
		val inv = container ?: container() ?: return false
		val items = inv.menu.items
		return items.size > 45 && !(items.getOrNull(items.size - 45)?.isEmpty ?: true)
	}

	private fun describe(stack: ItemStack?): String =
		if (stack == null || stack.isEmpty) "empty" else "\"${stack.hoverName.string}\""
}
