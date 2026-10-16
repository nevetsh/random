package com.meow.mod.features

object HideFromServers : Feature(
	"hideFromServers",
	"Hides MeowMod's own traffic.",
	subcategory = "General",
	displayName = "Hide MeowMod From Servers",
	enabledByDefault = true,
	heading = "Mod Information"
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
