package com.meow.mod.skyblock

import com.meow.mod.api.noControlCodes
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

// Reads the Hypixel sidebar (the scoreboard on the right). Hypixel puts the visible text in
// each entry's scoreboard team, so the entry owner on its own is not the line - it has to be
// run through the team before it reads as what the player sees.
object Sidebar {
	fun lines(): List<String> {
		val level = Minecraft.getInstance().level ?: return emptyList()
		val scoreboard = level.scoreboard
		val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
		return scoreboard.listPlayerScores(objective)
			.filter { !it.isHidden() }
			.map { entry ->
				val team = scoreboard.getPlayersTeam(entry.owner())
				val text = if (team != null) team.getFormattedName(entry.ownerName()).string else entry.ownerName().string
				text.noControlCodes().trim()
			}
			.filter { it.isNotEmpty() }
	}

	fun contains(text: String): Boolean =
		lines().any { it.equals(text, ignoreCase = true) || it.contains(text, ignoreCase = true) }
}
