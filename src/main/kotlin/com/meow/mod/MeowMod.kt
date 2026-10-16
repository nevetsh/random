package com.meow.mod

import com.meow.mod.command.MeowCommand
import com.meow.mod.config.Config
import com.meow.mod.events.MeowEvents
import com.meow.mod.features.AutoCombine
import com.meow.mod.features.AutoExperiments
import com.meow.mod.features.AutoHarp
import com.meow.mod.features.Feature
import com.meow.mod.features.HideFromServers
import com.meow.mod.features.ModUpdater
import com.meow.mod.features.TriggerBot
import com.meow.mod.features.WorldScanner
import com.meow.mod.skyblock.LocationUtils
import net.fabricmc.api.ClientModInitializer

object MeowMod : ClientModInitializer {
	const val MOD_ID = "meowmod"
	const val VERSION = "1.1.0"

	private val allFeatures: List<Feature> = listOf(
		HideFromServers, AutoCombine, ModUpdater,
		AutoHarp, AutoExperiments, TriggerBot, WorldScanner
	)

	override fun onInitializeClient() {
		allFeatures.forEach { feature ->
			feature.configSwitch.onChange { enabled ->
				if (enabled) feature.enable() else feature.disable()
			}
		}

		MeowEvents.initialize()
		LocationUtils.initialize()

		AutoCombine.initialize()
		AutoHarp.initialize()
		AutoExperiments.initialize()
		TriggerBot.initialize()
		WorldScanner.initialize()

		Config.load()

		allFeatures.forEach { feature ->
			if (feature.configSwitch.get()) feature.enable() else feature.disable()
		}

		MeowCommand.register()
	}
}
