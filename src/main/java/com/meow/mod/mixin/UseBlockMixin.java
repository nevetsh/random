package com.meow.mod.mixin;

import com.meow.mod.features.NoInteract;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The client decides to interact with a block here, and sends the packet for it inside the
// same call, so this is where No Interact learns what was clicked before that packet goes
// out. Both hands are passed through, and the module only acts on a pearl.
@Mixin(MultiPlayerGameMode.class)
public class UseBlockMixin {

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void meowmod$onUseItemOn(
		LocalPlayer player,
		InteractionHand hand,
		BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		Level level = player.level();
		String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString();
		String held = BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()).toString();
		NoInteract.INSTANCE.onBlockInteraction(held, block);
	}
}
