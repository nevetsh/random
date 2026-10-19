package com.meow.mod.ui

import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData
import com.meow.mod.features.AutoFragrun
import com.meow.mod.features.ModuleKeys
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

// The module key screen: the same shape as the command keybinds editor, with a module where
// that one has a command. Every module is listed with the key it answers to, and pressing a
// key there switches that module on and off in game (see ModuleKeys).
class ModuleKeysScreen(private val parent: Screen?) : Screen(Component.literal("MeowMod.ModuleKeys")) {
	private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
		fun contains(mx: Int, my: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private data class Row(val feature: ConfigData.FeatureSwitch, val key: Rect, val clear: Rect)

	private var rows = listOf<Row>()
	private var doneRect: Rect? = null
	private var mouseX = 0
	private var mouseY = 0
	private var scroll = 0.0
	private var maxScroll = 0.0

	// The module whose key box is waiting for a press. Held as the module itself rather than as
	// a row number, so a list that scrolls under the cursor cannot move the box being bound.
	private var listening: ConfigData.FeatureSwitch? = null

	private var notice = ""
	private var noticeUntil = 0L

	private val panelW get() = minOf(width - 60, 460)
	private val panelH get() = minOf(height - 60, 360)
	private val panelX get() = (width - panelW) / 2
	private val panelY get() = (height - panelH) / 2

	private val contentTop get() = panelY + 40
	// Never negative: a window short enough to leave no room still has to draw something rather
	// than hand a nonsense height to the scissor stack.
	private val contentH get() = (panelY + panelH - 34 - contentTop).coerceAtLeast(0)

	override fun init() {}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, a)
		this.mouseX = mouseX
		this.mouseY = mouseY

		Render2D.rect(graphics, 0, 0, width, height, ColorPalette.BACKGROUND)
		Render2D.rect(graphics, panelX, panelY, panelW, panelH, ColorPalette.PRIMARY)
		Render2D.outline(graphics, panelX, panelY, panelW, panelH, ColorPalette.argb(ColorPalette.OUTLINE))

		Render2D.text(graphics, "Module Keybinds", panelX + 8, panelY + 8, ColorPalette.argb(ColorPalette.TEXT), false, 1.3f)
		Render2D.text(
			graphics,
			"Click a key to bind it to that module. Backspace clears.",
			panelX + 8, panelY + 20,
			ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.85f
		)
		Render2D.argb(graphics, panelX + 1, panelY + 32, panelW - 2, 1, ColorPalette.argb(ColorPalette.OUTLINE))

