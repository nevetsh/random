package com.meow.mod.command

import com.meow.mod.ui.ConfigScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.Minecraft

object MeowCommand {
	fun register() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(literal("meow").executes {
				openMenu()
				1
			})
			dispatcher.register(literal("mm").executes {
				openMenu()
				1
			})
		}
	}

	private fun openMenu() {
		Minecraft.getInstance().execute {
			Minecraft.getInstance().setScreenAndShow(ConfigScreen())
		}
	}
}
