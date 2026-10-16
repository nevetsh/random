package com.meow.mod.events

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.multiplayer.ClientLevel

// Minimal client-side event bus. Fires world-load/change listeners by watching
// the active ClientLevel reference on every tick (covers joins, respawns and
// dimension changes without extra mixins).
object MeowEvents {
	private val worldLoadListeners = mutableListOf<() -> Unit>()
	private var lastLevel: ClientLevel? = null
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register { client ->
			val level = client.level
			if (level !== lastLevel) {
				lastLevel = level
				worldLoadListeners.toList().forEach { runCatching { it() } }
			}
		}
	}

	fun onWorldLoad(listener: () -> Unit) {
		worldLoadListeners.add(listener)
	}
}
