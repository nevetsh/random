package com.meow.mod.features

import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData

abstract class Feature(
	val configName: String,
	description: String = "",
	val subcategory: String = "General",
	displayName: String,
	enabledByDefault: Boolean = false
) {
	val configSwitch: ConfigData.FeatureSwitch =
		ConfigData.FeatureSwitch(configName, displayName, description, enabledByDefault).also {
			Config.register(it)
			Config.registerIn(subcategory, it)
			Config.features.add(it)
		}

	private var active = false

	fun addSwitch(
		configName: String,
		initial: Boolean,
		description: String = "",
		displayName: String
	): ConfigData.Switch =
		ConfigData.Switch("${this.configName}\$$configName", initial, displayName, description, configSwitch).also {
			Config.register(it)
			Config.registerIn(subcategory, it)
			configSwitch.subconfigs.add(it)
		}

	fun addSlider(
		configName: String,
		initial: Double,
		min: Double,
		max: Double,
		description: String = "",
		displayName: String
	): ConfigData.Slider =
		ConfigData.Slider("${this.configName}\$$configName", initial, min, max, displayName, description, configSwitch).also {
			Config.register(it)
			Config.registerIn(subcategory, it)
			configSwitch.subconfigs.add(it)
		}

	fun enable() {
		if (!active) {
			active = true
			onEnable()
		}
	}

	fun disable() {
		if (active) {
			active = false
			onDisable()
		}
	}

	open fun onEnable() {}
	open fun onDisable() {}
}
