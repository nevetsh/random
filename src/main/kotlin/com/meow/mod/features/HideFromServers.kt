package com.meow.mod.features

object HideFromServers : Feature(
	"hideFromServers",
	"Spoofs your client brand to 'vanilla' and blocks every outbound mod payload (any non-minecraft channel), so servers can't tell which mods you have.",
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
