package com.meow.mod.config

import kotlin.math.roundToInt

open class ConfigData<T> @JvmOverloads constructor(
	val configName: String,
	val type: ConfigType,
	initial: T,
	var displayName: String,
	var description: String = "",
	val parent: ConfigData.FeatureSwitch? = null
) {
	var value: T = initial
		private set

	private val changeHooks = mutableListOf<(T) -> Unit>()

	init {
		Config.register(this)
	}

	open fun get(): T = value

	open fun set(newValue: T) {
		value = newValue
		Config.writeValue(configName, newValue)
		changeHooks.forEach { it(newValue) }
	}

	fun onChange(hook: (T) -> Unit) {
		changeHooks.add(hook)
	}

	class Switch(
		configName: String,
		initial: Boolean,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null
	) : ConfigData<Boolean>(configName, ConfigType.SWITCH, initial, displayName, description, parent)

	class FeatureSwitch(
		configName: String,
		displayName: String,
		description: String = "",
		initial: Boolean = false,
		// The page this module belongs to. A module whose on/off switch is not listed on its own
		// page (it exposes a dropdown instead) is still found by it, so the page keeps its
		// keybind button.
		val subcategory: String = ""
	) : ConfigData<Boolean>(configName, ConfigType.SWITCH, initial, displayName, description) {
		val subconfigs = mutableListOf<ConfigData<*>>()

		// For a module whose on/off control is a dropdown rather than the plain switch: that
		// control has to stay listed even while the module is off, or it could never be turned
		// back on. Everything else the module owns is hidden while it is off (see ConfigScreen).
		var control: ConfigData<*>? = null

		// The key that switches this module on and off from in-game, saved with the rest of the
		// settings under the module's own name. It is deliberately not listed in any config
		// screen: the module keys have a screen of their own, where every module sits next to
		// the key it answers to (see ModuleKeys).
		private val keyData = Text("$configName\$key", "0", "Toggle Key", "")

		var toggleKey: Int
			get() = keyData.get().toIntOrNull() ?: 0
			set(value) {
				keyData.set(value.toString())
			}
	}

	class Slider(
		configName: String,
		initial: Double,
		val min: Double,
		val max: Double,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null,
		// Where a dragged or typed value lands. Most sliders count in tens, but a clicks per
		// second setting wants half steps, so the step is the slider's own.
		val step: Double = 10.0
	) : ConfigData<Double>(configName, ConfigType.SLIDER, initial, displayName, description, parent) {
		fun snap(value: Double): Double = (value / step).roundToInt() * step
	}

	class Text(
		configName: String,
		initial: String,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null,
		val maxLength: Int = 96,
		val secret: Boolean = false
	) : ConfigData<String>(configName, ConfigType.TEXT, initial, displayName, description, parent)

	// One of a fixed set of strings, chosen from a dropdown rather than typed. The stored value
	// is the option's own text, so a setting renames itself by renaming the option.
	class Choice(
		configName: String,
		initial: String,
		val options: List<String>,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null
	) : ConfigData<String>(configName, ConfigType.CHOICE, initial, displayName, description, parent)

	// A heading inside a subcategory, so a long page like General can be split into named
	// groups. It holds no value: it is only ever drawn (see Config.configsFor).
	class Section(
		configName: String,
		val title: String
	) : ConfigData<Boolean>(configName, ConfigType.SECTION, false, title, "")

	class Button(
		configName: String,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null,
		val label: String = "Open",
		val action: () -> Unit
	) : ConfigData<Boolean>(configName, ConfigType.BUTTON, false, displayName, description, parent)
}
