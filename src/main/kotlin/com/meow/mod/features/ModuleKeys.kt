package com.meow.mod.features

import com.meow.mod.Access
import com.meow.mod.api.ChatUtils
import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

// Every module can be switched on and off from a key of its own rather than only from the
// config screen. The key is stored on the module itself (see ConfigData.FeatureSwitch) and is
// edited in the Module Keybinds screen, which General opens.
object ModuleKeys {
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// The last state seen for each key, so a key acts on the press itself: a key held down must
	// not flip the module back and forth every tick.
	private val wasDown = HashMap<Int, Boolean>()
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
	}

	// Gives `key` to `feature` and takes it away from whichever module already had it, since one
	// key must never switch two modules at once. Returns the module that lost the key, if any.
	fun bind(feature: ConfigData.FeatureSwitch, key: Int): ConfigData.FeatureSwitch? {
		if (key <= 0) {
			feature.toggleKey = 0
			return null
		}
		val other = Config.features.firstOrNull { it !== feature && it.toggleKey == key }
		other?.toggleKey = 0
		feature.toggleKey = key
		return other
	}

	fun keyName(key: Int): String {
		if (key <= 0) return "None"
		return try {
			InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string
		} catch (_: Exception) {
			"Key $key"
		}
	}

	private fun tick() {
		val mc = Minecraft.getInstance()
		val window = mc.getWindow()

		// The same rule the command bindings follow: nothing fires while a screen is open, so
		// typing a bound key into chat cannot switch a module off behind the player's back.
		val canFire = Access.authorized && mc.gui.screen() == null
		for (feature in Config.features) {
			val key = feature.toggleKey
			if (key <= 0) continue
			val down = InputConstants.isKeyDown(window, key)
			val prev = wasDown[key] ?: false
			wasDown[key] = down
			if (!down || prev || !canFire) continue
			// A key that also runs a command keeps running that command: a binding the player
			// already had is never taken away by binding a module onto the same key. The module
			// key screen says so when the key is picked.
			if (AutoFragrun.commandForKey(key) != null) continue
			val on = !feature.get()
			feature.set(on)
			LOGGER.info("[MeowMod] Module keys: {} switched {}", feature.displayName, if (on) "on" else "off")
			ChatUtils.sendMessage("&f${feature.displayName}&7: " + if (on) "&aon" else "&coff", true)
		}
	}
}
