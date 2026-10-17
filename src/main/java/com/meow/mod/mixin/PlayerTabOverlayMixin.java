package com.meow.mod.mixin;

import com.meow.mod.features.HideServerId;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// The tab header's date line ends in the server's id ("10/16/26 m72DM 102,66"). Hide
// Server ID drops that token before the header is stored, so what is on screen - and in a
// screenshot - no longer says which of Hypixel's servers this is. Only the text shown
// changes; nothing about it, or anything else, is sent anywhere.
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@ModifyVariable(method = "setHeader", at = @At("HEAD"), argsOnly = true)
	private Component meowmod$stripServerId(Component header) {
		Component stripped = HideServerId.INSTANCE.replaceComponentIfNeeded(header);
		return stripped != null ? stripped : header;
	}
}
