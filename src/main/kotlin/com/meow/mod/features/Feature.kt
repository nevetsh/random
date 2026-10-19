package com.meow.mod.features

import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData

// A module and the settings it owns. Everything a module registers is listed under the
// module's own subcategory unless it says otherwise: the key bindings, for instance, are
// registered into General, where the player looks for their keys.
abstract class Feature(
	val configName: String,
	description: String = "",
	val subcategory: String = Config.GENERAL,
	displayName: String,
	enabledByDefault: Boolean = false,
	private val heading: String? = null,
	// A module that turns itself on and off with a dropdown does not list the plain switch on
	// its page: the dropdown is the control. The switch still exists - it is what the keybind
	// and the enable/disable wiring use - it is just not drawn (see ConfigScreen).
	privateSwitch: Boolean = false
) {
	val configSwitch: ConfigData.FeatureSwitch =
		ConfigData.FeatureSwitch(configName, displayName, description, enabledByDefault, subcategory).also {
			it.listedFor = { visibleToPlayer() }
			Config.register(it)
			if (!privateSwitch) Config.registerIn(subcategory, it, heading)
			Config.features.add(it)
		}

	private var active = false

	// Where a setting is listed, defaulting to the module's own subcategory. Only a setting that
	// belongs somewhere else passes one.
	private fun listedIn(subcategory: String?): String = subcategory ?: this.subcategory

	// The heading a setting follows: the module's own unless the setting names one. It is what
	// lets several modules share a page and still read as a group each.
	private fun headingFor(explicit: String?): String? = explicit ?: heading

	fun addSwitch(
		configName: String,
		initial: Boolean,
		description: String = "",
		displayName: String,
		subcategory: String? = null,
		heading: String? = null
	): ConfigData.Switch =
		ConfigData.Switch("${this.configName}\$$configName", initial, displayName, description, configSwitch).also {
			Config.register(it)
			Config.registerIn(listedIn(subcategory), it, headingFor(heading))
			configSwitch.subconfigs.add(it)
		}

	fun addSlider(
		configName: String,
		initial: Double,
		min: Double,
		max: Double,
		description: String = "",
		displayName: String,
		subcategory: String? = null,
		heading: String? = null,
		step: Double = 10.0
	): ConfigData.Slider =
		ConfigData.Slider("${this.configName}\$$configName", initial, min, max, displayName, description, configSwitch, step).also {
			Config.register(it)
			Config.registerIn(listedIn(subcategory), it, headingFor(heading))
			configSwitch.subconfigs.add(it)
		}

	fun addChoice(
		configName: String,
		initial: String,
		options: List<String>,
		description: String = "",
		displayName: String,
		subcategory: String? = null,
		heading: String? = null
	): ConfigData.Choice =
		ConfigData.Choice("${this.configName}\$$configName", initial, options, displayName, description, configSwitch).also {
			Config.register(it)
			Config.registerIn(listedIn(subcategory), it, headingFor(heading))
			configSwitch.subconfigs.add(it)
		}

	fun addText(
		configName: String,
		initial: String,
		description: String = "",
		displayName: String,
		maxLength: Int = 96,
		secret: Boolean = false,
		hidden: Boolean = false,
		color: Boolean = false,
		subcategory: String? = null,
		heading: String? = null
	): ConfigData.Text =
		ConfigData.Text("${this.configName}\$$configName", initial, displayName, description, configSwitch, maxLength, secret, color).also {
			Config.register(it)
			if (!hidden) {
				Config.registerIn(listedIn(subcategory), it, headingFor(heading))
				configSwitch.subconfigs.add(it)
			}
		}

	fun addButton(
		configName: String,
		description: String = "",
		displayName: String,
		label: String = "Open",
		subcategory: String? = null,
		heading: String? = null,
		action: () -> Unit
	): ConfigData.Button =
		ConfigData.Button("${this.configName}\$$configName", displayName, description, configSwitch, label, action).also {
			Config.register(it)
			Config.registerIn(listedIn(subcategory), it, headingFor(heading))
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

	// A module that is only ever listed for its author answers false for everyone else: the
	// config screen drops its page and the module keys screen leaves it out.
	open fun visibleToPlayer(): Boolean = true
}
