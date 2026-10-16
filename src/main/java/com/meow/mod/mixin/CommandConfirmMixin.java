package com.meow.mod.mixin;

import com.meow.mod.features.SkipCommandConfirm;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Commands clicked in chat go through Screen.clickCommandAction -> sendUnattendedCommand.
// That method verifies the command against the client's copy of the server command tree
// and, when it does not parse, opens the "Confirm Command Execution" screen instead of
// sending it. SkyBlock (and most non-vanilla servers) only publish part of their tree,
// so legitimate commands land on that popup.
//
// When the module is on we send the command the same way the "no issues" branch does and
// skip the popup. Commands the client *does* understand are untouched, as are the
// permission and chat-signature confirmations.
@Mixin(ClientPacketListener.class)
public class CommandConfirmMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger("MeowMod");

	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;

	@Shadow
	private ClientSuggestionProvider suggestionsProvider;

	@Inject(
		method = "sendUnattendedCommand(Ljava/lang/String;Lnet/minecraft/client/gui/screens/Screen;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void meowmod$sendUnknownCommandWithoutConfirm(
		String command,
		Screen returnScreen,
		CallbackInfo ci
	) {
		if (!SkipCommandConfirm.INSTANCE.isActive()) return;
		if (command.isBlank() || isKnownCommand(command)) return;

		LOGGER.info("[MeowMod] Sending unrecognized command without confirmation: /{}", command);

		ClientPacketListener self = (ClientPacketListener) (Object) this;
		self.sendCommand(command);
		Minecraft.getInstance().gui.setScreen(returnScreen);
		ci.cancel();
	}

	// Mirrors ClientPacketListener.verifyCommand -> isValidCommand: a command counts as
	// recognised only when it parses completely, without errors, to an executable node.
	private boolean isKnownCommand(String command) {
		try {
			ParseResults<ClientSuggestionProvider> parse = commands.parse(command, suggestionsProvider);
			return !parse.getReader().canRead()
				&& parse.getExceptions().isEmpty()
				&& parse.getContext().getLastChild().getCommand() != null;
		} catch (Exception e) {
			// If we cannot tell, leave vanilla behaviour alone.
			return true;
		}
	}
}
