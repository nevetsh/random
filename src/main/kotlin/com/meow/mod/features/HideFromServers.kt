package com.meow.mod.features

object HideFromServers : Feature(
	"hideFromServers",
	"Hides your mods: vanilla client brand, no mod payloads sent.",
	subcategory = "General",
	displayName = "Hide Mods From Servers",
	enabledByDefault = true
) {
	@Volatile
	private var hiding = true

	fun isHiding(): Boolean = hiding

	override fun onEnable() {
		hiding = true
	}

	override fun onDisable() {
		hiding = false
	}
}
