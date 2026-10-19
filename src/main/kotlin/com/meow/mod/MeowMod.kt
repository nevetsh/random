package com.meow.mod

import com.meow.mod.api.ChatUtils
import com.meow.mod.command.MeowCommand
import com.meow.mod.config.Config
import com.meow.mod.events.MeowEvents
import com.meow.mod.features.AutoAuction
import com.meow.mod.features.AutoBz
import com.meow.mod.features.AutoCombine
import com.meow.mod.features.AutoExperiments
import com.meow.mod.features.AutoFragrun
import com.meow.mod.features.AutoHarp
import com.meow.mod.features.AutoPet
import com.meow.mod.features.AutoReforge
import com.meow.mod.features.AutoSalvage
import com.meow.mod.features.AutoSlayer
import com.meow.mod.features.ConfigSharing
import com.meow.mod.features.CustomName
import com.meow.mod.features.DiscordChat
import com.meow.mod.features.FastPets
import com.meow.mod.features.Feature
import com.meow.mod.features.HideServerId
import com.meow.mod.features.ModUpdater
import com.meow.mod.features.ModuleKeys
import com.meow.mod.features.SkipCommandConfirm
import com.meow.mod.features.TriggerBot
import com.meow.mod.features.TrevorHelper
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
		AutoFragrun, AutoCombine, ConfigSharing,
		AutoHarp, AutoExperiments, TriggerBot, WorldScanner,
		CustomName, DiscordChat, SkipCommandConfirm, HideServerId,
		AutoAuction, AutoBz, AutoSlayer, AutoSalvage, AutoReforge,
		TrevorHelper, AutoPet
	)

	private fun applyAccess() {
		allFeatures.forEach { feature ->
			if (Access.authorized && feature.configSwitch.get()) feature.enable() else feature.disable()
		}
	}

	override fun onInitializeClient() {
		// The General page is split into named groups, listed in this order however the modules
		// happen to register their settings.
		Config.declareHeadings(Config.GENERAL, "Keybinds", "Cosmetics", "Misc")

		// The ten auto modules share one page. Each keeps its own heading, so the page still
		// reads as one group per module instead of one long list. Auction and Bazaar are the
		// day-to-day ones; Slayer is the least used, so it sits at the end.
		Config.declareHeadings(
			"Automation",
			"Auto Auction", "Auto Bazaar", "Auto Fragrun",
			"Auto Combine", "Auto Experiments", "Auto Harp", "Auto Pet", "Auto Reforge", "Auto Salvage",
			"Auto Slayer"
		)

		// The Auto Click page was retired with the auto clicker; the Trigger Bot lives on
		// a page of its own now, with no groups to split it into. The Trevor Helper page
		// groups its settings the way the two upstream mods sectioned them.
		Config.declareHeadings("Trevor Helper", "General", "ESP", "Automation", "Colors", "HUD", "Upstream")

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
		WorldScanner.initialize()
		AutoFragrun.initialize()
		ModuleKeys.initialize()
		DiscordChat.initialize()
		AutoAuction.initialize()
		AutoBz.initialize()
		AutoSlayer.initialize()
		AutoSalvage.initialize()
		AutoReforge.initialize()
		TrevorHelper.initialize()
		FastPets.initialize()

		ModUpdater.start()

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
