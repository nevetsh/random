package com.meow.mod.mixin;

import com.meow.mod.features.AutoReforge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Draws Auto Reforge's panel at the left of a container menu - the module decides for
// itself whether the menu on screen is the reforge one - and hands the mouse and keyboard
// to it before the menu gets them. The panel only draws and only reads clicks: nothing
// about the menu, the item or the player is sent anywhere.
@Mixin(AbstractContainerScreen.class)
public class ReforgeOverlayMixin {
	@Shadow
	protected int leftPos;

	@Shadow
	protected int topPos;

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void meowmod$autoReforgePanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AutoReforge.INSTANCE.renderOverlay((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, leftPos, topPos);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void meowmod$autoReforgeMouse(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (AutoReforge.INSTANCE.handleMouse((int) event.x(), (int) event.y())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void meowmod$autoReforgeKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (AutoReforge.INSTANCE.handleKey(event.key())) {
			cir.setReturnValue(true);
		}
	}
}
