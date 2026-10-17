package com.meow.mod.mixin;

import com.meow.mod.api.ActionBar;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

// The action bar is not a chat message, so Fabric's message events never see it. In this version
// it is also not always delivered as ClientboundSetActionBarTextPacket: Hypixel sends the stat
// line as a system chat packet whose overlay flag is set. Both routes - the plain action-bar
// packet (ClientPacketListener.setActionBarText) and the overlay chat packet
// (ChatListener.handleOverlay) - funnel into Hud.setOverlayMessage, so that is the one place
// worth catching. Doing it here keeps MeowMod's own reading of the stat line (see ActionBar)
// filled in no matter which packet the server chose.
@Mixin(Hud.class)
public class ActionBarMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger("MeowMod");
	private static final AtomicBoolean SEEN = new AtomicBoolean(false);

	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void meowmod$captureActionBar(Component message, boolean animate, CallbackInfo ci) {
		try {
			if (SEEN.compareAndSet(false, true)) {
				LOGGER.info("[MeowMod] first action bar captured: [{}]", message.getString());
			}
			ActionBar.INSTANCE.update(message);
		} catch (Throwable t) {
			// A bad bar must never cost the packet: report it and let it through untouched.
			LOGGER.warn("[MeowMod] could not read the action bar", t);
		}
	}
}
