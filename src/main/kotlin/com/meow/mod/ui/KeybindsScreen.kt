package com.meow.mod.ui

import com.meow.mod.features.KeyCommand
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class KeybindsScreen(private val parent: Screen?) : Screen(Component.literal("MeowMod.Keybinds")) {
	private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
		fun contains(mx: Int, my: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private data class Row(var key: Rect, var command: Rect, var remove: Rect)

	private var rows = listOf<Row>()
	private var addRect: Rect? = null
	private var doneRect: Rect? = null
	private var mouseX = 0
	private var mouseY = 0

	private var listening = -1
	private var editing = -1
	private var buffer = ""

	private val panelW get() = minOf(width - 60, 460)
	private val panelH get() = minOf(height - 60, 360)
	private val panelX get() = (width - panelW) / 2
	private val panelY get() = (height - panelH) / 2

	override fun init() {}

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, a)
		this.mouseX = mouseX
		this.mouseY = mouseY

		Render2D.rect(graphics, 0, 0, width, height, ColorPalette.BACKGROUND)
		Render2D.rect(graphics, panelX, panelY, panelW, panelH, ColorPalette.PRIMARY)
		Render2D.outline(graphics, panelX, panelY, panelW, panelH, ColorPalette.argb(ColorPalette.OUTLINE))

		Render2D.text(graphics, "Key Command", panelX + 8, panelY + 8, ColorPalette.argb(ColorPalette.TEXT), false, 1.3f)
		Render2D.text(
			graphics,
			"Click a key to rebind, click a command to edit it.",
			panelX + 8, panelY + 20,
			ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.85f
		)
		Render2D.argb(graphics, panelX + 1, panelY + 32, panelW - 2, 1, ColorPalette.argb(ColorPalette.OUTLINE))

		drawRows(graphics)
		drawButtons(graphics)
	}

	private fun drawRows(graphics: GuiGraphicsExtractor) {
		val newRows = mutableListOf<Row>()
		val list = KeyCommand.bindings
		val top = panelY + 40
		val rowH = 22
		val leftX = panelX + 8
		val rightEdge = panelX + panelW - 8
		val maxRows = ((panelH - 80) / rowH)

		list.take(maxRows).forEachIndexed { index, binding ->
			val y = top + index * rowH
			val keyRect = Rect(leftX, y, 84, 16)
			val removeRect = Rect(rightEdge - 16, y, 16, 16)
			val commandRect = Rect(leftX + 88, y, removeRect.x - (leftX + 88) - 4, 16)

			val listeningThis = listening == index
			Render2D.rect(graphics, keyRect.x, keyRect.y, keyRect.w, keyRect.h, ColorPalette.TERTIARY)
			Render2D.outline(graphics, keyRect.x, keyRect.y, keyRect.w, keyRect.h, ColorPalette.argb(ColorPalette.OUTLINE))
			val keyLabel = if (listeningThis) "Press a key..." else keyName(binding.key)
			val keyColor = if (listeningThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.TEXT)
			Render2D.text(graphics, keyLabel, keyRect.x + 4, keyRect.y + 4, keyColor, false, 0.9f)

			Render2D.rect(graphics, commandRect.x, commandRect.y, commandRect.w, commandRect.h, ColorPalette.TERTIARY)
			Render2D.outline(graphics, commandRect.x, commandRect.y, commandRect.w, commandRect.h, ColorPalette.argb(ColorPalette.OUTLINE))
			val editingThis = editing == index
			val value = if (editingThis) buffer + "_" else binding.command
			val shown = if (value.isEmpty() && !editingThis) "(click to edit command)" else value
			val cmdColor = if (editingThis) ColorPalette.argb(ColorPalette.ACCENT) else ColorPalette.argb(ColorPalette.LIGHT_TEXT)
			Render2D.text(graphics, shown, commandRect.x + 4, commandRect.y + 4, cmdColor, false, 0.9f)

			val hover = removeRect.contains(mouseX, mouseY)
			Render2D.rect(graphics, removeRect.x, removeRect.y, removeRect.w, removeRect.h, if (hover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
			Render2D.outline(graphics, removeRect.x, removeRect.y, removeRect.w, removeRect.h, ColorPalette.argb(ColorPalette.OUTLINE))
			val xLabel = "x"
			Render2D.text(
				graphics, xLabel,
				removeRect.x + (removeRect.w - Render2D.width(xLabel)) / 2, removeRect.y + 4,
				ColorPalette.argb(ColorPalette.TEXT), false, 0.9f
			)

			newRows.add(Row(keyRect, commandRect, removeRect))
		}
		rows = newRows

		if (list.isEmpty()) {
			Render2D.text(
				graphics, "No bindings yet. Click \"Add Binding\" below.",
				leftX, top + 4,
				ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.9f
			)
		}
	}

	private fun drawButtons(graphics: GuiGraphicsExtractor) {
		val addW = 120
		val add = Rect(panelX + 8, panelY + panelH - 24, addW, 16)
		addRect = add
		val addHover = add.contains(mouseX, mouseY)
		Render2D.rect(graphics, add.x, add.y, add.w, add.h, if (addHover) ColorPalette.OUTLINE2 else ColorPalette.TERTIARY)
		Render2D.outline(graphics, add.x, add.y, add.w, add.h, ColorPalette.argb(ColorPalette.OUTLINE))
		Render2D.text(graphics, "Add Binding", add.x + 6, add.y + 4, ColorPalette.argb(ColorPalette.TEXT), false, 0.9f)

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

		if (editing >= 0) {
			val row = rows.getOrNull(editing)
			if (row?.command?.contains(mx, my) != true) commitEdit()
		}

		addRect?.let {
			if (it.contains(mx, my)) {
				KeyCommand.bindings.add(KeyCommand.Binding(0, ""))
				KeyCommand.save()
				return true
			}
		}
		doneRect?.let {
			if (it.contains(mx, my)) {
				onClose()
				return true
			}
		}

		for (i in rows.indices) {
			val row = rows[i]
			if (row.key.contains(mx, my)) {
				listening = if (listening == i) -1 else i
				return true
			}
			if (row.command.contains(mx, my)) {
				editing = i
				buffer = KeyCommand.bindings.getOrNull(i)?.command ?: ""
				return true
			}
			if (row.remove.contains(mx, my)) {
				if (i in KeyCommand.bindings.indices) {
					KeyCommand.bindings.removeAt(i)
					KeyCommand.save()
				}
				listening = -1
				editing = -1
				return true
			}
		}

		return super.mouseClicked(event, doubled)
	}

	private fun commitEdit() {
		val i = editing
		if (i >= 0) {
			KeyCommand.bindings.getOrNull(i)?.command = buffer
			KeyCommand.save()
		}
		editing = -1
		buffer = ""
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		if (editing < 0) return super.charTyped(event)
		val text = event.codepointAsString()
		if (text.length == 1 && text[0].code >= 32 && buffer.length < 96) buffer += text[0]
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (listening >= 0) {
			val i = listening
			when (event.key()) {
				InputConstants.KEY_ESCAPE -> {
					listening = -1
					return true
				}

				InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> {
					KeyCommand.bindings.getOrNull(i)?.key = 0
					KeyCommand.save()
					listening = -1
					return true
				}

				else -> {
					KeyCommand.bindings.getOrNull(i)?.key = event.key()
					KeyCommand.save()
					listening = -1
					return true
				}
			}
		}

		if (editing >= 0) {
			when (event.key()) {
				InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
					commitEdit()
					return true
				}

				InputConstants.KEY_ESCAPE -> {
					editing = -1
					buffer = ""
					return true
				}

				InputConstants.KEY_BACKSPACE -> {
					if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
					return true
				}

				InputConstants.KEY_DELETE -> {
					buffer = ""
					return true
				}
			}
			return true
		}

		return super.keyPressed(event)
	}

	override fun onClose() {
		if (parent != null) Minecraft.getInstance().setScreenAndShow(parent)
		else super.onClose()
	}

	override fun isPauseScreen(): Boolean = false

	private fun keyName(key: Int): String {
		if (key <= 0) return "None"
		return try {
			InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string
		} catch (_: Exception) {
			"Key $key"
		}
	}
}