		drawRows(graphics)
		drawFooter(graphics)
	}

	private fun drawRows(graphics: GuiGraphicsExtractor) {
		// Alphabetical, like the modules in the config screen's sidebar.
		val features = Config.features.filter { it.listedFor() }.sortedBy { it.displayName.lowercase() }
		val rowH = 22
		val total = features.size * rowH
		maxScroll = (total - contentH).coerceAtLeast(0).toDouble()
		if (scroll > maxScroll) scroll = maxScroll
		if (scroll < 0.0) scroll = 0.0

		val leftX = panelX + 8
		val rightEdge = panelX + panelW - 8
		val newRows = mutableListOf<Row>()

		graphics.scissorStack.push(ScreenRectangle(panelX + 4, contentTop, panelW - 8, contentH))
		features.forEachIndexed { index, feature ->
			val y = contentTop + index * rowH - scroll.roundToInt()
			if (y + rowH < contentTop || y > contentTop + contentH) return@forEachIndexed

			val keyRect = Rect(leftX, y, 84, 16)
			val clearRect = Rect(rightEdge - 16, y, 16, 16)

			val listeningThis = listening === feature
			Render2D.rect(graphics, keyRect.x, keyRect.y, keyRect.w, keyRect.h, ColorPalette.TERTIARY)
			Render2D.outline(graphics, keyRect.x, keyRect.y, keyRect.w, keyRect.h, ColorPalette.argb(ColorPalette.OUTLINE))
			val keyLabel = if (listeningThis) "Press a key..." else ModuleKeys.keyName(feature.toggleKey)
			val keyColor = if (listeningThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.TEXT)
			Render2D.text(graphics, keyLabel, keyRect.x + 4, keyRect.y + 4, keyColor, false, 0.9f)

			// The module's own name, and whether it is on: the screen is also the quickest way to
			// see what is running without walking every module's page. A module that is off has
			// room kept for its marker, so the name never runs under the clear button.
			val on = feature.get()
			val nameX = keyRect.x + keyRect.w + 4
			val nameMax = (clearRect.x - nameX - 4 - if (on) 0 else 22).coerceAtLeast(0)
			val name = truncate(feature.displayName, nameMax, 0.9f)
			Render2D.text(graphics, name, nameX, y + 4, ColorPalette.argb(ColorPalette.TEXT), false, 0.9f)
			if (!on) {
				val suffix = "  off"
				val suffixX = nameX + (Render2D.width(name) * 0.9f).roundToInt()
				Render2D.text(graphics, suffix, suffixX, y + 5, ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.8f)
			}

			val hover = clearRect.contains(mouseX, mouseY)
			Render2D.rect(graphics, clearRect.x, clearRect.y, clearRect.w, clearRect.h, if (hover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
			Render2D.outline(graphics, clearRect.x, clearRect.y, clearRect.w, clearRect.h, ColorPalette.argb(ColorPalette.OUTLINE))
			val xLabel = "x"
			Render2D.text(
				graphics, xLabel,
				clearRect.x + (clearRect.w - Render2D.width(xLabel)) / 2, clearRect.y + 4,
				ColorPalette.argb(ColorPalette.TEXT), false, 0.9f
			)

			newRows.add(Row(feature, keyRect, clearRect))
		}
		graphics.scissorStack.pop()
		rows = newRows
	}

	private fun drawFooter(graphics: GuiGraphicsExtractor) {
		if (notice.isNotEmpty() && System.currentTimeMillis() < noticeUntil) {
			Render2D.text(graphics, notice, panelX + 8, panelY + panelH - 21, ColorPalette.argb(ColorPalette.ACCENT), false, 0.85f)
		}

		val doneW = 70
		val done = Rect(panelX + panelW - 8 - doneW, panelY + panelH - 24, doneW, 16)
		doneRect = done
		val doneHover = done.contains(mouseX, mouseY)
		Render2D.rect(graphics, done.x, done.y, done.w, done.h, if (doneHover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
		Render2D.outline(graphics, done.x, done.y, done.w, done.h, ColorPalette.argb(ColorPalette.OUTLINE))
		val doneLabel = "Done"
		Render2D.text(
			graphics, doneLabel,
			done.x + (done.w - Render2D.width(doneLabel)) / 2, done.y + 4,
			ColorPalette.argb(ColorPalette.TEXT), false, 0.9f
		)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
		val mx = event.x.toInt()
		val my = event.y.toInt()
		if (event.button() != 0) return super.mouseClicked(event, doubled)

		if (doneRect?.contains(mx, my) == true) {
			onClose()
			return true
		}

		for (row in rows) {
			if (row.key.contains(mx, my)) {
				listening = if (listening === row.feature) null else row.feature
				return true
			}
			if (row.clear.contains(mx, my)) {
				ModuleKeys.bind(row.feature, 0)
				listening = null
				return true
			}
		}

		// A click anywhere else puts the listening box away, so the next key press is the game's
		// rather than a key being bound by accident.
		listening = null
		return super.mouseClicked(event, doubled)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val feature = listening ?: return super.keyPressed(event)
		when (event.key()) {
			InputConstants.KEY_ESCAPE -> {
				listening = null
				return true
			}

			InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> {
				ModuleKeys.bind(feature, 0)
				listening = null
				return true
			}

			else -> {
				val taken = ModuleKeys.bind(feature, event.key())
				val note = StringBuilder()
				if (taken != null) note.append("${taken.displayName} gave up that key. ")
				val command = AutoFragrun.commandForKey(event.key())
				if (command != null) note.append("A command binding runs it, so that wins.")
				notice = note.toString().trim()
				noticeUntil = System.currentTimeMillis() + NOTICE_MS
				listening = null
				return true
			}
		}
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (maxScroll > 0) {
			scroll -= verticalAmount * 15
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	override fun onClose() {
		if (parent != null) Minecraft.getInstance().setScreenAndShow(parent)
		else super.onClose()
	}

	override fun isPauseScreen(): Boolean = false

	private fun truncate(text: String, maxPx: Int, scale: Float): String {
		if (maxPx <= 0) return ""
		if ((Render2D.width(text) * scale).toInt() <= maxPx) return text
		val ellipsis = "..."
		val ellipsisPx = (Render2D.width(ellipsis) * scale).toInt()
		var end = text.length
		while (end > 0 && (Render2D.width(text.substring(0, end)) * scale).toInt() + ellipsisPx > maxPx) end--
		return if (end <= 0) "" else text.substring(0, end) + ellipsis
	}

	private companion object {
		// How long a note about a key the screen would not hand over stays on screen.
		const val NOTICE_MS = 4000L
	}
}
