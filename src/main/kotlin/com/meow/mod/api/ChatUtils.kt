package com.meow.mod.api

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object ChatUtils {
	const val prefix = "&8[&dMeow&8]&r"

	fun literal(string: String): MutableComponent = Component.literal(string.replace("&", "\u00a7"))

	fun sendMessage(message: Component) {
		Minecraft.getInstance().execute {
			Minecraft.getInstance().player?.sendSystemMessage(message)
		}
	}

	@JvmOverloads
	fun sendMessage(message: String, withPrefix: Boolean = false) {
		val toAdd = if (withPrefix) "$prefix " else ""
		sendMessage(literal("$toAdd$message"))
	}

	fun command(command: String) {
		Minecraft.getInstance().connection?.sendCommand(command)
	}
}
