package com.meow.mod.mixin;

import com.meow.mod.features.CustomName;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

	@ModifyVariable(
		method = "handleChatInput(Ljava/lang/String;Z)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private String meowmod$rewriteOutgoing(String message) {
		return CustomName.INSTANCE.rewriteOutgoing(message);
	}
}
