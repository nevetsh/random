package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

// /pet <name> picks a pet out of the pets menu: the menu is opened, the pet whose name
// contains what was typed is found - "/pet bla" finds the Blaze pet - and clicked after
// the Auto Pet module's human delay rather than the tick the menu arrived on. A name
// that matches nothing is reported once the search is over rather than leaving the menu
// open.
//
// A pet that is already out offers to despawn itself in its lore, so it is never clicked:
// picking the pet that is out would put it away instead of bringing it out. That is checked
// again just before the click, since the pet can be summoned while the delay runs.
//
// The search reads pet names and that one lore line as they are shown in the menu and
// nothing else: no item data, no account data, and no network beyond the /pets command the
// menu is opened with.
object FastPets {
	// What was asked for, waiting for the menu, and when the search started.
	private var query: String? = null
	private var stage = STAGE_IDLE
	private var startedAt = 0L
	// The pet that was found, waiting out the Auto Pet module's click delay.
	private var pendingName: String? = null
	private var clickAt = 0L
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register { tick() }
	}

	// Just open the menu, as /pet on its own does.
	fun open() {
		ChatUtils.command("pets")
	}

	// Look for a pet whose name contains this, opening the menu to do it.
	fun search(name: String) {
		if (!AutoPet.active) {
			ChatUtils.sendMessage("&cAuto Pet is off - turn it on in the config menu to pick a pet.", true)
			return
		}
		query = name.trim()
		stage = STAGE_OPEN
		startedAt = System.currentTimeMillis()
	}

	private fun tick() {
		if (stage == STAGE_IDLE) return
		val minecraft = Minecraft.getInstance()
		val want = query ?: run {
			stage = STAGE_IDLE
			return
		}

		if (stage == STAGE_OPEN) {
			ChatUtils.command("pets")
			stage = STAGE_LOOKING
			startedAt = System.currentTimeMillis()
			return
		}

		// Stage three: the pet is in sight, but the click waits out the Auto Pet module's
		// human delay first - and the slot is looked up again when the wait is over, since
		// a menu can reorder itself while being looked at.
		if (stage == STAGE_CLICK) {
			val menu = minecraft.gui.screen() as? AbstractContainerScreen<*>
			if (menu == null || !menu.title.string.contains("Pets", ignoreCase = true)) {
				end()
				return
			}
			if (System.currentTimeMillis() < clickAt) return
			val wanted = pendingName ?: run {
				end()
				return
			}
			val items = menu.menu.items
			val slot = items.indices.firstOrNull { i ->
				items[i].hoverName.string.noControlCodes() == wanted && !items[i].isOutPet()
			}
			if (slot == null) {
				if (items.any { it.hoverName.string.noControlCodes() == wanted && it.isOutPet() }) {
					ChatUtils.sendMessage("&f$wanted&7 is already out - not despawning it.", true)
					if (AutoPet.shouldClose()) minecraft.player?.closeContainer()
				} else {
					ChatUtils.sendMessage("&cThe pets menu changed before the click - nothing selected.", true)
				}
				end()
				return
			}
			ScreenUtils.click(slot)
			ChatUtils.sendMessage("&7Selected &f$wanted&7.", true)
			if (AutoPet.shouldClose()) minecraft.player?.closeContainer()
			end()
			return
		}

		// Stage two: the menu is watched until the pet is found in it, or the search times
		// out - the menu does not always open (the player may be busy), and a name that is
		// not there at all would otherwise wait forever.
		val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
		if (screen != null && screen.title.string.contains("Pets", ignoreCase = true)) {
			val items = screen.menu.items
			val named = items.indices.filter { i ->
				val name = items[i].hoverName.string.noControlCodes()
				name.contains("[Lvl") && name.contains(want, ignoreCase = true)
			}
			val match = named.firstOrNull { !items[it].isOutPet() }
			if (match != null) {
				// Found it - the click itself comes later, after the human delay.
				pendingName = items[match].hoverName.string.noControlCodes()
				clickAt = System.currentTimeMillis() + AutoPet.clickDelayMs()
				stage = STAGE_CLICK
				return
			}
			if (named.isNotEmpty()) {
				// Everything with that name is already summoned; clicking it would put it away.
				val out = items[named.first()].hoverName.string.noControlCodes()
				ChatUtils.sendMessage("&f$out&7 is already out - not despawning it.", true)
				if (AutoPet.shouldClose()) minecraft.player?.closeContainer()
				end()
				return
			}
		}

		if (System.currentTimeMillis() - startedAt > SEARCH_TIMEOUT) {
			if (minecraft.gui.screen() != null) minecraft.player?.closeContainer()
			ChatUtils.sendMessage("&cCouldn't find a pet with \"&f$want&c\" in their name.", true)
			end()
		}
	}

	private fun end() {
		query = null
		stage = STAGE_IDLE
		pendingName = null
	}

	// A pet that is already out offers to despawn itself in its lore; clicking it would put
	// the pet away instead of bringing it out.
	private fun ItemStack.isOutPet(): Boolean =
		get(DataComponents.LORE)?.lines()?.any { it.string.contains(DESPAWN_LORE, ignoreCase = true) } == true

	// The search's stages, and how long the menu is watched before giving up on it.
	private const val STAGE_IDLE = 0
	private const val STAGE_OPEN = 1
	private const val STAGE_LOOKING = 2
	private const val STAGE_CLICK = 3
	private const val SEARCH_TIMEOUT = 4000L
	private const val DESPAWN_LORE = "Click to despawn!"
}
