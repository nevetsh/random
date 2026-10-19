package com.meow.mod.mixin;

import com.meow.mod.api.PayloadIdentity;
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

	// Always hide MeowMod: its own channels drop out of the channel list Fabric announces on
	// join, and its own payloads never leave the client. Everything else passes untouched.
	@ModifyVariable(
		method = "sendPacket",
		at = @At("HEAD"),
		argsOnly = true
	)
	private Packet<?> meowmod$hideOwnChannels(Packet<?> packet) {
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
			LOGGER.warn("[MeowMod] Hide MeowMod: could not filter the channel list, sending it as is", t);
			return packet;
		}
	}

	// MeowMod's own payloads never leave the client.
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
		if (!(packet instanceof ServerboundCustomPayloadPacket custom)) return;

		Identifier id = custom.payload().type().id();
		if (!PayloadIdentity.INSTANCE.isOwn(id.toString())) return;

		LOGGER.info("[MeowMod] Hide MeowMod: blocked outbound own payload: {}", id);
		ci.cancel();
	}
}
