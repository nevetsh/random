package com.meow.mod.command

import com.meow.mod.features.AutoBz
import com.meow.mod.features.AutoClick
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
						.then(							literal("restore").executes {
								AutoBz.restoreLast()
								1
							}
						)
						// The auto clicker's whitelists, edited while the item is held:
						// "add" and "remove" name the held item, "clear" empties one.
						.then(
							literal("ac")
								.then(
									literal("add")
										.then(literal("left").executes { AutoClick.addHeld(left = true); 1 })
										.then(literal("right").executes { AutoClick.addHeld(left = false); 1 })
								)
								.then(
									literal("remove")
										.then(literal("left").executes { AutoClick.removeHeld(left = true); 1 })
										.then(literal("right").executes { AutoClick.removeHeld(left = false); 1 })
								)
								.then(
									literal("clear")
										.then(literal("left").executes { AutoClick.clearWhitelist("left"); 1 })
										.then(literal("right").executes { AutoClick.clearWhitelist("right"); 1 })
										.then(literal("all").executes { AutoClick.clearWhitelist("all"); 1 })
								)
								.then(literal("list").executes { AutoClick.listWhitelists(); 1 })
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
