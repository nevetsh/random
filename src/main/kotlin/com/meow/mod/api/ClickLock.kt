package com.meow.mod.api

import net.minecraft.client.Minecraft

// The player's own attack input is shut off here while a combat module is running: the mouse
// press never reaches the game, so no click is queued and nothing is ever swung at. The module's
// own clicks do not come through the mouse at all - they are queued straight onto the attack key -
// so this never touches them. With the gate open the player's click is their own again, and the
// whole thing is client-side input only: no packet is made, changed or held back.
object ClickLock {
	@Volatile
	var blocked: Boolean = false
		private set

	fun setBlocked(value: Boolean) {
		blocked = value
		// A button already held when the gate shuts would otherwise keep its run of attacks. The
		// options are not built yet when this runs during startup, hence the safe call.
		if (value) Minecraft.getInstance().options?.keyAttack?.setDown(false)
	}
}
