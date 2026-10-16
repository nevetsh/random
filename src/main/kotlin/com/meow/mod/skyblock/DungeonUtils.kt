package com.meow.mod.skyblock

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState

// Ported from Odin (odtheking/Odin, BSD-3-Clause) — https://github.com/odtheking/Odin
enum class Floor(val requiredPercentage: Float = 1f) {
	E(0.3f),
	F1(0.3f), F2(0.4f), F3(0.5f), F4(0.6f), F5(0.7f), F6(0.85f), F7,
	M1, M2, M3, M4, M5, M6, M7;

	val floorNumber: Int
		get() = when (this) {
			E -> 0
			F1, M1 -> 1
			F2, M2 -> 2
			F3, M3 -> 3
			F4, M4 -> 4
			F5, M5 -> 5
			F6, M6 -> 6
			F7, M7 -> 7
		}

	val isMM: Boolean
		get() = when (this) {
			E, F1, F2, F3, F4, F5, F6, F7 -> false
			M1, M2, M3, M4, M5, M6, M7 -> true
		}
}

enum class M7Phases(private val displayName: String) {
	P1("P1"), P2("P2"), P3("P3"), P4("P4"), P5("P5"), Unknown("Unknown");

	override fun toString(): String = displayName
}

object DungeonUtils {
	private const val WITHER_ESSENCE_ID = "2865274b-3097-394e-8149-ec629c72d850"
	private const val REDSTONE_KEY = "fed95410-aba1-39df-9b95-1d4f361eb66e"

	val inDungeons: Boolean
		get() = LocationUtils.isCurrentArea(Island.Dungeon)

	val inClear: Boolean
		get() = inDungeons && !inBoss

	val inBoss: Boolean
		get() {
			val player = Minecraft.getInstance().player ?: return false
			val number = LocationUtils.floor?.floorNumber ?: return false
			return when (number) {
				1 -> player.x > -71 && player.z > -39
				in 2..4 -> player.x > -39 && player.z > -39
				in 5..6 -> player.x > -39 && player.z > -7
				7 -> player.x > -7 && player.z > -7
				else -> false
			}
		}

	fun isFloor(vararg options: Int): Boolean =
		LocationUtils.floor?.floorNumber?.let { it in options } ?: false

	fun getF7Phase(): M7Phases {
		if ((!isFloor(7) || !inBoss)) return M7Phases.Unknown
		val y = Minecraft.getInstance().player?.y ?: return M7Phases.Unknown
		return when {
			y > 210 -> M7Phases.P1
			y > 155 -> M7Phases.P2
			y > 100 -> M7Phases.P3
			y > 45 -> M7Phases.P4
			else -> M7Phases.P5
		}
	}

	fun isSecret(state: BlockState, pos: BlockPos): Boolean = when {
		state.block == net.minecraft.world.level.block.Blocks.CHEST ||
			state.block == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST ||
			state.block == net.minecraft.world.level.block.Blocks.LEVER -> true

		state.block is SkullBlock -> {
			val owner = Minecraft.getInstance().level?.getBlockEntity(pos) as? SkullBlockEntity
			val id = owner?.ownerProfile?.partialProfile()?.id?.toString()
			id == WITHER_ESSENCE_ID || id == REDSTONE_KEY
		}

		else -> false
	}
}
