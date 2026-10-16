package com.meow.mod.skyblock

// What Maddox's menu offers, read off each item's tooltip.
//
// The items themselves are useless as identifiers: Hypixel hands out the finished quest, the
// quest list and the start button as the slayer's own drop, so the "Click to ..." line of the
// lore is the only thing that says which is which. Kept pure and free of Minecraft types so the
// choice can be exercised without a game running.
object SlayerMenu {
	/** The finished quest's reward - the only line that offers a collection. */
	const val COLLECT = "Click to collect reward"

	/** A quest that can be started: the tier list, or the single quest once the reward is taken. */
	const val SLAY = "Click to slay"

	/** The finished quest's own item, which opens the quest it was against. */
	const val VIEW = "Click to view boss"

	/** The confirmation screen's button. */
	const val START = "Click to start quest"

	// "Boss: Revenant Horror IV" on the completion item. This is what makes "the same quest"
	// mean the same tier rather than whichever one the menu happens to offer first.
	private val BOSS = Regex("Boss:\\s*(.+)", RegexOption.IGNORE_CASE)

	/** One clickable item of the menu, reduced to everything the choice depends on. */
	data class Entry(
		val slot: Int,
		val action: String,
		/** The boss this item is about: its own name, or the boss a summary item names. */
		val boss: String,
		/** The boss a completion item names ("Boss: Revenant Horror IV"), null on any other. */
		val quest: String? = null
	)

	/** Which of the four actions an item offers, or null when it offers none of them. */
	fun actionOf(lore: List<String>): String? {
		for (action in ACTIONS) {
			if (lore.any { it.contains(action, ignoreCase = true) }) return action
		}
		return null
	}

	/** The boss and tier a completion item names, or null when it names none. */
	fun bossOf(lore: List<String>): String? =
		lore.firstNotNullOfOrNull { BOSS.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }

	// The order the four actions are used in: the reward is collected first (it is free and the
	// item disappears with it), then the quest, then the overview item that leads to it, and
	// the confirmation button last, since it is what actually starts the quest.
	private fun priority(action: String): Int = when (action) {
		COLLECT -> 0
		SLAY -> 1
		VIEW -> 2
		else -> 3
	}

	/**
	 * The item to click next, or null when the menu offers nothing this module knows: the
	 * highest-priority action, and among those the boss the finished quest was against, so the
	 * same quest is started again instead of whichever one the menu lists first.
	 */
	fun choose(entries: List<Entry>, wantedBoss: String?): Entry? {
		val action = entries.minByOrNull { priority(it.action) }?.action ?: return null
		val sameAction = entries.filter { it.action == action }
		if (wantedBoss != null) {
			sameAction.firstOrNull { it.boss.equals(wantedBoss, ignoreCase = true) }?.let { return it }
		}
		return sameAction.first()
	}

	private val ACTIONS = listOf(COLLECT, SLAY, VIEW, START)
}
