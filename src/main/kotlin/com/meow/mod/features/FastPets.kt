package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

// /pet <name> picks a pet out of the pets menu: the menu is opened, the pet whose name
// contains what was typed is clicked - "/pet bla" finds the Blaze pet - and a name that
// matches nothing is reported once the search is over rather than leaving the menu open.
//
// The search reads pet names as they are shown in the menu and nothing else: no item data,
// no account data, and no network beyond the /pets command the menu is opened with.
object FastPets {
	// What was asked for, waiting for the menu, and when the search started.
	private var query: String? = null
	private var stage = STAGE_IDLE
	private var startedAt = 0L
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

		// Stage two: the menu is watched until the pet is found in it, or the search times
		// out - the menu does not always open (the player may be busy), and a name that is
		// not there at all would otherwise wait forever.
		val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
		if (screen != null && screen.title.string.contains("Pets", ignoreCase = true)) {
			val items = screen.menu.items
			val match = items.indices.firstOrNull { i ->
				val name = items[i].hoverName.string.noControlCodes()
				name.contains("[Lvl") && name.contains(want, ignoreCase = true)
			}
			if (match != null) {
				val picked = items[match].hoverName.string.noControlCodes()
				ScreenUtils.click(match)
				ChatUtils.sendMessage("&7Selected &f$picked&7.", true)
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
	}

	// The search's stages, and how long the menu is watched before giving up on it.
	private const val STAGE_IDLE = 0
	private const val STAGE_OPEN = 1
	private const val STAGE_LOOKING = 2
	private const val SEARCH_TIMEOUT = 4000L
}
