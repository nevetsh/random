package com.meow.mod.features

import org.slf4j.LoggerFactory

// Ported from V5's "Cancel Interact" module, pinned at
// https://github.com/V5-Client/V5/blob/55ae528a57c1bc06bb1f3ff6c474ed830990e7e9/modules/other/CancelInteract.js
// The rules are re-implemented here in Kotlin rather than copied, but the behaviour is
// V5's and belongs to them; that project is GPL-3.0, so check the license before this file
// is ever distributed alongside their code.
//
// While an ender pearl is in hand, clicking a block that is not whitelisted is remembered,
// and the block interaction the client sends right after that click is dropped: pearling
// stops opening chests, pressing buttons or using whatever is in front of you by accident.
// Levers, chests and buttons stay usable on purpose.
//
// One deliberate difference from the module above: it only looks at the main hand, while
// this checks the hand that is actually interacting, so a pearl in the off hand counts.
object NoInteract : Feature(
	"noInteract",
	"Blocks aren't used while holding a pearl.",
	subcategory = "General",
	displayName = "No Interact",
	enabledByDefault = true,
	heading = "Misc"
) {
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// How long after the click the block interaction is still treated as that click's,
	// in milliseconds. Same window the V5 module uses.
	private const val CANCEL_WINDOW = 50L
	private const val ENDER_PEARL = "minecraft:ender_pearl"

	@Volatile
	private var enabled = false

	// When the last pearl block-click happened, or 0 when there is nothing to drop.
	@Volatile
	private var clickedAt = 0L

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		clickedAt = 0L
	}

	// Called from the block interaction mixin with the item in the interacting hand and
	// the clicked block, both as registry ids ("minecraft:ender_pearl").
	fun onBlockInteraction(heldItem: String, block: String) {
		if (!enabled) return
		if (heldItem != ENDER_PEARL) return
		if (!NoInteractRules.shouldCancel(block)) return
		clickedAt = System.currentTimeMillis()
		LOGGER.info("[MeowMod] No Interact: pearl clicked $block, dropping the block interaction")
	}

	// Called from the connection mixin for every outgoing block interaction. The window is
	// short enough that only the click that set it can match.
	fun shouldCancelPacket(): Boolean {
		if (!enabled) return false
		val clicked = clickedAt
		if (clicked == 0L || System.currentTimeMillis() - clicked > CANCEL_WINDOW) return false
		clickedAt = 0L
		return true
	}
}

// The rules on their own, with no Minecraft types in sight, so they can be checked and
// compared against the module they came from.
object NoInteractRules {
	private val AIR = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

	// Blocks that stay usable while a pearl is held.
	private val ALLOWED = setOf(
		"minecraft:lever",
		"minecraft:chest",
		"minecraft:trapped_chest",
		"minecraft:stone_button",
		"minecraft:oak_button",
		"minecraft:spruce_button",
		"minecraft:birch_button",
		"minecraft:jungle_button",
		"minecraft:acacia_button",
		"minecraft:dark_oak_button",
		"minecraft:mangrove_button",
		"minecraft:cherry_button",
		"minecraft:bamboo_button",
		"minecraft:polished_blackstone_button",
		"minecraft:crimson_button",
		"minecraft:warped_button",
		"minecraft:pale_oak_button"
	)

	fun shouldCancel(block: String): Boolean {
		val id = block.lowercase()
		return id !in AIR && id !in ALLOWED
	}
}
