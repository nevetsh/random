package com.meow.mod.api

import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

object PlayerUtils {
	// Emulates a physical left-click (attack key) press.
	fun leftClick() {
		val options = Minecraft.getInstance().options
		KeyMapping.click(options.keyAttack.defaultKey)
	}

	// Emulates a physical right-click (use key) press.
	fun rightClick() {
		val options = Minecraft.getInstance().options
		KeyMapping.click(options.keyUse.defaultKey)
	}
}
