package com.meow.mod.skyblock

import com.meow.mod.api.noControlCodes
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

// Lightweight replacement for Odin's LocationUtils: parses the area out of the
// tab list ("Area: ..." / "Dungeon: ...") on every client tick.
object LocationUtils {
	@Volatile
	var isInSkyblock: Boolean = false
		private set

	@Volatile
	var currentArea: Island = Island.Unknown
		private set

	@Volatile
	var floor: Floor? = null
		private set

	private val floorRegex = Regex("The Catacombs \\((\\w+)\\)")
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { update() }
	}

	private fun update() {
		val mc = Minecraft.getInstance()
		val connection = mc.connection
		if (connection == null || mc.level == null) {
			isInSkyblock = false
			currentArea = Island.Unknown
			floor = null
			return
		}

		val areaLine = connection.onlinePlayers
			.mapNotNull { it.tabListDisplayName?.string?.noControlCodes() }
			.firstOrNull { it.startsWith("Area: ") || it.startsWith("Dungeon: ") }

		if (areaLine == null) {
			isInSkyblock = false
			currentArea = Island.Unknown
			floor = null
			return
		}

		isInSkyblock = true
		val areaText = areaLine.substringAfter(':').trim()
		currentArea = Island.entries.firstOrNull { areaText.contains(it.displayName, ignoreCase = true) } ?: Island.Unknown
		floor = floorRegex.find(areaLine)?.groupValues?.get(1)?.let { runCatching { Floor.valueOf(it) }.getOrNull() }
	}

	fun isCurrentArea(vararg areas: Island): Boolean =
		areas.any { currentArea == it }
}
