package com.meow.mod.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

object Config {
	const val CATEGORY_NAME = "MeowMod"

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file
		get() = FabricLoader.getInstance().configDir.resolve("meowmod.json")

	private var root = JsonObject()

	private val configs = mutableListOf<ConfigData<*>>()
	val features = mutableListOf<ConfigData.FeatureSwitch>()

	val subcategories: MutableList<String> = mutableListOf()
	private val bySubcategory = linkedMapOf<String, MutableList<ConfigData<*>>>()

	fun register(data: ConfigData<*>) {
		configs.add(data)
	}

	fun registerIn(subcategory: String, data: ConfigData<*>) {
		bySubcategory.getOrPut(subcategory) {
			subcategories.add(subcategory)
			mutableListOf()
		}.add(data)
	}

	fun configsFor(subcategory: String): List<ConfigData<*>> =
		bySubcategory[subcategory].orEmpty()

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
