package com.meow.mod.mixin;

import com.meow.mod.features.CustomName;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public class FontMixin {

	@ModifyVariable(
		method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		argsOnly = true
	)
	private String meowmod$prepareString(String text) {
		return CustomName.INSTANCE.replaceStringIfNeeded(text);
	}

	@ModifyVariable(
		method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"),
		argsOnly = true
	)
	private FormattedCharSequence meowmod$prepareSequence(FormattedCharSequence text) {
		return CustomName.INSTANCE.replaceSequenceIfNeeded(text);
	}

	@ModifyVariable(
		method = "width(Ljava/lang/String;)I",
		at = @At("HEAD"),
		argsOnly = true
	)
	private String meowmod$widthString(String text) {
		return CustomName.INSTANCE.replaceStringIfNeeded(text);
	}

	@ModifyVariable(
		method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
		at = @At("HEAD"),
		argsOnly = true
	)
	private FormattedText meowmod$widthFormattedText(FormattedText text) {
		if (text instanceof Component component) {
			Component replaced = CustomName.INSTANCE.replaceComponentIfNeeded(component);
			if (replaced != null) return replaced;
		}
		return text;
	}

	@ModifyVariable(
		method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
		at = @At("HEAD"),
		argsOnly = true
	)
	private FormattedCharSequence meowmod$widthSequence(FormattedCharSequence text) {
		return CustomName.INSTANCE.replaceSequenceIfNeeded(text);
	}
}
