package com.meow.mod.features

object HideFromServers : Feature(
	"hideFromServers",
	"Spoofs your client brand to 'vanilla' and blocks Fabric/mod payload channels so servers can't tell which mods you have.",
	subcategory = "Privacy",
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
