package com.meow.mod

import com.meow.mod.api.ChatUtils
import com.meow.mod.command.MeowCommand
import com.meow.mod.config.Config
import com.meow.mod.events.MeowEvents
import com.meow.mod.features.AutoAuction
import com.meow.mod.features.AutoBz
import com.meow.mod.features.AutoCombine
import com.meow.mod.features.AutoExperiments
import com.meow.mod.features.AutoHarp
import com.meow.mod.features.CustomName
import com.meow.mod.features.DiscordChat
import com.meow.mod.features.Feature
import com.meow.mod.features.HideFromServers
import com.meow.mod.features.KeyCommand
import com.meow.mod.features.ModUpdater
import com.meow.mod.features.SkipCommandConfirm
import com.meow.mod.features.TriggerBot
import com.meow.mod.features.WorldScanner
import com.meow.mod.skyblock.LocationUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

object MeowMod : ClientModInitializer {
	const val MOD_ID = "meowmod"
	const val VERSION = "1.3.0"

	private val allFeatures: List<Feature> = listOf(
		HideFromServers, AutoCombine, ModUpdater,
		AutoHarp, AutoExperiments, TriggerBot, WorldScanner,
		KeyCommand, CustomName, DiscordChat, SkipCommandConfirm,
		AutoAuction, AutoBz
	)

	override fun onInitializeClient() {
		Access.check(Minecraft.getInstance().user.profileId)

		allFeatures.forEach { feature ->
			feature.configSwitch.onChange { enabled ->
				if (enabled && Access.authorized) feature.enable() else feature.disable()
			}
		}

		MeowEvents.initialize()
		LocationUtils.initialize()

		AutoCombine.initialize()
		AutoHarp.initialize()
		AutoExperiments.initialize()
		TriggerBot.initialize()
		WorldScanner.initialize()
		KeyCommand.initialize()
		DiscordChat.initialize()
		AutoAuction.initialize()
		AutoBz.initialize()

		Config.load()

		allFeatures.forEach { feature ->
			if (Access.authorized && feature.configSwitch.get()) feature.enable() else feature.disable()
		}

		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			Access.check(mc.user.profileId)
			if (!Access.authorized) {
				allFeatures.forEach { it.disable() }
				if (!Access.notified && mc.player != null) {
					Access.notified = true
					ChatUtils.sendMessage("&cMeowMod is not licensed for this account.", true)
				}
			}
		}

		MeowCommand.register()
	}
}
