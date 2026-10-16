package com.meow.mod.api

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

object ItemUtils {
	fun extraAttributes(itemStack: ItemStack): CompoundTag? =
		itemStack.get(DataComponents.CUSTOM_DATA)?.copyTag()

	// The tooltip body of an item, one entry per line, with colour codes stripped so it
	// can be matched against plain text.
	fun lore(itemStack: ItemStack): List<String> =
		itemStack.get(DataComponents.LORE)?.lines()?.map { it.string.noControlCodes() } ?: emptyList()

	fun skullTexture(itemStack: ItemStack): String? {
		if (itemStack.isEmpty) return null
		val profile = itemStack.get(DataComponents.PROFILE) ?: return null
		return profile.partialProfile().properties["textures"].firstOrNull()?.value
	}
}
