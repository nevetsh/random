package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.ScreenUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.optionals.getOrNull

object AutoCombine : Feature(
	"autoCombine",
	"Combines specific enchanted books to their max level (the enchant list is set by the mod)",
	subcategory = "AutoCombine",
	displayName = "Auto Combine"
) {
	private val SETTING_DELAY = addSlider(
		"delay",
		250.0,
		50.0, 1000.0,
		"The delay for each click (too low might ban)",
		"AutoCombine Delay"
	)
	private val SETTING_FORCE_ONLY = addSwitch(
		"forceAcceptOnly",
		false,
		"Forces the auto combiner to only click on anvil/sign instead of doing it entirely",
		"Force Click Only"
	)
	private val books = mapOf(
		"ultimate_legion" to 5,
		"ultimate_wise" to 5,
		"ultimate_wisdom" to 5,
		"ultimate_last_stand" to 5,
		"ultimate_swarm" to 5,
		"ultimate_soul_eater" to 5,
		"overload" to 5,
		"rejuvenate" to 5,
		"dragon_hunter" to 5,
		"smoldering" to 5,
		"green_thumb" to 5
	)
	private val data = CopyOnWriteArrayList<BookSlot>()
	private val sortedData = ConcurrentHashMap<String, MutableList<BookSlot>>()
	private var currentEnchant: String? = null
	private var lastClick = -1L
	private val stash = mutableListOf<String>()
	private var lastInv = MutableList<ItemStack?>(90) { null }

	@Volatile
	private var enabled = false

	data class BookSlot(val slot: Int, val enchantName: String, val level: Int, val uuid: String)

	fun initialize() {
		ClientTickEvents.END_CLIENT_TICK.register {
			if (!enabled) return@register
			val container = container()
			if (container == null) {
				if (data.isNotEmpty()) reset()
				return@register
			}
			if (container.title.string != "Anvil" || !invLoaded(container) || !canClick()) return@register
			onContainerTick(container)
			onEnchantTick(container)
		}
	}

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		reset()
	}

	private fun onContainerTick(container: AbstractContainerScreen<*>) {
		if (!canClick()) return
		val items = container.menu.items

		val midItem = items.getOrNull(22) ?: return
		val isEnchanted = midItem.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
		val isSign = midItem.item == Items.OAK_SIGN

		if (midItem.item == Items.BARRIER) {
			ChatUtils.sendMessage("&c[&4Meow&c] &cSeems like the Auto Combine did not get correct books, try re-opening the anvil menu")
			reset()
			return
		}
		if (SETTING_FORCE_ONLY.get()) {
			if (!canClick()) return
			if (isEnchanted == true || isSign) {
				lastClick = System.currentTimeMillis()
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
			lastClick = System.currentTimeMillis()
			ScreenUtils.click(22, false)
			if (isSign) stash.clear()
			return
		}
		if (data.isEmpty() || stash.size == 2) return
		if (currentEnchant != null && !sortedData.containsKey(currentEnchant)) {
			if (stash.isEmpty()) currentEnchant = null
			else {
				println("MeowMod\$AutoCombine something broke?")
				return
			}
		}

		sortedData.forEach sort@{ (_, v) ->
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
				if (!canClick() || stash.size == 2) return@forEach

				lastClick = System.currentTimeMillis()
				ScreenUtils.click(it.slot, true)
				if (!stash.contains(it.uuid)) stash.add(it.uuid)
				v.remove(it)
			}
		}
	}

	private fun onEnchantTick(container: AbstractContainerScreen<*>) {
		val items = container.menu.items
		val inv = MutableList(90) { items.getOrNull(it) }
		if (inv != lastInv) {
			data.clear()
			sortedData.clear()
		}
		lastInv = inv

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
		sortedData.entries.forEach { (k, v) -> if (v.size < 2) sortedData.remove(k) }
		lastClick = System.currentTimeMillis()
	}

	private fun canClick(): Boolean =
		lastClick == -1L || System.currentTimeMillis() - lastClick > SETTING_DELAY.get()

	private fun reset(full: Boolean = true) {
		data.clear()
		sortedData.clear()
		if (full) stash.clear()
		currentEnchant = null
	}

	private fun container(): AbstractContainerScreen<*>? =
		Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>

	private fun invLoaded(container: AbstractContainerScreen<*>?): Boolean {
		val inv = container ?: container() ?: return false
		val items = inv.menu.items
		return items.size > 45 && !(items.getOrNull(items.size - 45)?.isEmpty ?: true)
	}
}
