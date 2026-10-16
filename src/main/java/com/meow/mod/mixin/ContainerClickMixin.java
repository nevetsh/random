package com.meow.mod.mixin;

import com.meow.mod.features.AutoAuction;
import com.meow.mod.features.AutoBz;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Watches container clicks so features can react to a specific item being clicked. Used by
// Auto Auction, which only starts working once the player clicks the "Create Auction" item
// rather than as soon as any auction menu is open.
@Mixin(AbstractContainerScreen.class)
public class ContainerClickMixin {

	@Inject(
		method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
		at = @At("HEAD")
	)
	private void meowmod$onSlotClicked(
		Slot slot,
		int slotId,
		int button,
		ContainerInput clickType,
		CallbackInfo ci
	) {
		// Vanilla calls this with a null slot when the click missed every slot.
		if (slot == null) return;
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) return;
		AutoAuction.INSTANCE.onContainerSlotClicked(stack.getHoverName().getString());
		// Auto BZ needs to know which slot was clicked and how: an Auto Sell listing starts
		// from a left-click on one of the player's own inventory slots, and an order entry or
		// a cancel button is never one of those.
		AutoBz.INSTANCE.onContainerSlotClicked(stack, slotId, button, clickType);
	}
}
