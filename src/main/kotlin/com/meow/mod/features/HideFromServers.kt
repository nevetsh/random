package com.meow.mod.features

object HideFromServers : Feature(
	"hideFromServers",
	"Keeps MeowMod's own channels and payloads off the wire. Other mods' traffic is untouched, so Hypixel Mod API features (Skyblocker's location, End and Slayer modules) keep working.",
	subcategory = "General",
	displayName = "Hide MeowMod From Servers",
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
