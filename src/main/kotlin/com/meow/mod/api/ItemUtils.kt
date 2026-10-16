package com.meow.mod.api

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

object ItemUtils {
	fun extraAttributes(itemStack: ItemStack): CompoundTag? =
		itemStack.get(DataComponents.CUSTOM_DATA)?.copyTag()
}
