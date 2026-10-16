package com.meow.mod

import java.util.UUID

// Limits MeowMod to a small set of licensed accounts. Everyone else gets every
// feature force-disabled.
object Access {
	private val allowed: Set<UUID> = setOf(
		UUID.fromString("f930d102-3bee-4836-813f-4461a662b002"), // dookidookifwafwa
		UUID.fromString("b109bff6-cd95-4472-9482-0dfd45f4824c")  // Shioriinn
	)

	@Volatile
	var authorized: Boolean = true
		private set

	@Volatile
	var notified: Boolean = false

	fun check(uuid: UUID?): Boolean {
		authorized = uuid != null && uuid in allowed
		return authorized
	}
}
