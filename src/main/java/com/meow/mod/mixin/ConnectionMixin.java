package com.meow.mod.mixin;

import com.meow.mod.api.PayloadIdentity;
import com.meow.mod.features.HideFromServers;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Connection.class)
public class ConnectionMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger("MeowMod");

	// Hide MeowMod: this mod's own channels come out of the channel list Fabric announces on join,
	// and everything else in that list is left exactly as it was. The client brand is not touched,
	// so the client never claims to be vanilla. Blanket-blocking mod channels looked tempting but
	// took other mods down with it: Hypixel's Mod API registration is what Skyblocker reads its
	// location from, and without it Skyblocker's End and Slayer features silently do nothing.
	@ModifyVariable(
		method = "sendPacket",
		at = @At("HEAD"),
		argsOnly = true
	)
	private Packet<?> meowmod$hideOwnChannels(Packet<?> packet) {
		if (!HideFromServers.INSTANCE.isHiding()) return packet;
		if (!(packet instanceof ServerboundCustomPayloadPacket custom)) return packet;
		if (!(custom.payload() instanceof RegistrationPayload registration)) return packet;

		try {
			List<String> channels = registration.channels().stream().map(Identifier::toString).toList();
			List<String> kept = PayloadIdentity.INSTANCE.keep(channels);
			if (kept.size() == channels.size()) return packet;

			LOGGER.info(
				"[MeowMod] Hide MeowMod: dropping {} own channel(s) from the registration Fabric is sending",
				channels.size() - kept.size()
			);
			List<Identifier> filtered = kept.stream().map(Identifier::parse).toList();
			return new ServerboundCustomPayloadPacket(new RegistrationPayload(registration.type(), filtered));
		} catch (Throwable t) {
			// Cosmetic filtering must never cost a packet: whatever went wrong, send the original.
			LOGGER.warn("[MeowMod] Hide MeowMod: could not filter the channel list, sending it as is", t);
			return packet;
		}
	}

	// Any payload of this mod's own never leaves the client. Nothing else is dropped, so the Mod
	// API handshake and other mods' channels keep working.
	@Inject(
		method = "sendPacket",
		at = @At("HEAD"),
		cancellable = true
	)
	private void meowmod$hideOwnPayloads(
		Packet<?> packet,
		ChannelFutureListener channelFutureListener,
		boolean flush,
		CallbackInfo ci
	) {
		if (!HideFromServers.INSTANCE.isHiding()) return;
		if (!(packet instanceof ServerboundCustomPayloadPacket custom)) return;

		Identifier id = custom.payload().type().id();
		if (!PayloadIdentity.INSTANCE.isOwn(id.toString())) return;

		LOGGER.info("[MeowMod] Hide MeowMod: blocked outbound own payload: {}", id);
		ci.cancel();
	}
}
