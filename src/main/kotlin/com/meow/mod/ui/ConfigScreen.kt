package com.meow.mod.ui

import com.meow.mod.MeowMod
import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

class ConfigScreen : Screen(Component.literal("MeowMod.ConfigScreen")) {
	private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
		fun contains(mx: Int, my: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private data class Control(val data: ConfigData<*>, val track: Rect, val valueRect: Rect? = null)

	private var selected: String = Config.subcategories.firstOrNull() ?: Config.CATEGORY_NAME
	private var scroll = 0.0
	private var maxScroll = 0.0
	private var dragging: ConfigData.Slider? = null
	private var editing: ConfigData.Slider? = null
	private var editBuffer: String = ""
	private var editingText: ConfigData.Text? = null
	private var textBuffer: String = ""

	private var rows = listOf<Control>()
	private var closeRect: Rect? = null
	private var mouseX = 0
	private var mouseY = 0

	private val leftX get() = pctX(17.5)
	private val mainY get() = pctY(17.5)
	private val mainW get() = pctW(65.0)
	private val mainH get() = pctH(65.0)
	private val leftW get() = pctW(65.0 * 0.20)
	private val rightX get() = leftX + leftW + pctW(65.0 * 0.01)
	private val rightY get() = mainY + pctH(65.0 * 0.01)
	private val rightW get() = leftX + mainW - rightX - pctW(65.0 * 0.01)
	private val rightH get() = mainY + mainH - rightY - pctH(65.0 * 0.01)

	private fun pctX(p: Double) = (width * p / 100.0).roundToInt()
	private fun pctY(p: Double) = (height * p / 100.0).roundToInt()
	private fun pctW(p: Double) = (width * p / 100.0).roundToInt()
	private fun pctH(p: Double) = (height * p / 100.0).roundToInt()

	override fun init() {
		if (Config.subcategories.isNotEmpty() && selected !in Config.subcategories) {
			selected = Config.subcategories.first()
		}
	}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, a)
		this.mouseX = mouseX
		this.mouseY = mouseY

		Render2D.rect(graphics, 0, 0, width, height, ColorPalette.BACKGROUND)

		Render2D.rect(graphics, leftX, mainY, mainW, mainH, ColorPalette.PRIMARY)
		Render2D.rect(graphics, leftX, mainY, leftW, mainH, ColorPalette.SECONDARY)

		Render2D.text(graphics, "MeowMod", leftX + 4, mainY + 5, ColorPalette.argb(ColorPalette.TEXT), false, 1.4f)
		Render2D.text(graphics, "v${MeowMod.VERSION}", leftX + 4, mainY + 16, ColorPalette.argb(ColorPalette.TITLE), false, 1f)

