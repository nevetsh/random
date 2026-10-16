package com.meow.mod.api

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput

object ScreenUtils {
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
}
