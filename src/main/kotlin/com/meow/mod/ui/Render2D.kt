package com.meow.mod.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import java.awt.Color

object Render2D {
	private val font get() = Minecraft.getInstance().font

	fun rect(ctx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Color) {
		argb(ctx, x, y, w, h, ColorPalette.argb(color))
	}

	fun argb(ctx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, argb: Int) {
		ctx.fill(RenderPipelines.GUI, x, y, x + w, y + h, argb)
	}

	fun outline(ctx: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, argb: Int, lw: Int = 1) {
		ctx.fill(RenderPipelines.GUI, x, y, x + w, y + lw, argb)
		ctx.fill(RenderPipelines.GUI, x, y + lw, x + lw, y + h, argb)
		ctx.fill(RenderPipelines.GUI, x + w - lw, y + lw, x + w, y + h, argb)
		ctx.fill(RenderPipelines.GUI, x + lw, y + h - lw, x + w - lw, y + h, argb)
	}

	@JvmOverloads
	fun text(ctx: GuiGraphicsExtractor, str: String, x: Int, y: Int, argb: Int, shadow: Boolean = false, scale: Float = 1f) {
		val pose = ctx.pose()
		pose.pushMatrix()
		pose.translate(x.toFloat(), y.toFloat())
		if (scale != 1f) pose.scale(scale, scale)
		ctx.text(font, str, 0, 0, argb, shadow)
		pose.popMatrix()
	}

	fun width(str: String): Int = font.width(str)
}
