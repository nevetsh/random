package com.meow.mod.api

/**
 * What identifies *this* mod to a server: its mod id, which is also the namespace of any channel it
 * registers and of any payload it sends. Nothing else about the client needs hiding.
 *
 * This matters because a server-side Mod API registration is a capability, not a fingerprint:
 * Hypixel's location packet is what Skyblocker reads to know the player is in The End, so blocking
 * every mod channel takes other mods' features down with it. Kept pure and string-based so the
 * filtering can be exercised without a game running.
 */
object PayloadIdentity {
	/** The id in fabric.mod.json, and the namespace of everything this mod puts on the wire. */
	const val NAMESPACE = "meowmod"

	fun isOwn(id: String): Boolean = id.startsWith("$NAMESPACE:")

	/** Drops this mod's own channels, keeping every other channel in the order it arrived. */
	fun keep(channels: List<String>): List<String> = channels.filterNot { isOwn(it) }
}
