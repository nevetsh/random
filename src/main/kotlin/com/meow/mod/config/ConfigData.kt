package com.meow.mod.config

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
		initial: Boolean = false
	) : ConfigData<Boolean>(configName, ConfigType.SWITCH, initial, displayName, description) {
		val subconfigs = mutableListOf<ConfigData<*>>()
	}

	class Slider(
		configName: String,
		initial: Double,
		val min: Double,
		val max: Double,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null
	) : ConfigData<Double>(configName, ConfigType.SLIDER, initial, displayName, description, parent)

	class Text(
		configName: String,
		initial: String,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null,
		val maxLength: Int = 96,
		val secret: Boolean = false
	) : ConfigData<String>(configName, ConfigType.TEXT, initial, displayName, description, parent)

	class Button(
		configName: String,
		displayName: String,
		description: String = "",
		parent: FeatureSwitch? = null,
		val label: String = "Open",
		val action: () -> Unit
	) : ConfigData<Boolean>(configName, ConfigType.BUTTON, false, displayName, description, parent)
}
