package com.meow.mod.features

import com.meow.mod.Access

// Vanilla asks for confirmation before sending a command it cannot parse against
// the server's command tree ("Confirm Command Execution"). SkyBlock only declares a
// fraction of its commands, so clicking a command in chat (e.g. "viewstash item")
// always hits that popup. This module sends those commands straight through instead.
// Only *unrecognized* commands are affected: permission and chat-signature warnings
// still behave normally.
object SkipCommandConfirm : Feature(
	"skipCommandConfirm",
	"Skips the \"Confirm Command Execution\" popup for commands the client does not recognise, e.g. SkyBlock commands clicked in chat.",
	subcategory = "Command QoL",
	displayName = "Skip Command Confirmation",
	enabledByDefault = true
) {
	@JvmStatic
	fun isActive(): Boolean = configSwitch.get() && Access.authorized
}
