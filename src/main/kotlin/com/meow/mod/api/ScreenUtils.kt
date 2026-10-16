package com.meow.mod.api

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot

object ScreenUtils {
	// The player's own inventory is the last 36 slots of these menus, and its contents are not
	// the menu changing.
	private const val PLAYER_SLOTS = 36

	// A fingerprint of the menu on screen: what it is called and what is on it. The player's
	// inventory is left out, since items are handed back into it by a cancel and that is not
	// the menu changing. A screen with no slots (a sign) is described by its kind alone.
	//
	// Modules compare this against the menu a click was made in (see ClickWait) so that they
	// wait for the game to answer the click rather than for a delay to pass.
	fun signature(screen: Screen? = Minecraft.getInstance().gui.screen()): String {
		val container = screen as? AbstractContainerScreen<*> ?: return screen?.javaClass?.name ?: "none"
		val ownSlots = (container.menu.slots.size - PLAYER_SLOTS).coerceAtLeast(0)
		val shape = StringBuilder(container.title.string.noControlCodes())
		for (slot in container.menu.slots) {
			if (slot.index >= ownSlots) continue
			val stack = slot.item
			if (stack.isEmpty) continue
			// The glint is part of it because Hypixel uses it as state - a lit note, a watched
			// block, a button that can be pressed - so a menu where only that changed has changed.
			shape.append('|').append(slot.index).append(if (stack.hasFoil()) '*' else '=')
				shape.append(stack.hoverName.string.noControlCodes().trim())
		}
		return shape.toString()
	}

	@JvmOverloads
	fun click(slot: Int, shift: Boolean = false, button: String = "LEFT") {
		val minecraft = Minecraft.getInstance()
		val screen = minecraft.gui.screen() ?: return
		if (screen !is AbstractContainerScreen<*>) return

		val windowId = screen.menu.containerId
		val clickMode = when {
			button == "MIDDLE" -> ContainerInput.CLONE
			shift -> ContainerInput.QUICK_MOVE
			else -> ContainerInput.PICKUP
		}
		val clickButton = when (button) {
			"LEFT" -> 0
			"RIGHT" -> 1
			"MIDDLE" -> 2
			else -> 0
		}
		val player = minecraft.player ?: return
		minecraft.gameMode?.handleContainerInput(windowId, slot, clickButton, clickMode, player)
	}

	// The menu on screen, if one is open at all.
	fun container(): AbstractContainerScreen<*>? =
		Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>

	// The player's own inventory in a menu, which is the last PLAYER_SLOTS slots of it. A
	// Bazaar cancel hands an item back here, and Auto BZ opens a product's page by clicking
	// that item, so where the player's own slots start is decided in this one place.
	fun inventorySlots(container: AbstractContainerScreen<*>): List<Slot> {
		val own = (container.menu.slots.size - PLAYER_SLOTS).coerceAtLeast(0)
		return container.menu.slots.filter { it.index >= own }
	}

}