		drawCategories(graphics)
		drawClose(graphics)
		drawContent(graphics, mouseX, mouseY)
	}

	private fun drawCategories(graphics: GuiGraphicsExtractor) {
		var y = mainY + 32
		val entryH = 14
		for (sub in Config.subcategories) {
			val rect = Rect(leftX + 3, y, leftW - 6, entryH)
			val active = sub == selected
			if (active) Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.TERTIARY)
			val color = if (active) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
			Render2D.text(graphics, sub, rect.x + 4, rect.y + 3, color, false, 1f)
			if (active) Render2D.argb(graphics, rect.x, rect.y + rect.h - 1, rect.w, 1, ColorPalette.argb(ColorPalette.ACCENT))
			y += entryH + 2
		}
	}

	private fun drawClose(graphics: GuiGraphicsExtractor) {
		val rect = Rect(leftX + pctW(65.0 * 0.02), mainY + mainH - 20, leftW - pctW(65.0 * 0.04), 14)
		closeRect = rect
		val hover = rect.contains(mouseX, mouseY)
		Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, if (hover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
		val label = "Close"
		val tx = rect.x + (rect.w - Render2D.width(label)) / 2
		Render2D.text(graphics, label, tx, rect.y + 3, ColorPalette.argb(ColorPalette.TEXT), false, 1f)
	}

	private fun drawContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		Render2D.rect(graphics, rightX, rightY, rightW, rightH, ColorPalette.SECONDARY)
		Render2D.outline(graphics, rightX, rightY, rightW, rightH, ColorPalette.argb(ColorPalette.OUTLINE))

		val headerH = 16
		Render2D.text(
			graphics, selected, rightX + 6, rightY + 4,
			ColorPalette.argb(ColorPalette.TEXT), false, 1.2f
		)
		Render2D.argb(graphics, rightX + 1, rightY + headerH - 1, rightW - 2, 1, ColorPalette.argb(ColorPalette.OUTLINE))

		val contentTop = rightY + headerH
		val contentH = rightY + rightH - contentTop
		val rowH = 20
		val data = Config.configsFor(selected)
		val total = data.size * rowH
		maxScroll = (total - contentH).coerceAtLeast(0).toDouble()
		if (scroll > maxScroll) scroll = maxScroll
		if (scroll < 0.0) scroll = 0.0

		val newRows = mutableListOf<Control>()
		graphics.scissorStack.push(ScreenRectangle(rightX, contentTop, rightW, contentH))
		data.forEachIndexed { index, configData ->
			val rowY = contentTop + (index * rowH) - scroll.roundToInt()
			if (rowY + rowH < contentTop || rowY > contentTop + contentH) return@forEachIndexed

			val rowX = rightX + 4
			val rowW = rightW - 8

			val controlW = when (configData) {
				is ConfigData.FeatureSwitch, is ConfigData.Switch -> 30
				is ConfigData.Slider -> 104
				is ConfigData.Text -> minOf(160, rowW / 2) + 6
				is ConfigData.Button -> 66
				else -> 0
			}
			val textW = (rowW - controlW - 9).coerceAtLeast(0)

			val hovered = mouseX >= rowX && mouseX < rowX + rowW && mouseY >= rowY && mouseY < rowY + rowH
			if (hovered) {
				Render2D.argb(graphics, rowX, rowY, rowW, rowH - 2, ColorPalette.argb(ColorPalette.TERTIARY))
				Render2D.outline(graphics, rowX, rowY, rowW, rowH - 2, ColorPalette.argb(ColorPalette.OUTLINE2))
			}

			Render2D.text(
				graphics, truncate(configData.displayName, textW, 1f), rowX + 5, rowY + 3,
				ColorPalette.argb(ColorPalette.TEXT), false, 1f
			)
			if (configData.description.isNotEmpty()) {
				Render2D.text(
					graphics, truncate(configData.description, textW, 0.75f), rowX + 5, rowY + 12,
					ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.75f
				)
			}

			when (configData) {
				is ConfigData.FeatureSwitch -> {
					val track = Rect(rowX + rowW - 30, rowY + 5, 24, 10)
					drawSwitch(graphics, track, configData.get())
					newRows.add(Control(configData, track))
				}

				is ConfigData.Switch -> {
					val track = Rect(rowX + rowW - 30, rowY + 5, 24, 10)
					drawSwitch(graphics, track, configData.get())
					newRows.add(Control(configData, track))
				}

				is ConfigData.Slider -> {
					val track = Rect(rowX + rowW - 80, rowY + 6, 60, 8)
					val valueRect = drawSlider(graphics, track, configData)
					newRows.add(Control(configData, track, valueRect))
				}

				is ConfigData.Text -> {
					val fieldW = minOf(160, rowW / 2)
					val field = Rect(rowX + rowW - fieldW - 2, rowY + 4, fieldW, 12)
					drawTextField(graphics, field, configData)
					newRows.add(Control(configData, field, field))
				}

				is ConfigData.Button -> {
					val btn = Rect(rowX + rowW - 62, rowY + 4, 58, 12)
					drawButton(graphics, btn, configData.label)
					newRows.add(Control(configData, btn))
				}

				else -> {}
			}
		}
		graphics.scissorStack.pop()
		rows = newRows
	}

	private fun truncate(text: String, maxPx: Int, scale: Float): String {
		if (maxPx <= 0) return ""
		if ((Render2D.width(text) * scale).toInt() <= maxPx) return text
		val ellipsis = "..."
		val ellipsisPx = (Render2D.width(ellipsis) * scale).toInt()
		var end = text.length
		while (end > 0 && (Render2D.width(text.substring(0, end)) * scale).toInt() + ellipsisPx > maxPx) end--
		return if (end <= 0) "" else text.substring(0, end) + ellipsis
	}

	private fun drawSwitch(graphics: GuiGraphicsExtractor, track: Rect, on: Boolean) {
		val color = if (on) ColorPalette.ENABLED else ColorPalette.DISABLED
		Render2D.rect(graphics, track.x, track.y, track.w, track.h, color)
		Render2D.outline(graphics, track.x, track.y, track.w, track.h, ColorPalette.argb(ColorPalette.OUTLINE))
		val knobW = track.w / 2
		val knobX = if (on) track.x + track.w - knobW else track.x
		Render2D.rect(graphics, knobX, track.y, knobW, track.h, ColorPalette.TEXT)
	}

	private fun drawSlider(graphics: GuiGraphicsExtractor, track: Rect, slider: ConfigData.Slider): Rect {
		Render2D.rect(graphics, track.x, track.y, track.w, track.h, ColorPalette.TERTIARY)
		Render2D.outline(graphics, track.x, track.y, track.w, track.h, ColorPalette.argb(ColorPalette.OUTLINE))
		val progress = ((slider.get() - slider.min) / (slider.max - slider.min)).coerceIn(0.0, 1.0)
		val filled = (track.w * progress).roundToInt()
		Render2D.rect(graphics, track.x, track.y, filled, track.h, ColorPalette.ACCENT)
		Render2D.rect(graphics, track.x + (filled - 1).coerceIn(0, track.w - 2), track.y - 1, 2, track.h + 2, ColorPalette.TEXT)

		val editingThis = editing === slider
		val valueText = if (editingThis) editBuffer + "_" else slider.get().roundToInt().toString()
		val textX = track.x + track.w + 3
		val color = if (editingThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
		Render2D.text(graphics, valueText, textX, track.y + 1, color, false, 0.9f)

		val labelWidth = Render2D.width(valueText).coerceAtLeast(Render2D.width(slider.get().roundToInt().toString())).coerceAtLeast(10)
		return Rect(textX - 3, track.y - 3, labelWidth + 8, track.h + 6)
	}

	private fun drawTextField(graphics: GuiGraphicsExtractor, rect: Rect, text: ConfigData.Text) {
		Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.TERTIARY)
		Render2D.outline(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.argb(ColorPalette.OUTLINE))
		val editingThis = editingText === text
		val shown = when {
			editingThis -> textBuffer + "_"
			text.secret && text.get().isNotEmpty() -> "*".repeat(12)
			text.get().isEmpty() -> "(empty)"
			else -> text.get()
		}
		val color = if (editingThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
		Render2D.text(graphics, shown, rect.x + 3, rect.y + 2, color, false, 0.85f)
	}

	private fun drawButton(graphics: GuiGraphicsExtractor, rect: Rect, label: String) {
		val hover = rect.contains(mouseX, mouseY)
		Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, if (hover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
		Render2D.outline(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.argb(ColorPalette.OUTLINE))
		val tx = rect.x + (rect.w - Render2D.width(label)) / 2
		Render2D.text(graphics, label, tx, rect.y + 2, ColorPalette.argb(ColorPalette.TEXT), false, 1f)
	}

	override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
		val mx = mouseButtonEvent.x.toInt()
		val my = mouseButtonEvent.y.toInt()
		if (mouseButtonEvent.button() != 0) return super.mouseClicked(mouseButtonEvent, bl)

		if (editing != null) {
			val editingControl = rows.firstOrNull { it.data === editing }
			if (editingControl?.valueRect?.contains(mx, my) != true) commitEdit()
		}
		if (editingText != null) {
			val editingControl = rows.firstOrNull { it.data === editingText }
			if (editingControl?.valueRect?.contains(mx, my) != true) commitTextEdit()
		}

		closeRect?.let { if (it.contains(mx, my)) { onClose(); return true } }

		var y = mainY + 32
		val entryH = 14
		for (sub in Config.subcategories) {
			val rect = Rect(leftX + 3, y, leftW - 6, entryH)
			if (rect.contains(mx, my)) {
				selected = sub
				scroll = 0.0
				return true
			}
			y += entryH + 2
		}

		for (control in rows) {
			when (val data = control.data) {
				is ConfigData.Slider -> {
					if (control.valueRect?.contains(mx, my) == true) {
						editing = data
						editBuffer = data.get().roundToInt().toString()
						return true
					}
				}

				is ConfigData.Text -> {
					if (control.valueRect?.contains(mx, my) == true) {
						editingText = data
						textBuffer = if (data.secret) "" else data.get()
						return true
					}
				}

				else -> {}
			}

			if (!control.track.contains(mx, my)) continue
			when (val data = control.data) {
				is ConfigData.Switch -> {
					data.set(!data.get())
					return true
				}

				is ConfigData.FeatureSwitch -> {
					data.set(!data.get())
					return true
				}

				is ConfigData.Slider -> {
					dragging = data
					updateSlider(data, mx, control.track)
					return true
				}

				is ConfigData.Button -> {
					data.action()
					return true
				}

				else -> {}
			}
		}

		return super.mouseClicked(mouseButtonEvent, bl)
	}

	private fun commitEdit() {
		val slider = editing ?: return
		editBuffer.toDoubleOrNull()?.let { slider.set(it.coerceIn(slider.min, slider.max)) }
		editing = null
		editBuffer = ""
	}

	private fun commitTextEdit() {
		val text = editingText ?: return
		if (!text.secret || textBuffer.isNotBlank()) text.set(textBuffer)
		editingText = null
		textBuffer = ""
	}

	override fun mouseDragged(mouseButtonEvent: MouseButtonEvent, d: Double, e: Double): Boolean {
		val slider = dragging ?: return super.mouseDragged(mouseButtonEvent, d, e)
		rows.firstOrNull { it.data === slider }?.let {
			updateSlider(slider, mouseButtonEvent.x.toInt(), it.track)
		}
		return true
	}

	override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
		if (dragging != null) {
			dragging = null
			return true
		}
		return super.mouseReleased(mouseButtonEvent)
	}

	private fun updateSlider(slider: ConfigData.Slider, mx: Int, track: Rect) {
		val t = ((mx - track.x).toDouble() / track.w.toDouble()).coerceIn(0.0, 1.0)
		val raw = slider.min + t * (slider.max - slider.min)
		val snapped = (raw / 10.0).roundToInt() * 10.0
		slider.set(snapped.coerceIn(slider.min, slider.max))
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (maxScroll > 0) {
			scroll -= verticalAmount * 15
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	override fun charTyped(characterEvent: CharacterEvent): Boolean {
		if (editingText != null) {
			val text = characterEvent.codepointAsString()
			if (text.length == 1 && text[0].code >= 32) {
				if (textBuffer.length < (editingText?.maxLength ?: 96)) textBuffer += text[0]
			}
			return true
		}
		if (editing == null) return super.charTyped(characterEvent)
		val text = characterEvent.codepointAsString()
		if (text.length == 1) {
			val c = text[0]
			if (c.isDigit() || (c == '.' && !editBuffer.contains('.'))) {
				if (editBuffer.length < 8) editBuffer += c
			}
		}
		return true
	}

	override fun keyPressed(keyEvent: KeyEvent): Boolean {
		if (editingText != null) {
			if (keyEvent.isPaste()) {
				val clip = Minecraft.getInstance().keyboardHandler.getClipboard()
				if (clip.isNotEmpty()) {
					val max = editingText?.maxLength ?: 96
					textBuffer = (textBuffer + clip.filter { it.code >= 32 }).take(max)
				}
				return true
			}
			when (keyEvent.key()) {
				InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					commitTextEdit()
					return true
				}

				InputConstants.KEY_ESCAPE -> {
					editingText = null
					textBuffer = ""
					return true
				}

				InputConstants.KEY_BACKSPACE -> {
					if (textBuffer.isNotEmpty()) textBuffer = textBuffer.dropLast(1)
					return true
				}

				InputConstants.KEY_DELETE -> {
					textBuffer = ""
					return true
				}
			}
			return true
		}
		if (editing == null) return super.keyPressed(keyEvent)
		when (keyEvent.key()) {
			InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				commitEdit()
				return true
			}

			InputConstants.KEY_ESCAPE -> {
				editing = null
				editBuffer = ""
				return true
			}

			InputConstants.KEY_BACKSPACE -> {
				if (editBuffer.isNotEmpty()) editBuffer = editBuffer.dropLast(1)
				return true
			}

			InputConstants.KEY_DELETE -> {
				editBuffer = ""
				return true
			}
		}
		return true
	}

	override fun isPauseScreen(): Boolean = false
}
