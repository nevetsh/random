package com.meow.mod.mixin;

import com.meow.mod.api.ClickLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A physical mouse press enters the game here, and in world it is turned into an attack-key click
// right here (KeyMapping.set, then KeyMapping.click), so this is the one place the player's own
// left click can be dropped without also dropping the module's. The module never goes through the
// mouse - it queues its click onto the attack key directly - so gating the press only ever touches
// the player. Menus are left alone: with a screen or an overlay up the press goes through as it
// always did.
@Mixin(MouseHandler.class)
public class AttackMixin {

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void meowmod$blockManualAttack(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (!ClickLock.INSTANCE.getBlocked()) return;
		// Only the press makes the click; the release is left to run so the key state stays right.
		if (action != 1) return;
		if (button.button() != 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.screen() != null || minecraft.gui.overlay() != null) return;
		ci.cancel();
	}
}
