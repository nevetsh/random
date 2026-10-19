package com.meow.mod

import com.meow.mod.api.ChatUtils
import com.meow.mod.command.MeowCommand
import com.meow.mod.config.Config
import com.meow.mod.events.MeowEvents
import com.meow.mod.features.AutoAuction
import com.meow.mod.features.AutoBz
import com.meow.mod.features.AutoClick
import com.meow.mod.features.AutoCombine
import com.meow.mod.features.AutoExperiments
import com.meow.mod.features.AutoFragrun
import com.meow.mod.features.AutoHarp
import com.meow.mod.features.AutoReforge
import com.meow.mod.features.AutoSalvage
import com.meow.mod.features.AutoSlayer
import com.meow.mod.features.CustomName
import com.meow.mod.features.DiscordChat
import com.meow.mod.features.FastPets
import com.meow.mod.features.Feature
import com.meow.mod.features.HideFromServers
import com.meow.mod.features.HideServerId
import com.meow.mod.features.ModUpdater
import com.meow.mod.features.ModuleKeys
import com.meow.mod.features.SkipCommandConfirm
import com.meow.mod.features.TriggerBot
import com.meow.mod.features.WorldScanner
import com.meow.mod.skyblock.LocationUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

object MeowMod : ClientModInitializer {
	const val MOD_ID = "meowmod"

	// Read from the mod metadata rather than a constant kept by hand here: the build writes
	// gradle.properties into fabric.mod.json, so the version shown in the config screen can
	// no longer be left behind by a release (the 1.4.1 build title said v1.4.0).
	val VERSION: String
		get() = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map { it.metadata.version.friendlyString }
			.orElse("dev")

	// Modules are built in this order, and inside a heading of the General page that is also the
	// order the settings are listed in. Which heading a setting belongs to is declared on the
	// setting itself, so a module can add one to General from anywhere.
	private val allFeatures: List<Feature> = listOf(
		AutoFragrun, HideFromServers, AutoCombine, ModUpdater,
		AutoHarp, AutoExperiments, TriggerBot, AutoClick, WorldScanner,
		CustomName, DiscordChat, SkipCommandConfirm, HideServerId,
		AutoAuction, AutoBz, AutoSlayer, AutoSalvage, AutoReforge
	)

	private fun applyAccess() {
		allFeatures.forEach { feature ->
			if (Access.authorized && feature.configSwitch.get()) feature.enable() else feature.disable()
		}
	}

	override fun onInitializeClient() {
		// The General page is split into named groups, listed in this order however the modules
		// happen to register their settings.
		Config.declareHeadings(Config.GENERAL, "Mod Information", "Keybinds", "Cosmetics", "Misc")

		// The Auto Click page holds the held-button clicker and the crystal/secret trigger bot,
		// split into a group each.
		Config.declareHeadings("Auto Click", "Auto Click", "Trigger Bot")

		Access.start(Minecraft.getInstance().user.profileId)

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
		AutoClick.initialize()
		WorldScanner.initialize()
		AutoFragrun.initialize()
		ModuleKeys.initialize()
		DiscordChat.initialize()
		AutoAuction.initialize()
		AutoBz.initialize()
		AutoSlayer.initialize()
		AutoSalvage.initialize()
		AutoReforge.initialize()
		FastPets.initialize()

		Config.load()

		applyAccess()

		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			val before = Access.authorized
			Access.check(mc.user.profileId)
			if (Access.authorized != before) {
				Access.notified = false
				applyAccess()
			}
			if (!Access.authorized) {
				allFeatures.forEach { it.disable() }
				if (Access.settled && !Access.notified && mc.player != null) {
					Access.notified = true
					ChatUtils.sendMessage("&cMeowMod is not licensed for this account.", true)
				}
			}
		}

		MeowCommand.register()
	}
}
