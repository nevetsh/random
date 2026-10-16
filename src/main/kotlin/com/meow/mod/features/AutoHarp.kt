package com.meow.mod.features

import com.meow.mod.api.ScreenUtils
import com.meow.mod.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.Items

// Ported from OdinClient (skies-starred/OdinClient, BSD-3-Clause)
object AutoHarp : Feature(
	"autoHarp",
	"Tries to do the Harp for you! (clicks the lit quartz block automatically)",
	subcategory = "Cheats",
	displayName = "Auto Harp"
) {
	@Volatile
	private var enabled = false
	private var hash = 0
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register {
			if (!enabled) return@register
			if (!LocationUtils.isInSkyblock) return@register

			val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return@register
			if (!screen.title.string.startsWith("Harp - ")) return@register

			val slots = screen.menu.slots
			var newHash = 0
			var slot = -1

			for (i in 37..43) {
				val lit = slots.getOrNull(i)?.item?.item == Items.QUARTZ_BLOCK
				newHash = (newHash shl 1) or if (lit) 1 else 0
				if (slot == -1 && lit) slot = i
			}

			if (hash == newHash) return@register
			hash = newHash
			if (slot == -1) return@register

			ScreenUtils.click(slot, button = "MIDDLE")
		}
	}

	override fun onEnable() {
		enabled = true
		hash = 0
	}

	override fun onDisable() {
		enabled = false
	}
}
