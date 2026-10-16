package com.meow.mod.command

import com.meow.mod.features.AutoBz
import com.meow.mod.ui.ConfigScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.Minecraft

object MeowCommand {
	fun register() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			for (root in listOf("meow", "mm")) {
				dispatcher.register(
					literal(root)
						.executes {
							openMenu()
							1
						}
						// Re-places the last Bazaar order that was cancelled, which is handy
						// when Auto Re-place is turned off.
						.then(
							literal("restore").executes {
								AutoBz.restoreLast()
								1
							}
						)
				)
			}
		}
	}

	private fun openMenu() {
		Minecraft.getInstance().execute {
			Minecraft.getInstance().setScreenAndShow(ConfigScreen())
		}
	}
}
