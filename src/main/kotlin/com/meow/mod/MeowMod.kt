package com.meow.mod

import com.meow.mod.command.MeowCommand
import com.meow.mod.config.Config
import com.meow.mod.features.AutoCombine
import com.meow.mod.features.Feature
import com.meow.mod.features.HideFromServers
import com.meow.mod.features.ModUpdater
import net.fabricmc.api.ClientModInitializer

object MeowMod : ClientModInitializer {
	const val MOD_ID = "meowmod"
	const val VERSION = "1.0.0"

	private val allFeatures: List<Feature> = listOf(HideFromServers, AutoCombine, ModUpdater)

	override fun onInitializeClient() {
		allFeatures.forEach { feature ->
			feature.configSwitch.onChange { enabled ->
				if (enabled) feature.enable() else feature.disable()
			}
		}

		AutoCombine.initialize()

		Config.load()

		allFeatures.forEach { feature ->
			if (feature.configSwitch.get()) feature.enable() else feature.disable()
		}

		MeowCommand.register()
	}
}
