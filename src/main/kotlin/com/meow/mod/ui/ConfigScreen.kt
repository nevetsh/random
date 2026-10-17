package com.meow.mod.ui

import com.meow.mod.MeowMod
import com.meow.mod.api.ChatUtils
import com.meow.mod.config.Config
import com.meow.mod.config.ConfigData
import com.meow.mod.features.AutoFragrun
import com.meow.mod.features.ModuleKeys
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

	// The page the screen opens on: the one that was open when it was last closed, or
	// General the first time. Remembered here rather than in the config, so it lasts for
	// the session and nothing about it is written to disk.
	private var selected: String = lastPage ?: (Config.subcategories.firstOrNull() ?: Config.CATEGORY_NAME)
	private var scroll = 0.0
	private var maxScroll = 0.0
	private var dragging: ConfigData.Slider? = null
	private var editing: ConfigData.Slider? = null
	private var editBuffer: String = ""
	private var editingText: ConfigData.Text? = null
	private var textBuffer: String = ""

	// The dropdown that is open, if any. The list is drawn over everything else, and its rows
	// are worked out from the open control's own box when a click arrives.
	private var expandedChoice: ConfigData.Choice? = null

	private var rows = listOf<Control>()
	private var closeRect: Rect? = null
	private var mouseX = 0
	private var mouseY = 0

	// The sidebar's search box, which narrows the module list down.
	private var search = ""
	private var searching = false
	private var searchRect: Rect? = null

	// The keybind button in a module page's header, and whether it is waiting for the key that
	// module should answer to (see ModuleKeys).
	private var keybindRect: Rect? = null
	private var keybindFor: ConfigData.FeatureSwitch? = null
	private var listeningKeybind = false

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
		lastPage = selected
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
		drawSearch(graphics)
		drawClose(graphics)
		drawContent(graphics, mouseX, mouseY)
		drawChoiceOverlay(graphics)
	}

	private data class Entry(val name: String, val rect: Rect)

	// General stays at the top - it is the page the screen opens on - and the modules are listed
	// after it in alphabetical order. The search box above Close narrows the list down.
	private fun listedSubcategories(): List<String> {
		val query = search.trim()
		return Config.subcategories
			.filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
			.sortedWith(compareByDescending<String> { it == Config.GENERAL }.thenBy { it.lowercase() })
	}

	private fun sidebarEntries(): List<Entry> = listedSubcategories().mapIndexed { index, sub ->
		Entry(sub, Rect(leftX + 3, mainY + 32 + index * (ENTRY_H + 2), leftW - 6, ENTRY_H))
	}

	private fun drawCategories(graphics: GuiGraphicsExtractor) {
		for (entry in sidebarEntries()) {
			val rect = entry.rect
			val active = entry.name == selected
			if (active) Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.TERTIARY)
			val color = if (active) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
			Render2D.text(graphics, entry.name, rect.x + 4, rect.y + 3, color, false, 1f)
			if (active) Render2D.argb(graphics, rect.x, rect.y + rect.h - 1, rect.w, 1, ColorPalette.argb(ColorPalette.ACCENT))
		}
	}

	private fun drawSearch(graphics: GuiGraphicsExtractor) {
		val rect = Rect(leftX + 3, mainY + mainH - 38, leftW - 6, 14)
		searchRect = rect
		Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.TERTIARY)
		Render2D.outline(
			graphics, rect.x, rect.y, rect.w, rect.h,
			ColorPalette.argb(if (searching) ColorPalette.ACCENT else ColorPalette.OUTLINE)
		)
		val label = when {
			searching -> search + "_"
			search.isEmpty() -> "Search modules..."
			else -> search
		}
		val color = when {
			searching -> ColorPalette.argb(ColorPalette.ACCENT)
			search.isEmpty() -> ColorPalette.argb(ColorPalette.LIGHT_TEXT)
			else -> ColorPalette.argb(ColorPalette.TEXT)
		}
		Render2D.text(graphics, truncate(label, rect.w - 6, 0.85f), rect.x + 3, rect.y + 3, color, false, 0.85f)
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

		// A module's page carries its keybind beside the title: General belongs to no module
		// and so has nothing to bind - its switches are settings of their own pages. The
		// button names the key it is set to, or asks for one. The module is looked up by page
		// rather than from the rows, so a page whose on/off switch is a dropdown still has one.
		val data = Config.configsFor(selected).filter { visibleOnPage(it) }
		val module = if (selected == Config.GENERAL) null else Config.featureFor(selected)
		keybindFor = module
		keybindRect = null
		if (module != null) {
			val label = when {
				listeningKeybind -> "Press a key..."
				module.toggleKey > 0 -> "Key: ${ModuleKeys.keyName(module.toggleKey)}"
				else -> "Keybind"
			}
			val btnW = (Render2D.width(label) + 10f).roundToInt().coerceAtLeast(44)
			val btn = Rect(rightX + rightW - btnW - 4, rightY + 2, btnW, 12)
			drawButton(graphics, btn, label)
			keybindRect = btn
		}
		Render2D.argb(graphics, rightX + 1, rightY + headerH - 1, rightW - 2, 1, ColorPalette.argb(ColorPalette.OUTLINE))

		val contentTop = rightY + headerH
		val contentH = rightY + rightH - contentTop
		val rowH = 20
		val total = data.size * rowH
		maxScroll = (total - contentH).coerceAtLeast(0).toDouble()
		if (scroll > maxScroll) scroll = maxScroll
		if (scroll < 0.0) scroll = 0.0

		val newRows = mutableListOf<Control>()
		graphics.scissorStack.push(ScreenRectangle(rightX, contentTop, rightW, contentH))
		data.forEachIndexed { index, configData ->
			val rowY = contentTop + (index * rowH) - scroll.roundToInt()
			if (rowY + rowH < contentTop || rowY > contentTop + contentH) return@forEachIndexed

			// A heading, centred across the page, splitting it into named groups.
			if (configData is ConfigData.Section) {
				val label = configData.title
				val labelW = (Render2D.width(label) * HEADING_SCALE).roundToInt()
				Render2D.text(
					graphics, label, rightX + (rightW - labelW) / 2, rowY + 5,
					ColorPalette.argb(ColorPalette.ACCENT), false, HEADING_SCALE
				)
				return@forEachIndexed
			}

			val rowX = rightX + 4
			val rowW = rightW - 8

			val controlW = when (configData) {
				is ConfigData.FeatureSwitch, is ConfigData.Switch -> 30
				is ConfigData.Slider -> 104
				is ConfigData.Text -> minOf(160, rowW / 2) + 6
				is ConfigData.Button -> 66
				is ConfigData.Choice -> minOf(150, rowW / 2) + 6
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

				is ConfigData.Choice -> {
					val fieldW = minOf(150, rowW / 2)
					val box = Rect(rowX + rowW - fieldW - 2, rowY + 4, fieldW, 12)
					drawChoiceBox(graphics, box, configData)
					newRows.add(Control(configData, box))
				}

				else -> {}
			}
		}
		graphics.scissorStack.pop()
		rows = newRows
	}

	// A module's own settings are only listed while it is on: with the switch off there is
	// nothing to set, so the page reads as just the modules it offers. The switch itself is
	// never gated - it is what turns the module back on - and neither is a module's dropdown
	// control (see ConfigData.FeatureSwitch.control). A setting a module lists on another page
	// (its command keys live in General) belongs to that page and is left alone, since it works
	// whatever the module is set to.
	private fun visibleOnPage(item: ConfigData<*>): Boolean {
		val owner = item.parent ?: return true
		if (owner.subcategory != selected) return true
		return owner.get() || item === owner.control
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

	// A whole number reads as "10", and only a half step drags a decimal in ("5.5"), so a
	// slider that counts in steps of one stays as short as it always was.
	private fun sliderText(value: Double): String {
		val text = value.toString()
		return if (text.endsWith(".0")) text.dropLast(2) else text
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
		val valueText = if (editingThis) editBuffer + "_" else sliderText(slider.get())
		val textX = track.x + track.w + 3
		val color = if (editingThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
		Render2D.text(graphics, valueText, textX, track.y + 1, color, false, 0.9f)

		val labelWidth = Render2D.width(valueText).coerceAtLeast(Render2D.width(sliderText(slider.get()))).coerceAtLeast(10)
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

	// A dropdown's closed face: the chosen option and a caret. The open list is drawn by
	// drawChoiceOverlay, over the rest of the page.
	private fun drawChoiceBox(graphics: GuiGraphicsExtractor, rect: Rect, choice: ConfigData.Choice) {
		val hover = rect.contains(mouseX, mouseY)
		Render2D.rect(graphics, rect.x, rect.y, rect.w, rect.h, if (hover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
		Render2D.outline(graphics, rect.x, rect.y, rect.w, rect.h, ColorPalette.argb(ColorPalette.OUTLINE))
		Render2D.text(graphics, truncate(choice.get(), rect.w - 12, 0.85f), rect.x + 3, rect.y + 2, ColorPalette.argb(ColorPalette.TEXT), false, 0.85f)
		Render2D.text(graphics, "v", rect.x + rect.w - 8, rect.y + 2, ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.85f)
	}

	private fun choiceMenu(anchor: Rect, choice: ConfigData.Choice): Rect =
		Rect(anchor.x, anchor.y + anchor.h + 1, anchor.w, choice.options.size * CHOICE_ITEM_H)

	private fun choiceItem(menu: Rect, index: Int): Rect =
		Rect(menu.x, menu.y + index * CHOICE_ITEM_H, menu.w, CHOICE_ITEM_H)

	private fun drawChoiceOverlay(graphics: GuiGraphicsExtractor) {
		val choice = expandedChoice ?: return
		val control = rows.firstOrNull { it.data === choice } ?: return
		val menu = choiceMenu(control.track, choice)
		Render2D.rect(graphics, menu.x, menu.y, menu.w, menu.h, ColorPalette.PRIMARY)
		Render2D.outline(graphics, menu.x, menu.y, menu.w, menu.h, ColorPalette.argb(ColorPalette.ACCENT))
		choice.options.forEachIndexed { index, option ->
			val item = choiceItem(menu, index)
			if (option == choice.get()) Render2D.rect(graphics, item.x, item.y, item.w, item.h, ColorPalette.TERTIARY)
			val color = if (item.contains(mouseX, mouseY)) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.TEXT)
			Render2D.text(graphics, truncate(option, item.w - 6, 0.85f), item.x + 3, item.y + 2, color, false, 0.85f)
		}
	}

	override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
		val mx = mouseButtonEvent.x.toInt()
		val my = mouseButtonEvent.y.toInt()
		if (mouseButtonEvent.button() != 0) return super.mouseClicked(mouseButtonEvent, bl)

		// An open dropdown owns the next click: it either picks an option or is dismissed, and
		// nothing underneath it is touched either way.
		expandedChoice?.let { choice ->
			val control = rows.firstOrNull { it.data === choice }
			if (control != null) {
				val menu = choiceMenu(control.track, choice)
				choice.options.forEachIndexed { index, option ->
					if (choiceItem(menu, index).contains(mx, my)) {
						choice.set(option)
						expandedChoice = null
						return true
					}
				}
			}
			expandedChoice = null
			return true
		}

		if (editing != null) {
			val editingControl = rows.firstOrNull { it.data === editing }
			if (editingControl?.valueRect?.contains(mx, my) != true) commitEdit()
		}
		if (editingText != null) {
			val editingControl = rows.firstOrNull { it.data === editingText }
			if (editingControl?.valueRect?.contains(mx, my) != true) commitTextEdit()
		}

		closeRect?.let { if (it.contains(mx, my)) { onClose(); return true } }

		if (searchRect?.contains(mx, my) == true) {
			searching = true
			return true
		}
		// Any other click puts the search box away, so the next key press is the game's.
		searching = false

		keybindRect?.let { rect ->
			if (rect.contains(mx, my)) {
				listeningKeybind = !listeningKeybind
				return true
			}
		}
		// Clicking anywhere else stops the button listening, for the same reason.
		listeningKeybind = false

		for (entry in sidebarEntries()) {
			if (entry.rect.contains(mx, my)) {
				selected = entry.name
				lastPage = entry.name
				scroll = 0.0
				listeningKeybind = false
				expandedChoice = null
				return true
			}
		}

		for (control in rows) {
			when (val data = control.data) {
				is ConfigData.Slider -> {
					if (control.valueRect?.contains(mx, my) == true) {
						editing = data
						editBuffer = sliderText(data.get())
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

				is ConfigData.Choice -> {
					expandedChoice = data
					return true
				}

				else -> {}
			}
		}

		return super.mouseClicked(mouseButtonEvent, bl)
	}

	private fun commitEdit() {
		val slider = editing ?: return
		editBuffer.toDoubleOrNull()?.let { slider.set(slider.snap(it).coerceIn(slider.min, slider.max)) }
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
		slider.set(slider.snap(raw).coerceIn(slider.min, slider.max))
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (maxScroll > 0) {
			scroll -= verticalAmount * 15
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
	}

	override fun charTyped(characterEvent: CharacterEvent): Boolean {
		if (searching) {
			val text = characterEvent.codepointAsString()
			if (text.length == 1 && text[0].code >= 32 && search.length < SEARCH_MAX) search += text[0]
			return true
		}
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
		// The keybind button takes every key while it listens: it is the one control that
		// means nothing without the key it is waiting for.
		if (listeningKeybind) {
			val feature = keybindFor
			listeningKeybind = false
			if (feature == null) return true
			when (keyEvent.key()) {
				InputConstants.KEY_ESCAPE -> return true

				InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> {
					ModuleKeys.bind(feature, 0)
					ChatUtils.sendMessage("&f${feature.displayName}&7: keybind cleared.", true)
					return true
				}

				else -> {
					val taken = ModuleKeys.bind(feature, keyEvent.key())
					val note = StringBuilder()
					if (taken != null) note.append(" ${taken.displayName} gave up that key.")
					if (AutoFragrun.commandForKey(keyEvent.key()) != null) note.append(" A command binding runs it, so that wins.")
					ChatUtils.sendMessage(
						"&f${feature.displayName}&7 bound to &f${ModuleKeys.keyName(keyEvent.key())}&7.$note",
						true
					)
					return true
				}
			}
		}
		if (searching) {
			when (keyEvent.key()) {
				InputConstants.KEY_ESCAPE, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					searching = false
					return true
				}

				InputConstants.KEY_BACKSPACE -> {
					if (search.isNotEmpty()) search = search.dropLast(1)
					return true
				}

				InputConstants.KEY_DELETE -> {
					search = ""
					return true
				}
			}
			return true
		}
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

	private companion object {
		// The sidebar's entry height, and how far a search may run before it stops taking more.
		const val ENTRY_H = 14
		const val SEARCH_MAX = 24

		// The page the last opened screen was showing, so /mm can reopen on it. General
		// the first time, when nothing has been remembered yet.
		private var lastPage: String? = null

		// Headings are drawn a touch smaller than the settings under them.
		const val HEADING_SCALE = 0.95f

		// The height of one row in an open dropdown.
		const val CHOICE_ITEM_H = 12
	}
}
