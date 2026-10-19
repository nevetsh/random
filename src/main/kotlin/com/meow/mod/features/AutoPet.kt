package com.meow.mod.features

import com.meow.mod.api.humanDelay

// Auto Pet: the module behind /pet's clicking. The pets menu is opened with the command
// and the pet is picked by clicking its slot like a player would - but never on the tick
// the menu arrives. The click waits a human delay (the configured base stretched by a
// random 10-20%, which lands around 250-500ms with the default): look at the menu first,
// then click.
//
// It is a module of its own so the wait and the closing of the menu are settings, and so
// the click can be turned off entirely - FastPets then refuses to click and says why.
object AutoPet : Feature(
	"autoPet",
	"Clicks your pet after a human delay.",
	subcategory = "Automation",
	heading = "Auto Pet",
	displayName = "Auto Pet",
	enabledByDefault = true
) {
	private val clickDelay = addSlider(
		"clickDelay", 350.0, 100.0, 1000.0,
		"Wait before the click, plus 10-20%.",
		"Click Delay (ms)", step = 25.0
	)

	private val autoClose = addSwitch(
		"autoClose", true,
		"Closes the pets menu after clicking.",
		"Close Menu"
	)

	// Read by FastPets instead of the feature's private state: the click flow runs there
	// and only needs to know whether it is allowed to click at all.
	@Volatile
	var active = false
		private set

	override fun onEnable() {
		active = true
	}

	override fun onDisable() {
		active = false
	}

	// How long this particular click waits - rolled fresh every time, so no two clicks
	// are spaced the same way.
	fun clickDelayMs(): Long = humanDelay(clickDelay.get().toLong())

	fun shouldClose(): Boolean = autoClose.get()
}
