package com.meow.mod.mixin;

import com.meow.mod.features.AutoBz;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

// Hypixel's Bazaar asks for the search query and the order amount through a real sign
// GUI, and vanilla only sends that text to the server from removed() - the method that
// runs when the screen closes, whether you press ESC or click Done. Playing back a sign
// therefore means writing the text into the screen's line buffer just before it closes,
// which is what this does for whichever module has a line waiting.
@Mixin(AbstractSignEditScreen.class)
public class SignEditScreenMixin {
	@Shadow
	private String[] messages;

	@Inject(method = "removed", at = @At("HEAD"))
	private void meowmod$fillPendingSignText(CallbackInfo ci) {
		String text = AutoBz.INSTANCE.getPendingSignText();
		if (text == null) return;

		Arrays.fill(this.messages, "");
		this.messages[0] = text;
	}
}
