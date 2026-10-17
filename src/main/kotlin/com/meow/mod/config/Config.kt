package com.meow.mod.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

object Config {
	const val CATEGORY_NAME = "MeowMod"

	// The subcategory a setting lands in unless it says otherwise. It is the page the config
	// screen opens on, so it is kept at the top of the list rather than sorted with the modules.
	const val GENERAL = "General"

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file
		get() = FabricLoader.getInstance().configDir.resolve("meowmod.json")

	private var root = JsonObject()

	private val configs = mutableListOf<ConfigData<*>>()
	val features = mutableListOf<ConfigData.FeatureSwitch>()

	val subcategories: MutableList<String> = mutableListOf()
	private val bySubcategory = linkedMapOf<String, MutableList<ConfigData<*>>>()

	// The headings a subcategory was split into, in the order they are listed, and which heading
	// each setting was put under.
	private val headings = linkedMapOf<String, MutableList<String>>()
	private val headingOf = HashMap<ConfigData<*>, String>()

	// The list the config screen draws, worked out once per change rather than on every frame.
	private val listed = HashMap<String, List<ConfigData<*>>>()

	fun register(data: ConfigData<*>) {
		// A heading is a marker the config screen draws, not a setting: there is nothing in it
		// to save and nothing to load.
		if (data.type == ConfigType.SECTION) return
		configs.add(data)
	}

	fun registerIn(subcategory: String, data: ConfigData<*>, heading: String? = null) {
		bySubcategory.getOrPut(subcategory) {
			subcategories.add(subcategory)
			mutableListOf()
		}.add(data)
		if (heading != null) headingOf[data] = heading
		listed.remove(subcategory)
	}

	// Splits a subcategory into named groups. The headings are listed in the order they are
	// declared, and the settings that name one follow it, so a page reads the way it was
	// designed however the modules that own those settings happen to be built.
	fun declareHeadings(subcategory: String, vararg titles: String) {
		headings.getOrPut(subcategory) { mutableListOf() }.addAll(titles)
		listed.remove(subcategory)
	}

	fun configsFor(subcategory: String): List<ConfigData<*>> = listed.getOrPut(subcategory) {
		val items = bySubcategory[subcategory].orEmpty()
		val titles = headings[subcategory].orEmpty()
		val result = mutableListOf<ConfigData<*>>()
		titles.forEachIndexed { index, title ->
			result.add(ConfigData.Section("$subcategory#$index", title))
			items.filterTo(result) { headingOf[it] == title }
		}
		// Anything that named no heading, or named one that was never declared, is listed after
		// the headings rather than being dropped off the page.
		items.filterTo(result) { it !in headingOf || headingOf[it] !in titles }
		result
	}

	// The module that owns a page, whatever its switch is listed on: a module whose on/off
	// control is a dropdown hides the plain switch but is still the page's module, so the
	// keybind button has something to bind.
	fun featureFor(subcategory: String): ConfigData.FeatureSwitch? =
		features.firstOrNull { it.subcategory == subcategory }

	fun load() {
		val f = file
		if (Files.exists(f)) {
			try {
				root = JsonParser.parseString(Files.readString(f)).asJsonObject
			} catch (_: Exception) {
				root = JsonObject()
			}
		}
		for (data in configs) {
			applySaved(data)
		}
	}

	private fun applySaved(data: ConfigData<*>) {
		val element = root.get(data.configName) ?: return
		if (element.isJsonNull || !element.isJsonPrimitive) return
		@Suppress("UNCHECKED_CAST")
		when (data.type) {
			ConfigType.SWITCH -> (data as ConfigData<Boolean>).set(element.asBoolean)
			ConfigType.SLIDER -> (data as ConfigData<Double>).set(element.asDouble)
			ConfigType.TEXT -> (data as ConfigData<String>).set(element.asString)
			ConfigType.CHOICE -> (data as ConfigData<String>).set(element.asString)
			// Neither of these holds a value the player sets.
			ConfigType.BUTTON, ConfigType.SECTION -> {}
		}
	}

	fun writeValue(key: String, value: Any?) {
		when (value) {
			is Boolean -> root.addProperty(key, value)
			is Number -> root.addProperty(key, value)
			is String -> root.addProperty(key, value)
		}
		save()
	}

	private fun save() {
		try {
			val f = file
			Files.createDirectories(f.parent)
			Files.writeString(f, gson.toJson(root))
		} catch (_: Exception) {
		}
	}
}
