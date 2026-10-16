package com.meow.mod.mixin;

import com.meow.mod.features.HideFromServers;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger("MeowMod");

	@ModifyVariable(
		method = "sendPacket",
		at = @At("HEAD"),
		argsOnly = true
	)
	private Packet<?> meowmod$spoofBrand(Packet<?> packet) {
		if (!HideFromServers.INSTANCE.isHiding()) return packet;
		if (!(packet instanceof ServerboundCustomPayloadPacket)) return packet;

		if (((ServerboundCustomPayloadPacket) packet).payload() instanceof BrandPayload) {
			String original = ((BrandPayload) ((ServerboundCustomPayloadPacket) packet).payload()).brand();
			LOGGER.info("[MeowMod] Spoofing client brand: '{}' -> 'vanilla'", original);
			return new ServerboundCustomPayloadPacket(new BrandPayload("vanilla"));
		}
		return packet;
	}

	@Inject(
		method = "sendPacket",
		at = @At("HEAD"),
		cancellable = true
	)
	private void meowmod$blockModPayloads(
		Packet<?> packet,
		ChannelFutureListener channelFutureListener,
		boolean flush,
		CallbackInfo ci
	) {
		if (!HideFromServers.INSTANCE.isHiding()) return;
		if (!(packet instanceof ServerboundCustomPayloadPacket)) return;

		Identifier id = ((ServerboundCustomPayloadPacket) packet).payload().type().id();
		String namespace = id.getNamespace();
		String path = id.getPath();

		if (namespace.equals("fabric")) {
			LOGGER.info("[MeowMod] Blocked outbound mod payload: {}", id);
			ci.cancel();
			return;
		}
		if (namespace.equals("minecraft") && (path.equals("register") || path.equals("unregister"))) {
			LOGGER.info("[MeowMod] Blocked outbound mod payload: {}", id);
			ci.cancel();
		}
	}
}
